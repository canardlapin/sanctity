package sanctity.postgres

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.Fragment
import doobie.implicits.*
import munit.FunSuite
import sanctity.app.RepositoryCli
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.BidsInterpreter
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.sql.SQLException
import java.util.UUID
import scala.concurrent.duration.*

final class ServicePrivilegesSuite extends FunSuite:
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def sqlRun(db: PostgresDatabase[IO], text: String): IO[Unit] = Fragment.const(text).update.run.transact(db.transactor).void
  private def deniedSql(db: PostgresDatabase[IO], text: String): IO[Unit] = sqlRun(db, text).attempt.map {
    case Left(e: SQLException) => assertEquals(e.getSQLState, "42501", text)
    case other => fail(s"Expected privilege denial for $text; observed ${other.map(_ => "success").left.map(_.getClass.getSimpleName)}")
  }
  private def fixture(run: (PgFixture, PostgresDatabase[IO], String) => IO[Unit]): Unit =
    PostgresFixture.resource.use { f =>
      val role = "sanctity_rt_" + UUID.randomUUID().toString.replace("-", "")
      val password = UUID.randomUUID().toString
      Resource.make(for
        database <- sql"SELECT current_database()".query[String].unique.transact(f.database.transactor)
        _ <- sqlRun(f.database, s"""REVOKE CREATE,TEMPORARY ON DATABASE "$database" FROM PUBLIC""")
        _ <- sqlRun(f.database, "REVOKE CREATE ON SCHEMA public FROM PUBLIC")
        _ <- sqlRun(f.database, s"""CREATE ROLE "$role" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$password'""")
      yield ())(_ => sqlRun(f.database, s"""DROP OWNED BY "$role"""") *> sqlRun(f.database, s"""DROP ROLE "$role""""))
        .use { _ =>
          val runtime = DatabaseConfig.from(f.config.jdbcUrl, role, password, f.config.schema).toOption.get
          f.database.grantRuntime(role) *> PostgresDatabase.open[IO](runtime).use(db => run(f, db, role))
        }
    }.unsafeRunSync()

  test("runtime can authenticate, prepare, accept and read using the required transaction locks") {
    fixture { (f, db, _) =>
      val ids = IdSource.random[IO]
      val catalog = db.catalog(ids)
      val repository = new NativeRepository[IO](catalog, f.system.store, new BidsInterpreter[IO](f.system.store), ids)
      for
        report <- f.database.organization.preview(f.system.context.principal)
        _ <- f.database.organization.bootstrap(f.system.context.principal,PostgresOrganizationCatalog.previewDigest(report))
        _ <- db.checkCompatibility()
        _ <- db.checkRuntimeRole
        _ <- db.checkOrganizationReady
        credential <- checked(f.database.credentials(ids).issue(f.system.context.principal, "runtime-test", 600))
        context <- checked(db.credentials(ids).authenticate(credential.secret))
        preview <- checked(repository.importNative(context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        receipt <- checked(repository.accept(context, preview.candidate.id, IdempotencyKey.from("runtime-accept").toOption.get))
        release <- checked(catalog.release(context, receipt.release))
        _ = assertEquals(release.manifest.id, preview.candidate.manifest.id)
        _ = assertEquals(release.interpretation.id, preview.candidate.interpretation.id)
        retry <- checked(repository.accept(context, preview.candidate.id, IdempotencyKey.from("runtime-accept").toOption.get))
        _ = assertEquals(retry, receipt)
        reader <- ids.next[PrincipalTag]
        share <- checked(catalog.grant(context,f.system.dataset.id,reader,Some(receipt.release.release),Set(Action.ReadMetadata)))
        shared <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadMetadata,Some(receipt.release.release))
        _ = assert(shared.isRight)
        _ <- checked(catalog.revoke(context,f.system.dataset.id,share.id))
        forbiddenEvent <- sqlRun(db,"INSERT INTO organization_event(id,revision,installation,writer_epoch,previous_hash,payload_digest,applied_hash,actor,scope,request_key,receipt,payload,recorded_at) SELECT gen_random_uuid(),revision+1000,installation,writer_epoch,previous_hash,payload_digest,applied_hash,actor,'forged-operator','forged-operator',receipt,jsonb_set(payload,'{kind}','\"DisablePrincipal\"'::jsonb),recorded_at FROM organization_event ORDER BY revision LIMIT 1").attempt
        _ = assert(forbiddenEvent.left.toOption.exists { case e: java.sql.SQLException => e.getSQLState == "42501"; case _ => false })
        issued <- db.credentials(ids).issue(context.principal, "forbidden", 60)
        revoked <- db.credentials(ids).revoke(credential.id)
        _ = assert(issued.isLeft && revoked.isLeft)
        _ <- checked(f.database.credentials(ids).revoke(credential.id))
        stale <- catalog.release(context, receipt.release)
        _ = assert(stale.isLeft)
      yield ()
    }
  }

  test("runtime denies DDL, immutable-history mutation, credential administration and recovery/migration commands") {
    fixture { (f, db, role) =>
      for
        _ <- db.checkRuntimeRole
        _ <- Vector(
          "CREATE SCHEMA forbidden_schema",
          "CREATE TABLE forbidden_table (id int)",
          "CREATE TEMP TABLE forbidden_temp (id int)",
          "ALTER TABLE dataset ADD COLUMN forbidden int",
          "DROP TABLE dataset",
          "TRUNCATE audit",
          "UPDATE release SET accepted_at=clock_timestamp()",
          "DELETE FROM release",
          "UPDATE organization_policy SET initialized=TRUE",
          "UPDATE organization_policy SET writer_epoch=1",
          "DELETE FROM organization_event",
          "UPDATE principal SET active=FALSE",
          "UPDATE principal SET authority_eligibility='{}'::jsonb",
          "UPDATE dataset SET authority_lifecycle='Withdrawn'",
          "UPDATE release SET authority_lifecycle='Available'",
          "UPDATE organization_project SET payload='{}'::jsonb",
          "UPDATE organization_event SET receipt='{}'::jsonb",
          "UPDATE schema_migration SET checksum='forbidden'",
          "UPDATE repository_state SET recovery_required=FALSE",
          "UPDATE dataset SET curator='00000000-0000-0000-0000-000000000001'::uuid",
          "UPDATE api_credential SET revoked_at=NULL",
          "UPDATE api_credential SET token_digest=repeat('0',64)",
          "INSERT INTO api_credential(id,principal_id,token_digest,label,expires_at) VALUES(gen_random_uuid(),gen_random_uuid(),repeat('0',64),'forbidden',clock_timestamp())",
          s"""ALTER ROLE "$role" CREATEDB"""
        ).traverse_(deniedSql(db, _))
        migrated <- db.migrate.attempt
        bootstrap <- RepositoryCli.command(db, List("create", UUID.randomUUID().toString, UUID.randomUUID().toString)).attempt
        recovery <- db.recovery.requireReconciliation.attempt
        operator <- db.requireOperator.attempt
        _ = assert(migrated.isLeft && bootstrap.isLeft && recovery.isLeft && operator.isLeft)
        other = "other_" + UUID.randomUUID().toString.replace("-", "")
        _ <- Resource.make(sqlRun(f.database, s"""CREATE SCHEMA "$other"; CREATE TABLE "$other".private_data (id int)"""))(
          _ => sqlRun(f.database, s"""DROP SCHEMA "$other" CASCADE"""))
          .use(_ => deniedSql(db, s"""UPDATE "$other".private_data SET id=1"""))
      yield ()
    }
  }

  test("privilege escalation, schema drift/newer versions and a closed recovery gate refuse readiness") {
    fixture { (f, db, role) => for
      _ <- db.checkCompatibility()
      _ <- db.checkRuntimeRole
      ownerRejected <- f.database.checkRuntimeRole.attempt
      _ = assert(ownerRejected.isLeft)
      database <- sql"SELECT current_database()".query[String].unique.transact(f.database.transactor)
      _ <- sqlRun(f.database, s"""GRANT CREATE ON DATABASE "$database" TO "$role"""")
      elevated <- db.checkRuntimeRole.attempt
      _ = assert(elevated.isLeft)
      _ <- sqlRun(f.database, s"""REVOKE CREATE ON DATABASE "$database" FROM "$role"""")
      _ <- sqlRun(f.database, s"""GRANT UPDATE(recovery_required) ON repository_state TO "$role"""")
      recoveryPower <- db.checkRuntimeRole.attempt
      _ = assert(recoveryPower.isLeft)
      _ <- sqlRun(f.database, s"""REVOKE UPDATE(recovery_required) ON repository_state FROM "$role"""")
      _ <- db.checkRuntimeRole
      original <- sql"SELECT checksum FROM schema_migration WHERE version=1".query[String].unique.transact(f.database.transactor)
      _ <- sqlRun(f.database, "UPDATE schema_migration SET checksum='drift' WHERE version=1")
      drift <- db.checkCompatibility().attempt
      _ = assert(drift.isLeft)
      unchanged <- sql"SELECT checksum FROM schema_migration WHERE version=1".query[String].unique.transact(f.database.transactor)
      _ = assertEquals(unchanged, "drift")
      _ <- sql"UPDATE schema_migration SET checksum=$original WHERE version=1".update.run.transact(f.database.transactor)
      _ <- sqlRun(f.database, "INSERT INTO schema_migration(version,checksum) VALUES(999,'newer')")
      downgrade <- db.checkCompatibility().attempt
      _ = assert(downgrade.isLeft)
      _ <- sqlRun(f.database, "DELETE FROM schema_migration WHERE version=999")
      _ <- f.database.recovery.requireReconciliation
      closed <- db.checkCompatibility().attempt
      _ = assert(closed.isLeft)
      _ <- db.checkCompatibility(requireOpen = false)
    yield () }
  }

  test("terminating a migration backend rolls back all DDL and a later explicit retry is compatible") {
    PostgresFixture.resource.use { f =>
      val schema = "interrupted_" + UUID.randomUUID().toString.replace("-", "")
      val trigger = "interrupt_" + UUID.randomUUID().toString.replace("-", "")
      val config = DatabaseConfig.from(f.config.jdbcUrl, f.config.user, f.config.password, schema).toOption.get
      val setup = s"""CREATE FUNCTION "${f.config.schema}"."$trigger"() RETURNS event_trigger AS $$$$
        BEGIN IF current_schema()='$schema' AND TG_TAG='CREATE TABLE' THEN PERFORM pg_sleep(30); END IF; END;
        $$$$ LANGUAGE plpgsql; CREATE EVENT TRIGGER "$trigger" ON ddl_command_end EXECUTE FUNCTION "${f.config.schema}"."$trigger"()"""
      def waiting: IO[Int] =
        sql"""SELECT pid FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND wait_event='PgSleep'
          AND query LIKE '%CREATE TABLE IF NOT EXISTS schema_migration%'""".query[Int].option.transact(f.database.transactor)
          .flatMap(_.fold(IO.sleep(50.millis) *> waiting)(IO.pure))
      PostgresDatabase.open[IO](config).use { candidate =>
        (Resource.make(sqlRun(f.database, setup))(_ => sqlRun(f.database,
          s"""DROP EVENT TRIGGER "$trigger"; DROP FUNCTION "${f.config.schema}"."$trigger"()"""))).use { _ =>
          for
            worker <- candidate.migrate.attempt.start
            pid <- waiting.timeout(10.seconds)
            killed <- sql"SELECT pg_terminate_backend($pid)".query[Boolean].unique.transact(f.database.transactor)
            _ = assert(killed)
            result <- worker.joinWithNever.timeout(5.seconds)
            _ = assert(result.isLeft)
            exists <- sql"SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=$schema)".query[Boolean].unique.transact(f.database.transactor)
            _ = assert(!exists, "transactional schema creation must roll back with the interrupted migration")
          yield ()
        } *> candidate.migrate *> candidate.checkCompatibility()
      }.guarantee(sqlRun(f.database, s"""DROP SCHEMA IF EXISTS "$schema" CASCADE"""))
    }.unsafeRunSync()
  }
