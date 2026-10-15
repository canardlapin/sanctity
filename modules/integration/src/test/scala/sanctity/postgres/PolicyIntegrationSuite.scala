package sanctity.postgres

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment}
import doobie.implicits.*
import io.circe.Json
import munit.FunSuite
import sanctity.application.*
import sanctity.kernel.*
import sanctity.policy.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final class PolicyIntegrationSuite extends FunSuite:
  import PolicyWire.{field, text, str, obj}
  override val munitTimeout: Duration = 90.seconds
  private var material: Path = scala.compiletime.uninitialized
  override def beforeAll(): Unit =
    material = Files.createTempDirectory("sanctity-policy-tls-")
    PolicyTestTls.generate(material)
  override def afterAll(): Unit = sanctity.app.TestFiles.remove(material).unsafeRunSync()
  private val ids = IdSource.random[IO]
  private def checked[A](f: IO[Either[Problem,A]]): IO[A] = f.flatMap(Checked.lift[IO,A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def name(s: String): OrganizationName = OrganizationName.from(s).toOption.get
  private val description = OrganizationDescription.from("Synthetic policy integration fixture").toOption.get
  private final case class Env(f: PgFixture, db: PostgresDatabase[IO], witness: CatalogWitnessFixture, trust: PolicyConnection):
    val ctx = f.system.context
    val dataset = f.system.dataset.id
    def query[A](q: ConnectionIO[A]): IO[A] = q.transact(f.database.transactor)
    def sql(s: String): IO[Unit] = query(Fragment.const(s).update.run).void
    def enroll: IO[Json] = db.policy.enroll(trust,witness.recovery)
    def mutate(command: OrganizationCommand, refs: Set[OrganizationRef], label: String): IO[OrganizationReceipt] = for
      before <- db.organization.snapshot
      receipt <- checked(db.organization.execute(ctx,OrganizationMutation(key(label),refs.map(r => r -> before.generation(r).get).toMap,command)))
      _ = assertEquals(receipt.durability,PolicyDurability.Durable)
    yield receipt
  private def fixture(enroll: Boolean = true)(run: Env => IO[Unit]): Unit = (for
    f <- PostgresFixture.resource
    _ <- Resource.eval(for
      report <- f.database.organization.preview(f.system.context.principal)
      _ <- f.database.organization.bootstrap(f.system.context.principal,PostgresOrganizationCatalog.previewDigest(report))
    yield ())
    installation <- Resource.eval(sql"SELECT installation::text FROM organization_policy".query[String].unique.transact(f.database.transactor))
    witness <- Resource.make(IO.blocking(new CatalogWitnessFixture(material,f.root.toRealPath().resolve("witness"),installation)))(w => IO.blocking(w.close()))
    trust = new PolicyConnection(installation,witness.writerFingerprint,witness.writer)
    db <- PostgresDatabase.open[IO](f.config,Some(trust))
    e = Env(f,db,witness,trust)
    _ <- Resource.eval(if enroll then e.enroll.void else IO.unit)
  yield e).use(run).unsafeRunSync()

  test("fenced enrollment captures complete current state and preserves original history") {
    fixture(false) { e => for
      person <- ids.next[PrincipalTag]
      grant <- checked(e.f.system.catalog.grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      _ <- checked(e.f.system.catalog.revoke(e.ctx,e.dataset,grant.id))
      credential <- checked(e.f.database.credentials(ids).issue(person,"revoked",600))
      _ <- checked(e.f.database.credentials(ids).revoke(credential.id))
      before <- e.query(sql"SELECT payload::text FROM organization_event ORDER BY revision".query[String].to[Vector])
      _ <- e.enroll
      after <- e.query(sql"SELECT payload::text FROM organization_event ORDER BY revision".query[String].to[Vector])
      _ = assertEquals(after,before)
      checkpoint <- e.query(sql"SELECT command->>'checkpoint' FROM policy_enrollment".query[String].unique)
      source = new String(PolicyWire.unbase64(checkpoint),java.nio.charset.StandardCharsets.UTF_8)
      _ = assert(source.contains(grant.id.render) && source.contains(credential.id.render))
      _ = assert(!source.contains(credential.secret) && !source.contains("token_digest") && !source.contains("lease_token"))
      ready <- e.db.policy.readiness
      _ = assertEquals(text(ready,"mode"),"independent")
      again <- e.enroll.attempt
      _ = assert(again.isLeft)
    yield () }
  }

  test("all nineteen supported mutation classes receive exact independent COMMIT acknowledgements") {
    fixture() { e =>
      val lab = OrganizationMigration.lab(e.ctx.principal)
      val project = OrganizationMigration.project(e.f.system.dataset)
      for
        person <- ids.next[PrincipalTag]
        grant <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
        credentialId <- ids.next[CredentialTag]
        credential <- checked(e.db.credentials(ids).issueOnce(credentialId,person,"one",600))
        _ <- checked(e.db.credentials(ids).revoke(credential.id))
        _ <- checked(e.db.catalog(ids).revoke(e.ctx,e.dataset,grant.id))
        membership <- ids.next[LabMembershipTag]
        participation <- ids.next[ProjectParticipationTag]
        _ <- e.mutate(OrganizationCommand.RenameLab(lab,name("Renamed lab"),description),Set(OrganizationRef.Lab(lab)),"rename-lab")
        _ <- e.mutate(OrganizationCommand.RenameProject(project,name("Renamed project"),description),Set(OrganizationRef.Project(project)),"rename-project")
        _ <- e.mutate(OrganizationCommand.AddMember(membership,lab,person,LabRole.Member,None),Set(OrganizationRef.Lab(lab)),"member")
        _ <- e.mutate(OrganizationCommand.AddParticipant(participation,project,person,OrganizationRole.Reader,ParticipationBasis.Membership(membership),None),Set(OrganizationRef.Project(project),OrganizationRef.Membership(membership)),"participant")
        impact <- checked(e.db.organization.inheritanceImpact(e.ctx,e.dataset))
        _ <- e.mutate(OrganizationCommand.SetInheritance(e.dataset,PlacementPolicy.ProjectMembers,impact),Set(OrganizationRef.Placement(e.dataset),OrganizationRef.Project(project)),"inheritance")
        _ <- e.mutate(OrganizationCommand.EndMember(membership),Set(OrganizationRef.Membership(membership),OrganizationRef.Lab(lab)),"end-member")
        _ <- e.mutate(OrganizationCommand.EndParticipant(participation),Set(OrganizationRef.Participation(participation),OrganizationRef.Project(project)),"end-participant")
        appointment <- ids.next[DatasetStewardshipTag]
        _ <- e.mutate(OrganizationCommand.AddSteward(appointment,e.dataset,person),Set(OrganizationRef.Placement(e.dataset)),"add-steward")
        _ <- e.mutate(OrganizationCommand.EndSteward(appointment),Set(OrganizationRef.Stewardship(appointment),OrganizationRef.Placement(e.dataset)),"end-steward")
        state <- e.db.organization.snapshot
        _ <- checked(e.db.organization.createDataset(e.ctx,e.f.system.dataset.custodian,project,key("create-data"),state.projects(project).generation))
        newLab <- ids.next[LabTag]
        admin <- ids.next[LabMembershipTag]
        _ <- e.mutate(OrganizationCommand.CreateLab(newLab,e.ctx.principal,admin,name("Second lab"),description),Set.empty,"create-lab")
        newProject <- ids.next[ProjectTag]
        steward <- ids.next[ProjectParticipationTag]
        _ <- e.mutate(OrganizationCommand.CreateProject(newProject,newLab,e.ctx.principal,steward,name("Second project"),description),Set(OrganizationRef.Lab(newLab)),"create-project")
        _ <- e.mutate(OrganizationCommand.ArchiveProject(newProject,true),Set(OrganizationRef.Project(newProject),OrganizationRef.Lab(newLab)),"archive-project")
        _ <- e.mutate(OrganizationCommand.ArchiveLab(newLab,true),Set(OrganizationRef.Lab(newLab)),"archive-lab")
        _ <- e.mutate(OrganizationCommand.DisablePrincipal(person),Set(OrganizationRef.Principal(person)),"disable")
        kinds <- e.query(sql"SELECT payload->>'kind' FROM organization_event WHERE durability='Pending'".query[String].to[Vector])
        acknowledgements <- e.query(sql"SELECT count(*) FROM policy_acknowledgement".query[Long].unique)
        _ = assertEquals(kinds.toSet,PolicyCapabilities.kinds)
        _ = assertEquals(acknowledgements,kinds.size.toLong)
        state <- IO.blocking(e.witness.current)
        _ = assert(field(state,"pending").isNull)
      yield ()
    }
  }

  test("failed event insertion rolls back effects and proves live ABORT without a committed event") {
    fixture() { e => for
      _ <- e.sql("CREATE FUNCTION reject_policy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$; CREATE TRIGGER reject_policy BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION reject_policy()")
      person <- ids.next[PrincipalTag]
      result <- e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata))
      _ = assert(result.isLeft)
      count <- e.query(sql"SELECT count(*) FROM access_grant".query[Long].unique)
      _ = assertEquals(count,0L)
      state <- IO.blocking(e.witness.current)
      _ = assert(field(state,"pending").isNull)
      _ = assertEquals(text(state,"revision"),"0")
      _ = assertEquals(text(state,"sequence"),"3") // ENROLL, PREPARE, ABORT
    yield () }
  }

  test("lost PREPARE response leaves no local effects and cannot abort from restarted absence") {
    fixture() { e => for
      _ <- IO(e.witness.failHead(2))
      person <- ids.next[PrincipalTag]
      result <- e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata))
      _ = assert(result.left.exists(_.isInstanceOf[Problem.PolicyOutcomeUnknown]))
      count <- e.query(sql"SELECT count(*) FROM access_grant".query[Long].unique)
      _ = assertEquals(count,0L)
      _ <- IO.blocking(e.witness.restart())
      state <- IO.blocking(e.witness.current)
      _ = assert(!field(state,"pending").isNull)
      resolved <- e.db.policy.resolve.attempt
      _ = assert(resolved.isLeft)
      again <- IO.blocking(e.witness.current)
      _ = assertEquals(again,state)
    yield () }
  }

  test("lost COMMIT acknowledgement preserves local restriction and resolves without repeating effects") {
    fixture() { e => for
      person <- ids.next[PrincipalTag]
      grant <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      _ <- IO(e.witness.failHead(5)) // enrollment + grant prepare/commit + revoke prepare/commit
      result <- e.db.catalog(ids).revoke(e.ctx,e.dataset,grant.id)
      _ = assert(result.left.exists(_.isInstanceOf[Problem.PolicyPending]))
      revoked <- e.query(sql"SELECT revoked_at IS NOT NULL FROM access_grant".query[Boolean].unique)
      _ = assert(revoked)
      _ <- IO.blocking(e.witness.restart())
      denied <- e.db.catalog(ids).check(AccessContext(person),e.dataset,Action.ReadMetadata)
      _ = assertEquals(denied,Left(Problem.Denied))
      _ <- e.db.policy.resolve
      _ <- checked(e.db.catalog(ids).revoke(e.ctx,e.dataset,grant.id))
      count <- e.query(sql"SELECT count(*) FROM organization_event WHERE payload->>'kind'='GrantRevoked'".query[Long].unique)
      _ = assertEquals(count,1L)
    yield () }
  }

  test("credential issuance with lost acknowledgement never duplicates a retained operation ID") {
    fixture() { e => for
      id <- ids.next[CredentialTag]
      _ <- IO(e.witness.failHead(3))
      result <- e.db.credentials(ids).issueOnce(id,e.ctx.principal,"one",600)
      _ = assert(result.left.exists(_.isInstanceOf[Problem.PolicyPending]))
      _ <- IO.blocking(e.witness.restart())
      retry <- e.db.credentials(ids).issueOnce(id,e.ctx.principal,"one",600)
      _ = assert(retry.left.exists(_.isInstanceOf[Problem.Conflict]))
      count <- e.query(sql"SELECT count(*) FROM api_credential".query[Long].unique)
      _ = assertEquals(count,1L)
      legacy <- e.db.credentials(ids).issue(e.ctx.principal,"unsafe-retry",600)
      _ = assert(legacy.left.exists(_.isInstanceOf[Problem.Unsupported]))
    yield () }
  }

  test("barrier fences the running service even if local epoch and heads are unchanged") {
    fixture() { e => for
      _ <- e.db.checkOrganizationReady
      state <- IO.blocking(e.witness.current)
      _ <- IO.blocking(e.witness.command("RECOVERY_BARRIER",obj("operation" -> str(UUID.randomUUID().toString),
        "installation" -> str(e.trust.installation),"epoch" -> str((text(state,"epoch").toLong+1).toString),
        "reason" -> str("synthetic fencing test"),"operator" -> str(e.witness.recoveryFingerprint),
        "at" -> str(Instant.now.toString),"challenge" -> str(UUID.randomUUID().toString))))
      denied <- e.db.catalog(ids).dataset(e.ctx,e.dataset)
      _ = assert(denied.isLeft)
      epoch <- e.query(sql"SELECT writer_epoch FROM organization_policy".query[Long].unique)
      _ = assertEquals(epoch,0L)
      startup <- e.db.checkOrganizationReady.attempt
      _ = assert(startup.isLeft)
    yield () }
  }

  test("absolute five-second admission expires an idle transaction and rolls it back") {
    fixture() { e => for
      started <- IO.monotonic
      result <- (OrganizationSql.guard(false) *> doobie.free.connection.raw(_ => Thread.sleep(5500))).transact(e.db.transactor).attempt
      elapsed <- IO.monotonic.map(_-started)
      _ = assert(result.isLeft)
      _ = assert(elapsed < 10.seconds)
      _ <- e.db.checkOrganizationReady
    yield () }
  }

  test("expired admission drains a running server query after earlier work consumed its budget") {
    fixture() { e =>
      val backend = new java.util.concurrent.atomic.AtomicInteger()
      val work = OrganizationSql.guard(false) *> doobie.free.connection.raw { c =>
        backend.set(PolicyBridge.rows(c,"SELECT pg_backend_pid()")(_.getInt(1)).head)
        val statement = c.createStatement()
        try
          statement.execute("SELECT pg_sleep(2)")
          statement.execute("SELECT pg_sleep(20)")
          ()
        finally statement.close()
      }
      for
        result <- work.transact(e.db.transactor).attempt
        _ = assert(result.isLeft)
        _ = assert(backend.get() > 0)
        // Client abort can win the small race with the server deadline. It must
        // not leave the second query alive for its own fresh five-second timeout.
        _ <- IO.sleep(100.millis)
        active <- e.query(sql"SELECT count(*) FROM pg_stat_activity WHERE pid=${backend.get()} AND xact_start IS NOT NULL".query[Long].unique)
        _ = assertEquals(active,0L)
        _ <- e.db.checkOrganizationReady
      yield ()
    }
  }

  test("unsupported lifecycle and eligibility are refused at enrollment and live admission") {
    fixture(false) { e => for
      _ <- e.sql("UPDATE dataset SET authority_lifecycle='Quarantined'")
      failed <- e.enroll.attempt
      _ = assert(failed.isLeft)
      mode <- e.query(sql"SELECT durability_mode FROM organization_policy".query[String].unique)
      _ = assertEquals(mode,"local-engineering")
      _ <- e.sql("UPDATE dataset SET authority_lifecycle='Available'; UPDATE principal SET authority_eligibility='{}'::jsonb")
      eligibility <- e.enroll.attempt
      _ = assert(eligibility.isLeft)
      _ <- e.sql("""UPDATE principal SET authority_eligibility='{"version":1,"value":{"Local":{}}}'::jsonb""")
      _ <- e.enroll
      _ <- e.sql("UPDATE dataset SET authority_lifecycle='Withdrawn'")
      denied <- e.db.checkOrganizationReady.attempt
      _ = assert(denied.isLeft)
    yield () }
  }

  test("missing independent trust and a restored older local head both fail admission") {
    fixture() { e => for
      missing <- e.f.database.checkOrganizationReady.attempt
      _ = assert(missing.isLeft)
      person <- ids.next[PrincipalTag]
      _ <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      _ <- e.sql("UPDATE organization_policy SET revision=0,applied_hash=(SELECT applied_hash FROM organization_event WHERE revision=0)")
      stale <- e.db.checkOrganizationReady.attempt
      _ = assert(stale.isLeft)
    yield () }
  }

  test("enrollment retires the old runtime login and a fresh least-privilege login can acknowledge grant changes") {
    fixture(false) { e =>
      val oldRole = "policy_old_"+UUID.randomUUID().toString.replace("-","")
      val newRole = "policy_new_"+UUID.randomUUID().toString.replace("-","")
      val password = UUID.randomUUID().toString
      val resource = Resource.make(for
        database <- e.query(sql"SELECT current_database()".query[String].unique)
        _ <- e.sql(s"""REVOKE CREATE,TEMPORARY ON DATABASE "$database" FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC""")
        _ <- Vector(oldRole,newRole).traverse_(r => e.sql(s"""CREATE ROLE "$r" LOGIN NOINHERIT PASSWORD '$password'"""))
      yield ())(_ => Vector(oldRole,newRole).traverse_(r => e.sql(s"""DROP OWNED BY "$r"; DROP ROLE "$r"""")))
      resource.use { _ => for
        _ <- e.f.database.grantRuntime(oldRole)
        _ <- e.enroll
        oldAllowed <- e.query(sql"SELECT has_table_privilege($oldRole,'organization_policy','SELECT')".query[Boolean].unique)
        _ = assert(!oldAllowed)
        regrant <- e.f.database.grantRuntime(oldRole).attempt
        _ = assert(regrant.isLeft)
        _ <- e.f.database.grantRuntime(newRole)
        config = DatabaseConfig.from(e.f.config.jdbcUrl,newRole,password,e.f.config.schema).toOption.get
        _ <- PostgresDatabase.open[IO](config,Some(e.trust)).use { runtime => for
          _ <- runtime.checkRuntimeRole
          _ <- runtime.checkOrganizationReady
          person <- ids.next[PrincipalTag]
          grant <- checked(runtime.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
          _ <- checked(runtime.catalog(ids).revoke(e.ctx,e.dataset,grant.id))
          forbidden <- Fragment.const("UPDATE policy_acknowledgement SET certificate='{}'::jsonb").update.run.transact(runtime.transactor).attempt
          _ = assert(forbidden.isLeft)
        yield () }
      yield () }
    }
  }

  test("lost enrollment response stays closed and resumes with the exact retained checkpoint") {
    fixture(false) { e => for
      _ <- IO(e.witness.failHead(1))
      first <- e.enroll.attempt
      _ = assert(first.isLeft)
      closed <- e.query(sql"SELECT recovery_required FROM repository_state".query[Boolean].unique)
      retained <- e.query(sql"SELECT command::text FROM policy_enrollment".query[String].unique)
      _ = assert(closed)
      _ <- IO.blocking(e.witness.restart())
      _ <- e.enroll
      after <- e.query(sql"SELECT command::text FROM policy_enrollment".query[String].unique)
      _ = assertEquals(after,retained)
      _ <- e.db.checkOrganizationReady
    yield () }
  }

  test("unsupported historical producer prevents enrollment without a partial fence") {
    fixture(false) { e => for
      _ <- e.sql("UPDATE organization_event SET payload=jsonb_set(payload,'{version}','\"future-policy\"'::jsonb)")
      result <- e.enroll.attempt
      _ = assert(result.isLeft)
      staged <- e.query(sql"SELECT count(*) FROM policy_enrollment".query[Long].unique)
      _ = assertEquals(staged,0L)
    yield () }
  }

  test("stored acknowledgement signatures are verified before trusting a durable receipt") {
    fixture() { e => for
      person <- ids.next[PrincipalTag]
      _ <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      _ <- e.sql("UPDATE policy_acknowledgement SET certificate='{}'::jsonb")
      ready <- e.db.checkOrganizationReady.attempt
      _ = assert(ready.isLeft)
    yield () }
  }

  test("locally committed pending restriction governs reads and blocks overtaking until witness resolution") {
    fixture() { e => for
      person <- ids.next[PrincipalTag]
      grant <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      _ <- e.sql("CREATE FUNCTION wait_policy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(1); RETURN NEW; END $$; CREATE TRIGGER wait_policy BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION wait_policy()")
      fiber <- e.db.catalog(ids).revoke(e.ctx,e.dataset,grant.id).start
      _ <- {
        def waitForInsert(attempts: Int): IO[Unit] =
          e.query(sql"SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event='PgSleep' AND query LIKE '%organization_event%')".query[Boolean].unique)
            .flatMap(waiting => if waiting then IO.unit else if attempts==0 then IO.raiseError(new Exception("did not reach local event boundary"))
              else IO.sleep(20.millis) *> waitForInsert(attempts-1))
        waitForInsert(100)
      }
      _ <- IO.blocking(e.witness.stop())
      result <- fiber.joinWithNever
      _ = assert(result.left.exists(_.isInstanceOf[Problem.PolicyPending]))
      revoked <- e.query(sql"SELECT revoked_at IS NOT NULL FROM access_grant".query[Boolean].unique)
      _ = assert(revoked)
      blocked <- e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadContent))
      _ = assert(blocked.isLeft)
      _ <- e.sql("DROP TRIGGER wait_policy ON organization_event; DROP FUNCTION wait_policy()")
      _ <- IO.blocking(e.witness.restart())
      state <- IO.blocking(e.witness.current)
      _ = assert(!field(state,"pending").isNull)
      denied <- e.db.catalog(ids).check(AccessContext(person),e.dataset,Action.ReadMetadata)
      _ = assertEquals(denied,Left(Problem.Denied))
      _ <- e.db.policy.resolve
      resolved <- IO.blocking(e.witness.current)
      _ = assert(field(resolved,"pending").isNull)
    yield () }
  }

  test("oversized complete checkpoint is refused without truncation or partial enrollment") {
    fixture(false) { e => for
      _ <- e.sql("UPDATE organization_policy SET migration_report=jsonb_build_object('padding',repeat('x',4194304))")
      result <- e.enroll.attempt
      _ = assert(result.isLeft)
      rows <- e.query(sql"SELECT count(*) FROM policy_enrollment".query[Long].unique)
      _ = assertEquals(rows,0L)
      state <- IO.blocking(e.witness.current)
      _ = assertEquals(text(state,"sequence"),"0")
    yield () }
  }

  test("independent offboarding invalidation is atomic with its event and denies cached credentials") {
    fixture() { e => for
      person <- ids.next[PrincipalTag]
      _ <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      credentialId <- ids.next[CredentialTag]
      credential <- checked(e.db.credentials(ids).issueOnce(credentialId,person,"active",600))
      cached <- checked(e.db.credentials(ids).authenticate(credential.secret))
      _ <- e.sql("CREATE FUNCTION reject_disable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.payload->>'kind'='DisablePrincipal' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$; CREATE TRIGGER reject_disable BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION reject_disable()")
      state <- e.db.organization.snapshot
      ref = OrganizationRef.Principal(person)
      request = OrganizationMutation(key("offboard"),Map(ref -> state.generation(ref).get),OrganizationCommand.DisablePrincipal(person))
      failed <- e.db.organization.execute(e.ctx,request)
      _ = assert(failed.isLeft)
      active <- e.db.credentials(ids).authenticate(credential.secret)
      _ = assert(active.isRight)
      _ <- e.sql("DROP TRIGGER reject_disable ON organization_event; DROP FUNCTION reject_disable()")
      receipt <- checked(e.db.organization.execute(e.ctx,request))
      _ = assertEquals(receipt.durability,PolicyDurability.Durable)
      denied <- e.db.catalog(ids).check(cached,e.dataset,Action.ReadMetadata)
      _ = assertEquals(denied,Left(Problem.Denied))
      invalidated <- e.query(sql"SELECT revoked_at IS NOT NULL FROM api_credential".query[Boolean].unique)
      _ = assert(invalidated)
    yield () }
  }

  test("lost local COMMIT response reports unknown and resolves the exact committed event under a new live guard") {
    fixture() { e =>
      val once = new java.util.concurrent.atomic.AtomicBoolean(true)
      val xa = e.db.transactor
      val failing = xa.copy(connect0 = kernel => xa.connect(kernel).map { connection =>
        java.lang.reflect.Proxy.newProxyInstance(classOf[java.sql.Connection].getClassLoader,
          Array(classOf[java.sql.Connection]),new java.lang.reflect.InvocationHandler {
            def invoke(proxy: Object, method: java.lang.reflect.Method, arguments: Array[Object]): Object =
              val args = Option(arguments).getOrElse(Array.empty[Object])
              try
                val result = method.invoke(connection,args*)
                if method.getName == "commit" && once.compareAndSet(true,false) then
                  throw new java.sql.SQLException("synthetic lost local commit response","08006")
                result
              catch case e: java.lang.reflect.InvocationTargetException => throw e.getCause
          }).asInstanceOf[java.sql.Connection]
      })
      val database = new PostgresDatabase[IO](failing,e.f.config.schema)
      for
        person <- ids.next[PrincipalTag]
        result <- database.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata))
        _ = assert(result.left.exists(_.isInstanceOf[Problem.PolicyOutcomeUnknown]))
        state <- IO.blocking(e.witness.current)
        _ = assert(!field(state,"pending").isNull)
        count <- e.query(sql"SELECT count(*) FROM access_grant".query[Long].unique)
        _ = assertEquals(count,1L)
        _ <- e.db.policy.resolve
        resolved <- IO.blocking(e.witness.current)
        _ = assert(field(resolved,"pending").isNull)
        receipts <- e.query(sql"SELECT count(*) FROM policy_acknowledgement".query[Long].unique)
        _ = assertEquals(receipts,1L)
      yield ()
    }
  }

  test("historical organization-local-v1 framing enrolls without rewriting its payload or identity") {
    fixture(false) { e => for
      original <- e.query(sql"SELECT id::text,payload::text FROM organization_event WHERE revision=0".query[(String,String)].unique)
      source = io.circe.jawn.parse(original._2).toOption.get
      legacy = Json.obj("version" -> str("organization-local-v1"),"actor" -> field(source,"actor"),
        "request" -> Json.Null,"createdDatasets" -> field(field(source,"extra"),"createdDatasets"),
        "at" -> field(source,"at"),"changes" -> field(source,"changes"))
        .printWith(io.circe.Printer.noSpaces.copy(sortKeys=true))
      digest = Digest.bytes(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)).value
      applied = Digest.framed("sanctity.organization-local.v1",Vector(PolicyWire.zero,original._1,"0",digest)).value
      _ <- e.query(sql"UPDATE organization_event SET payload=$legacy::jsonb,payload_digest=$digest,applied_hash=$applied WHERE revision=0".update.run)
      _ <- e.query(sql"UPDATE organization_policy SET applied_hash=$applied WHERE singleton".update.run)
      before <- e.query(sql"SELECT id::text,payload::text,payload_digest,applied_hash FROM organization_event".query[(String,String,String,String)].unique)
      _ <- e.enroll
      after <- e.query(sql"SELECT id::text,payload::text,payload_digest,applied_hash FROM organization_event".query[(String,String,String,String)].unique)
      _ = assertEquals(after,before)
      person <- ids.next[PrincipalTag]
      _ <- checked(e.db.catalog(ids).grant(e.ctx,e.dataset,person,None,Set(Action.ReadMetadata)))
      previous <- e.query(sql"SELECT previous_hash FROM organization_event WHERE revision=1".query[String].unique)
      _ = assertEquals(previous,applied)
    yield () }
  }

  test("HTTP distinguishes unknown and pending outcomes and never attaches a durable success header to either") {
    Vector(4 -> "policy_outcome_unknown:",5 -> "policy_pending:").foreach { (head,expected) =>
      fixture() { e => for
        id <- ids.next[CredentialTag]
        token <- checked(e.db.credentials(ids).issueOnce(id,e.ctx.principal,"http",600))
        _ = assertEquals(token.durability,PolicyDurability.Durable)
        _ <- IO(e.witness.failHead(head))
        person <- ids.next[PrincipalTag]
        catalog = e.db.catalog(ids)
        repository = new NativeRepository[IO](catalog,e.f.system.store,new sanctity.bids.BidsInterpreter[IO](e.f.system.store),ids)
        api = new sanctity.http.RepositoryHttp[IO](catalog,repository,e.db.credentials(ids),independentPolicy=true).app
        request = org.http4s.Request[IO](org.http4s.Method.POST,org.http4s.Uri.unsafeFromString(s"/v1/datasets/${e.dataset.render}/grants"))
          .withEntity(Json.obj("recipient" -> str(person.render),"release" -> Json.Null,"actions" -> Json.arr(str("ReadMetadata"))).noSpaces)
          .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Authorization"),"Bearer "+token.secret),
            org.http4s.Header.Raw(org.typelevel.ci.CIString("Content-Type"),"application/json"))
        response <- api.run(request)
        body <- response.bodyText.compile.string
        _ = assertEquals(response.status,org.http4s.Status.ServiceUnavailable)
        _ = assert(body.contains(expected) && !body.contains(token.secret))
        _ = assert(!response.headers.headers.exists(_.name==org.typelevel.ci.CIString("X-Sanctity-Policy-Durability")))
      yield () }
    }
  }

  test("historical LocalOnly events cannot acquire an acknowledgement by inserting a separate receipt") {
    fixture(false) { e => for
      forged <- e.query(sql"INSERT INTO policy_acknowledgement(event_id,certificate) SELECT id,'{}'::jsonb FROM organization_event WHERE revision=0".update.run).attempt
      _ = assert(forged.left.toOption.exists { case x: java.sql.SQLException => x.getSQLState=="23503"; case _ => false })
      count <- e.query(sql"SELECT count(*) FROM policy_acknowledgement".query[Long].unique)
      _ = assertEquals(count,0L)
    yield () }
  }
