package sanctity.postgres

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.Fragment
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.FunSuite
import sanctity.app.SyntheticFixture
import sanctity.application.{Checked, IdSource, NativeRepository, SemanticInterpreter}
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource

import java.nio.file.Files
import java.sql.DriverManager
import scala.concurrent.duration.*

final class TransactionSuite extends FunSuite:
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def key(value: String): IdempotencyKey = IdempotencyKey.from(value).toOption.get
  private def path(value: String): RelPath = RelPath.from(value).toOption.get
  private def fixture(run: PgFixture => IO[Unit]): Unit = PostgresFixture.resource.use(run).unsafeRunSync()
  private def prepared(f: PgFixture): IO[Preview] =
    checked(f.system.repository.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
  private def counts(f: PgFixture): IO[(Long, Long, Long, Long)] =
    sql"""SELECT (SELECT count(*) FROM release),(SELECT count(*) FROM acceptance_receipt),
      (SELECT count(*) FROM audit),(SELECT count(*) FROM outbox)""".query[(Long, Long, Long, Long)].unique.transact(f.database.transactor)
  private def sqlRun(f: PgFixture, sql: String): IO[Unit] = Fragment.const(sql).update.run.transact(f.database.transactor).void

  test("migration is idempotent, serialized and refuses checksum drift") {
    fixture { f =>
      for
        _ <- (f.database.migrate, f.database.migrate).parTupled
        versions <- sql"SELECT count(*) FROM schema_migration".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(versions, 13L)   // through V013 (dataset labels); moves with every migration
        _ <- sqlRun(f, "UPDATE schema_migration SET checksum='changed'")
        drift <- f.database.migrate.attempt
        _ = assert(drift.isLeft)
      yield ()
    }
  }

  test("prepared evidence and accepted receipts survive independent connection pools without reinterpretation") {
    fixture { f =>
      for
        preview <- prepared(f)
        _ <- PostgresDatabase.open[IO](f.config).use { reopened =>
          val ids = IdSource.random[IO]
          val catalog = reopened.catalog(ids)
          val forbiddenInterpreter = new SemanticInterpreter[IO]:
            def prepare(manifest: Manifest): IO[(Interpretation, Assessment)] =
              IO.raiseError(new IllegalStateException(s"unexpected reinterpretation: ${manifest.id}"))
          val repository = new NativeRepository[IO](catalog, f.system.store, forbiddenInterpreter, ids)
          for
            stored <- checked(catalog.candidate(f.system.context, preview.candidate.id))
            _ = assertEquals(stored.interpretation.id, preview.candidate.interpretation.id)
            _ = assertEquals(stored.assessment.id, preview.candidate.assessment.id)
            receipt <- checked(repository.accept(f.system.context, stored.id, key("reconnected")))
            _ <- PostgresDatabase.open[IO](f.config).use { again =>
              val another = again.catalog(ids)
              for
                retry <- checked(another.accept(f.system.context, stored.id, key("reconnected")))
                _ = assertEquals(retry, receipt)
                release <- checked(another.release(f.system.context, receipt.release))
                _ = assertEquals(release.manifest.id, preview.candidate.manifest.id)
                _ <- release.interpretation.artifacts.traverse_ { expected =>
                  checked(another.metadata(f.system.context, receipt.release, expected.path)).map(actual => assertEquals(actual, expected))
                }
              yield ()
            }
          yield ()
        }
        totals <- counts(f)
        _ = assertEquals(totals, (1L, 1L, 1L, 1L))
      yield ()
    }
  }

  test("the same acceptance retry on separate pools creates exactly one release and event") {
    fixture { f =>
      for
        preview <- prepared(f)
        _ <- PostgresDatabase.open[IO](f.config).use { other =>
          for
            pair <- (
              f.system.catalog.accept(f.system.context, preview.candidate.id, key("same-intent")),
              other.catalog(IdSource.random[IO]).accept(f.system.context, preview.candidate.id, key("same-intent"))
            ).parTupled
            left <- Checked.lift[IO, AcceptanceReceipt](pair._1)
            right <- Checked.lift[IO, AcceptanceReceipt](pair._2)
            _ = assertEquals(left, right)
            event <- sql"SELECT payload->>'release',payload->>'interpretation',payload->>'assessment' FROM outbox"
              .query[(String, String, String)].unique.transact(f.database.transactor)
            _ = assertEquals(event, (left.release.release.render, preview.candidate.interpretation.id.value.value, preview.candidate.assessment.id.value.value))
          yield ()
        }
        totals <- counts(f)
        _ = assertEquals(totals, (1L, 1L, 1L, 1L))
      yield ()
    }
  }

  test("failure after each publication write rolls back head, release, receipt, audit and outbox") {
    fixture { f =>
      for
        preview <- prepared(f)
        _ <- sqlRun(f, """CREATE FUNCTION reject_acceptance() RETURNS trigger AS $$
          BEGIN RAISE EXCEPTION 'injected acceptance failure'; RETURN NEW; END; $$ LANGUAGE plpgsql""")
        _ <- Vector("release" -> "INSERT", "dataset" -> "UPDATE", "draft" -> "UPDATE",
          "acceptance_receipt" -> "INSERT", "audit" -> "INSERT", "outbox" -> "INSERT").traverse_ { (table, action) =>
          Resource.make(sqlRun(f, s"CREATE TRIGGER reject_acceptance AFTER $action ON $table FOR EACH ROW EXECUTE FUNCTION reject_acceptance()"))(
            _ => sqlRun(f, s"DROP TRIGGER reject_acceptance ON $table")
          ).use { _ =>
            for
              result <- f.system.repository.accept(f.system.context, preview.candidate.id, key("atomic"))
              _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Operational]), table)
              totals <- counts(f)
              _ = assertEquals(totals, (0L, 0L, 0L, 0L), table)
              dataset <- checked(f.system.catalog.dataset(f.system.context, f.system.dataset.id))
              _ = assertEquals(dataset.head, None, table)
              draft <- checked(f.system.catalog.readDraft(f.system.context, preview.candidate.draft))
              _ = assertEquals(draft.generation, preview.candidate.generation)
            yield ()
          }
        }
        _ <- checked(f.system.repository.accept(f.system.context, preview.candidate.id, key("atomic")))
        totals <- counts(f)
        _ = assertEquals(totals, (1L, 1L, 1L, 1L))
      yield ()
    }
  }

  test("a reader cannot observe the new root or its publication records before commit") {
    fixture { f =>
      val holding = Resource.fromAutoCloseable(IO.blocking(DriverManager.getConnection(f.config.jdbcUrl, f.config.user, f.config.password)))
      def lock(connection: java.sql.Connection, function: String): IO[Unit] = IO.blocking {
        val statement = connection.createStatement()
        try
          val _ = statement.execute(s"SELECT $function(741,1)")
        finally statement.close()
      }
      def awaitBlocked(remaining: Int): IO[Unit] =
        sql"SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=741 AND objid=1 AND NOT granted)"
          .query[Boolean].unique.transact(f.database.transactor).flatMap {
            case true => IO.unit
            case false if remaining > 0 => IO.sleep(20.millis) *> awaitBlocked(remaining - 1)
            case false => IO.raiseError(new IllegalStateException("acceptance did not reach the commit barrier"))
          }
      for
        preview <- prepared(f)
        _ <- sqlRun(f, """CREATE FUNCTION pause_acceptance() RETURNS trigger AS $$
          BEGIN PERFORM pg_advisory_xact_lock(741,1); RETURN NEW; END; $$ LANGUAGE plpgsql""")
        _ <- sqlRun(f, "CREATE TRIGGER pause_acceptance AFTER INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION pause_acceptance()")
        _ <- holding.use { connection =>
          lock(connection, "pg_advisory_lock") *>
            Resource.make(f.system.repository.accept(f.system.context, preview.candidate.id, key("visibility")).start)(_.cancel).use { writer =>
              for
                _ <- awaitBlocked(200)
                totals <- counts(f)
                _ = assertEquals(totals, (0L, 0L, 0L, 0L))
                head <- sql"SELECT head::text FROM dataset".query[Option[String]].unique.transact(f.database.transactor)
                _ = assertEquals(head, None)
                _ <- lock(connection, "pg_advisory_unlock")
                result <- writer.joinWithNever
                _ <- Checked.lift[IO, AcceptanceReceipt](result)
              yield ()
            }
        }
        totals <- counts(f)
        _ = assertEquals(totals, (1L, 1L, 1L, 1L))
      yield ()
    }
  }

  test("an incomplete required index prevents acceptance") {
    fixture { f =>
      for
        preview <- prepared(f)
        _ <- sqlRun(f, "DELETE FROM metadata_field WHERE name='RepetitionTime'")
        outcome <- f.system.repository.accept(f.system.context, preview.candidate.id, key("incomplete"))
        _ = assert(outcome.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
        totals <- counts(f)
        _ = assertEquals(totals, (0L, 0L, 0L, 0L))
      yield ()
    }
  }

  test("authority is read again at acceptance and before returning an earlier receipt") {
    fixture { f =>
      for
        preview <- prepared(f)
        principal <- IdSource.random[IO].next[PrincipalTag]
        _ <- sql"UPDATE dataset SET curator=${Records.uuid(principal)}".update.run.transact(f.database.transactor)
        denied <- f.system.repository.accept(f.system.context, preview.candidate.id, key("authority"))
        _ = assertEquals(denied, Left(Problem.Denied))
        accepted <- checked(f.system.repository.accept(AccessContext(principal), preview.candidate.id, key("authority")))
        _ <- sql"UPDATE dataset SET curator=${Records.uuid(f.system.context.principal)}".update.run.transact(f.database.transactor)
        retry <- f.system.repository.accept(AccessContext(principal), preview.candidate.id, key("authority"))
        _ = assertEquals(retry, Left(Problem.Denied))
        release <- checked(f.system.repository.release(f.system.context, accepted.release))
        _ = assertEquals(release.activity.actor, f.system.context.principal)
        _ = assertEquals(release.acceptedBy, principal)
      yield ()
    }
  }

  test("missing retained bytes prevent acceptance after preparation") {
    fixture { f =>
      for
        preview <- prepared(f)
        blob = preview.candidate.manifest.files.head.blob
        _ <- IO.blocking(Files.delete(f.root.resolve("storage/objects").resolve(blob.digest.value.take(2)).resolve(blob.digest.value)))
        result <- f.system.repository.accept(f.system.context, preview.candidate.id, key("missing"))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
        totals <- counts(f)
        _ = assertEquals(totals, (0L, 0L, 0L, 0L))
      yield ()
    }
  }

  test("corrupt persisted scientific identity fails closed on read") {
    fixture { f =>
      for
        preview <- prepared(f)
        receipt <- checked(f.system.repository.accept(f.system.context, preview.candidate.id, key("integrity")))
        _ <- sqlRun(f, """UPDATE interpretation SET payload=jsonb_set(payload,'{value,profile,interpreterIdentity}','"changed"'::jsonb)""")
        result <- f.system.repository.release(f.system.context, receipt.release)
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
      yield ()
    }
  }

  test("historical SQL explanations stay bound to the original interpretation after correction") {
    fixture { f =>
      for
        preview <- prepared(f)
        initial <- checked(f.system.repository.accept(f.system.context, preview.candidate.id, key("initial")))
        original <- checked(f.system.repository.explain(f.system.context, initial.release, path(SyntheticFixture.firstImage)))
        edit <- checked(f.system.repository.correctMetadata(f.system.context, initial.release, path(SyntheticFixture.sharedSidecar),
          SyntheticFixture.correctedJson, "Persistent correction"))
        later <- checked(f.system.repository.accept(f.system.context, edit.candidate.id, key("later")))
        _ <- PostgresDatabase.open[IO](f.config).use { reopened =>
          val catalog = reopened.catalog(IdSource.random[IO])
          for
            earlier <- checked(catalog.metadata(f.system.context, initial.release, path(SyntheticFixture.firstImage)))
            after <- checked(catalog.metadata(f.system.context, later.release, path(SyntheticFixture.firstImage)))
            _ = assertEquals(earlier, original)
            _ = assertEquals(after.fields("RepetitionTime").value, MetadataValue.Number(BigDecimal("1.8")))
          yield ()
        }
      yield ()
    }
  }
