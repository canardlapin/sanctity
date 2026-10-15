package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment}
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Encoder
import munit.FunSuite
import sanctity.app.OrganizationAuthorityFixtures as Corpus
import sanctity.application.*
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.*
import sanctity.objects.NativeDirectorySource
import java.sql.Timestamp
import java.time.{Duration, Instant}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

final class CurrentAuthoritySuite extends FunSuite:
  import Records.uuid
  import OrganizationCodecs.given
  override val munitTimeout = 5.minutes
  private def checked[A](v: IO[Either[Problem,A]]): IO[A] = v.flatMap(Checked.lift[IO,A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def fixture(run: PgFixture => IO[Unit]): Unit = PostgresFixture.resource.use(run).unsafeRunSync()
  private def sqlRun(f: PgFixture, text: String): IO[Unit] = Fragment.const(text).update.run.transact(f.database.transactor).void
  private def bootstrap(f: PgFixture): IO[Unit] = for
    report <- f.database.organization.preview(f.system.context.principal)
    _ <- f.database.organization.bootstrap(f.system.context.principal,PostgresOrganizationCatalog.previewDigest(report))
  yield ()
  private def mutate(f: PgFixture, command: OrganizationCommand, refs: Set[OrganizationRef], label: String): IO[OrganizationReceipt] = for
    before <- f.database.organization.snapshot
    receipt <- checked(f.database.organization.execute(f.system.context,OrganizationMutation(key(label),refs.map(r => r -> before.generation(r).get).toMap,command)))
  yield receipt

  /** Test-only operator fixture: real relational releases reference cloned, FK-valid
    * draft/candidate rows. No test bypass is compiled into production authorization.
    * Shift the frozen corpus clock to the database clock, preserving expiry relations.
    */
  private def seed(f: PgFixture, x: Corpus.Scenario): IO[State] = for
    preview <- checked(f.system.repository.importNative(f.system.context,f.system.dataset.id,new NativeDirectorySource[IO](f.root.resolve("source"))))
    now <- OrganizationSql.now.transact(f.database.transactor)
    delta = Duration.between(x.now,now)
    at = (t: Instant) => t.plus(delta)
    s = x.state.copy(
      principals=x.state.principals.view.mapValues(p => p.copy(createdAt=at(p.createdAt))).toMap,
      eligibility=x.state.eligibility.view.mapValues {
        case Eligibility.Local => Eligibility.Local
        case Eligibility.Guest(g) => Eligibility.Guest(g.copy(expiresAt=at(g.expiresAt)))
      }.toMap,
      memberships=x.state.memberships.view.mapValues(m => m.copy(createdAt=at(m.createdAt),expiresAt=m.expiresAt.map(at))).toMap,
      participations=x.state.participations.view.mapValues(r => r.copy(createdAt=at(r.createdAt),expiresAt=r.expiresAt.map(at))).toMap,
      stewardships=x.state.stewardships.view.mapValues(r => r.copy(createdAt=at(r.createdAt))).toMap,
      grants=x.state.grants.view.mapValues(g => g.copy(createdAt=at(g.createdAt),expiresAt=g.expiresAt.map(at))).toMap)
    _ <- (for
      _ <- s.principals.values.toVector.traverse_ { p =>
        val eligibility = JsonCodecs.encode(s.eligibility(p.id))
        sql"""INSERT INTO principal(id,label,active,generation,created_at,authority_eligibility)
          VALUES(${uuid(p.id)},${p.label.value},${p.active},${p.generation.value},${Timestamp.from(p.createdAt)},$eligibility::jsonb)""".update.run
      }
      _ <- s.datasets.values.toVector.traverse_ { d => for
        _ <- sql"INSERT INTO dataset(id,custodian,curator,authority_lifecycle) VALUES(${uuid(d.id)},${uuid(f.system.dataset.custodian)},${uuid(f.system.context.principal)},${d.lifecycle.toString})".update.run
        _ <- d.releases.toVector.traverse_ { r =>
          val draft = Corpus.id[DraftTag]("draft/"+r.render)
          val candidate = Corpus.id[CandidateTag]("candidate/"+r.render)
          for
            _ <- sql"""INSERT INTO draft(id,dataset_id,generation,manifest_id,activity)
              SELECT ${uuid(draft)},${uuid(d.id)},generation,manifest_id,activity FROM draft WHERE id=${uuid(preview.candidate.draft)}""".update.run
            _ <- sql"""INSERT INTO candidate(id,draft_id,dataset_id,generation,manifest_id,interpretation_id,assessment_id,activity)
              SELECT ${uuid(candidate)},${uuid(draft)},${uuid(d.id)},generation,manifest_id,interpretation_id,assessment_id,activity
              FROM candidate WHERE id=${uuid(preview.candidate.id)}""".update.run
            _ <- sql"""INSERT INTO release(id,dataset_id,draft_id,candidate_id,accepted_by,accepted_at,authority_lifecycle)
              VALUES(${uuid(r)},${uuid(d.id)},${uuid(draft)},${uuid(candidate)},${uuid(f.system.context.principal)},${Timestamp.from(now)},
              ${d.releaseLifecycle.getOrElse(r,Lifecycle.Available).toString})""".update.run
          yield ()
        }
        _ <- sql"UPDATE dataset SET head=${d.head.map(uuid)} WHERE id=${uuid(d.id)}".update.run
      yield () }
      _ <- insertRows("lab",s.labs.values.toVector)
      _ <- insertRows("project",s.projects.values.toVector)
      _ <- insertRows("membership",s.memberships.values.toVector)
      _ <- insertRows("participation",s.participations.values.toVector)
      _ <- insertRows("placement",s.placements.values.toVector)
      _ <- insertRows("stewardship",s.stewardships.values.toVector)
      _ <- insertRows("link",s.links.values.toVector)
      _ <- s.grants.values.toVector.traverse_ { g =>
        val basis=JsonCodecs.encode(g.basis)
        val kind=if g.basis == GrantBasis.Independent then "Independent" else "Derived"
        sql"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions,issuer,created_at,expires_at,basis,basis_payload,generation,revoked_at)
          VALUES(${uuid(g.id)},${uuid(g.scope.dataset)},${uuid(g.recipient)},${g.scope.release.map(uuid)},${g.actions.toList.map(_.toString)},
          ${g.issuer.map(uuid)},${Timestamp.from(g.createdAt)},${g.expiresAt.map(Timestamp.from)},$kind,$basis::jsonb,${g.generation.value},
          ${Option.when(g.revoked)(Timestamp.from(now))})""".update.run
      }
      _ <- sql"""UPDATE organization_policy SET initialized=true,bootstrap_operator=${uuid(f.system.context.principal)},
        migration_digest=repeat('0',64),migration_report='{}'::jsonb WHERE singleton""".update.run
    yield ()).transact(f.database.transactor)
  yield s
  private def insertRows[A: Encoder](table: String, values: Vector[A]): ConnectionIO[Unit] = values.traverse_ { v =>
    val payload=JsonCodecs.encode(v)
    (Fragment.const(s"INSERT INTO organization_$table(payload) VALUES(") ++ fr"$payload::jsonb" ++ fr")").update.run
  }

  Corpus.cases.foreach { j =>
    val x=Corpus.scenario(j)
    test("SQL/reference ADR002 " + j.hcursor.get[String]("id").toOption.get) {
      fixture { f => for
        expectedState <- seed(f,x)
        result <- (for
          _ <- OperationsSql.active
          _ <- OrganizationSql.guard(false)
          loaded <- PolicySql.load
          now <- OrganizationSql.now
          result <- PolicySql.permits(AccessContext(x.actor.principal),x.request).attempt.map {
            case Right(v) => v
            case Left(Failure(Problem.Denied)) => false
            case Left(error) => throw error
          }
        yield (loaded,now,result)).transact(f.database.transactor)
        (loaded,now,resultValue)=result
        _ = assertEquals(loaded.grants,expectedState.grants)
        _ = assertEquals(loaded.memberships,expectedState.memberships)
        _ = assertEquals(loaded.participations,expectedState.participations)
        _ = assertEquals(resultValue,x.expected.hcursor.get[Boolean]("allow").toOption.get)
        _ = assertEquals(resultValue,OrganizationAuthority.permits(expectedState,x.actor,x.request,now))
        _ = x.request match
          case Request.ListDatasets(p) => assertEquals(visibleDatasets(loaded,x.actor,p,now),visibleDatasets(expectedState,x.actor,p,now))
          case Request.ListSharedReleases => assertEquals(sharedScopes(loaded,x.actor,now),sharedScopes(expectedState,x.actor,now))
          case _ => ()
      yield () }
    }
  }

  test("grant intent, current expiry, exact retries, revocation and append failure are transactional") {
    fixture { f =>
      val catalog=f.database.catalog(IdSource.random[IO])
      for
        _ <- bootstrap(f)
        id <- IdSource.random[IO].next[GrantTag]
        reader <- IdSource.random[IO].next[PrincipalTag]
        at <- OrganizationSql.now.transact(f.database.transactor)
        grant=PolicyGrant(id,Some(f.system.context.principal),reader,DataScope(f.system.dataset.id),Set(Permission.ReadMetadata),at,Some(at.plusSeconds(600)),GrantBasis.Independent,OrganizationGeneration.initial)
        issued <- checked(catalog.grantPolicy(f.system.context,grant))
        retry <- checked(catalog.grantPolicy(f.system.context,grant))
        _ = assertEquals(issued,retry)
        changed <- catalog.grantPolicy(f.system.context,grant.copy(actions=Set(Permission.ReadContent)))
        _ = assert(changed.isLeft)
        allowed <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadMetadata)
        _ = assert(allowed.isRight)
        _ <- sqlRun(f,"CREATE FUNCTION fail_policy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$; CREATE TRIGGER fail_policy BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION fail_policy()")
        failed <- catalog.revoke(f.system.context,f.system.dataset.id,id)
        stillAllowed <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadMetadata)
        _ = assert(failed.isLeft && stillAllowed.isRight)
        _ <- sqlRun(f,"DROP TRIGGER fail_policy ON organization_event; DROP FUNCTION fail_policy()")
        _ <- checked(catalog.revoke(f.system.context,f.system.dataset.id,id))
        _ <- checked(catalog.revoke(f.system.context,f.system.dataset.id,id))
        denied <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadMetadata)
        replay <- catalog.grantPolicy(f.system.context,grant)
        events <- sql"SELECT payload->>'kind',durability FROM organization_event ORDER BY revision".query[(String,String)].to[Vector].transact(f.database.transactor)
        _ = assert(denied.isLeft && replay.isLeft)
        _ = assertEquals(events,Vector("OrganizationGenesis","GrantIssued","GrantRevoked").map(_ -> "LocalOnly"))
        expired <- catalog.grantPolicy(f.system.context,grant.copy(id=Corpus.id("expired"),createdAt=at.minusSeconds(20),expiresAt=Some(at.minusSeconds(10))))
        _ = assert(expired.isLeft)
      yield ()
    }
  }

  test("offboarding fences cached credentials, publication, grants and protected receipt retries") {
    fixture { f =>
      val ids=IdSource.random[IO]
      for
        _ <- bootstrap(f)
        token <- checked(f.database.credentials(ids).issue(f.system.context.principal,"stale-context",600))
        ctx <- checked(f.database.credentials(ids).authenticate(token.secret))
        journal=f.database.journal(ids)
        op <- checked(journal.begin(ctx,f.system.dataset.id,key("pending-import"),"synthetic",Vector.empty))
        lease <- checked(journal.claim(ctx,op.id,60))
        preview <- checked(f.system.repository.importNative(ctx,f.system.dataset.id,new NativeDirectorySource[IO](f.root.resolve("source"))))
        receipt <- checked(f.system.repository.accept(ctx,preview.candidate.id,key("accepted")))
        replay <- checked(f.system.repository.accept(ctx,preview.candidate.id,key("accepted")))
        _ = assertEquals(receipt,replay)
        _ <- mutate(f,OrganizationCommand.DisablePrincipal(ctx.principal),Set(OrganizationRef.Principal(ctx.principal)),"offboard")
        accepts <- f.system.repository.accept(ctx,preview.candidate.id,key("accepted"))
        receipts <- f.system.catalog.receipt(ctx,preview.candidate.id,key("accepted"))
        grants <- f.system.catalog.grant(ctx,f.system.dataset.id,Corpus.principal("new"),None,Set(Action.ReadMetadata))
        tokenAgain <- f.database.credentials(ids).authenticate(token.secret)
        resumed <- journal.renew(ctx,lease,60)
        published <- f.system.repository.accept(ctx,preview.candidate.id,key("after-offboard"))
        _ = assert(accepts.isLeft && receipts.isLeft && grants.isLeft && tokenAgain.isLeft && resumed.isLeft && published.isLeft)
        invalidation <- sql"SELECT payload->'extra'->'invalidations'->'credentials' FROM organization_event WHERE payload->>'kind'='DisablePrincipal'".query[String].unique.transact(f.database.transactor)
        _ = assert(invalidation.contains(token.id.render))
        noSecret <- sql"SELECT bool_and(payload::text NOT LIKE ${"%"+token.secret+"%"}) FROM organization_event".query[Boolean].unique.transact(f.database.transactor)
        _ = assert(noSecret)
      yield ()
    }
  }

  test("an independent reader waits for revocation commit and cannot use the prior grant") {
    fixture { f =>
      val held=new CountDownLatch(1)
      val release=new CountDownLatch(1)
      for
        _ <- bootstrap(f)
        reader <- IdSource.random[IO].next[PrincipalTag]
        grant <- checked(f.system.catalog.grant(f.system.context,f.system.dataset.id,reader,None,Set(Action.ReadMetadata)))
        _ <- sqlRun(f,"CREATE TABLE authority_test_gate(id integer PRIMARY KEY); INSERT INTO authority_test_gate VALUES(1); CREATE FUNCTION gate_revoke() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.payload->>'kind'='GrantRevoked' THEN PERFORM id FROM authority_test_gate FOR SHARE; END IF; RETURN NEW; END $$; CREATE TRIGGER gate_revoke BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION gate_revoke()")
        blocker <- (sql"SELECT id FROM authority_test_gate FOR UPDATE".query[Int].unique *>
          doobie.free.connection.delay { held.countDown(); assert(release.await(20,TimeUnit.SECONDS)) }).transact(f.database.transactor).start
        _ <- IO.blocking(assert(held.await(10,TimeUnit.SECONDS)))
        revoke <- f.system.catalog.revoke(f.system.context,f.system.dataset.id,grant.id).start
        _ <- waitForLocks(f,1)
        read <- f.system.catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadMetadata).start
        _ <- waitForLocks(f,2).guarantee(IO(release.countDown()))
        _ <- blocker.joinWithNever
        revoked <- revoke.joinWithNever
        denied <- read.joinWithNever
        _ = assert(revoked.isRight && denied.isLeft)
      yield ()
    }
  }

  test("ending original membership removes inherited and derived rights; rejoining cannot revive the old grant") {
    fixture { f =>
      val ids=IdSource.random[IO]
      val catalog=f.database.catalog(ids)
      val ctx=f.system.context
      val lab=OrganizationMigration.lab(ctx.principal)
      val project=OrganizationMigration.project(f.system.dataset)
      for
        reader <- ids.next[PrincipalTag]
        _ <- checked(catalog.grant(ctx,f.system.dataset.id,reader,None,Set(Action.ReadMetadata)))
        _ <- bootstrap(f)
        member <- ids.next[LabMembershipTag]
        role <- ids.next[ProjectParticipationTag]
        _ <- mutate(f,OrganizationCommand.AddMember(member,lab,reader,LabRole.Member,None),Set(OrganizationRef.Lab(lab)),"join")
        _ <- mutate(f,OrganizationCommand.AddParticipant(role,project,reader,OrganizationRole.Reader,ParticipationBasis.Membership(member),None),Set(OrganizationRef.Project(project),OrganizationRef.Membership(member)),"participate")
        impact <- checked(f.database.organization.inheritanceImpact(ctx,f.system.dataset.id))
        _ <- mutate(f,OrganizationCommand.SetInheritance(f.system.dataset.id,PlacementPolicy.ProjectMembers,impact),Set(OrganizationRef.Placement(f.system.dataset.id),OrganizationRef.Project(project)),"inherit")
        inherited <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadContent)
        _ = assert(inherited.isRight)
        id <- ids.next[GrantTag]
        at <- OrganizationSql.now.transact(f.database.transactor)
        g=PolicyGrant(id,Some(ctx.principal),reader,DataScope(f.system.dataset.id),Set(Permission.ReadContent),at,None,GrantBasis.Derived(Set(Appointment.Participation(role))),OrganizationGeneration.initial)
        _ <- checked(catalog.grantPolicy(ctx,g))
        _ <- mutate(f,OrganizationCommand.SetInheritance(f.system.dataset.id,PlacementPolicy.ExplicitOnly,impact),Set(OrganizationRef.Placement(f.system.dataset.id),OrganizationRef.Project(project)),"explicit")
        derived <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadContent)
        _ = assert(derived.isRight)
        _ <- mutate(f,OrganizationCommand.EndMember(member),Set(OrganizationRef.Membership(member),OrganizationRef.Lab(lab)),"depart")
        denied <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadContent)
        independent <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadMetadata)
        _ = assert(denied.isLeft && independent.isRight)
        replacement <- ids.next[LabMembershipTag]
        _ <- mutate(f,OrganizationCommand.AddMember(replacement,lab,reader,LabRole.Member,None),Set(OrganizationRef.Lab(lab)),"rejoin")
        after <- catalog.check(AccessContext(reader),f.system.dataset.id,Action.ReadContent)
        _ = assert(after.isLeft)
        events <- sql"SELECT id,revision,previous_hash,payload_digest,applied_hash,payload::text FROM organization_event ORDER BY revision"
          .query[(java.util.UUID,Long,String,String,String,String)].to[Vector].transact(f.database.transactor)
        _ = events.foldLeft("0" * 64) { case (prior,(event,revision,previous,digest,applied,payload)) =>
          val json=io.circe.jawn.parse(payload).toOption.get.printWith(io.circe.Printer.noSpaces.copy(sortKeys=true))
          assertEquals(previous,prior)
          assertEquals(digest,Digest.bytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).value)
          assertEquals(applied,Digest.framed("sanctity.policy-local.v2",Vector(previous,event.toString,revision.toString,digest)).value)
          applied
        }
      yield ()
    }
  }

  test("event append failure rolls back credential issuance, revocation and offboarding together") {
    fixture { f =>
      val ids=IdSource.random[IO]
      for
        _ <- bootstrap(f)
        token <- checked(f.database.credentials(ids).issue(f.system.context.principal,"original",600))
        _ <- sqlRun(f,"CREATE FUNCTION fail_policy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$; CREATE TRIGGER fail_policy BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION fail_policy()")
        issuance <- f.database.credentials(ids).issue(f.system.context.principal,"must-rollback",600)
        revocation <- f.database.credentials(ids).revoke(token.id)
        offboard <- f.database.organization.execute(f.system.context,OrganizationMutation(key("must-rollback"),Map(OrganizationRef.Principal(f.system.context.principal)->OrganizationGeneration.initial),OrganizationCommand.DisablePrincipal(f.system.context.principal)))
        admitted <- f.database.credentials(ids).authenticate(token.secret)
        state <- sql"SELECT (SELECT count(*) FROM api_credential),(SELECT count(*) FROM organization_event),(SELECT active FROM principal WHERE id=${uuid(f.system.context.principal)})"
          .query[(Long,Long,Boolean)].unique.transact(f.database.transactor)
        _ = assert(issuance.isLeft && revocation.isLeft && offboard.isLeft && admitted.isRight)
        _ = assertEquals(state,(1L,2L,true))
      yield ()
    }
  }

  test("initial acceptance and retries cannot disclose a receipt without current metadata authority") {
    fixture { f => for
      _ <- bootstrap(f)
      preview <- checked(f.system.repository.importNative(f.system.context,f.system.dataset.id,new NativeDirectorySource[IO](f.root.resolve("source"))))
      actor <- IdSource.random[IO].next[PrincipalTag]
      ctx=AccessContext(actor)
      _ <- checked(f.system.catalog.grant(f.system.context,f.system.dataset.id,actor,None,Set(Action.Accept)))
      denied <- f.system.catalog.accept(ctx,preview.candidate.id,key("protected"))
      count <- sql"SELECT count(*) FROM release".query[Long].unique.transact(f.database.transactor)
      _ = assertEquals(denied,Left(Problem.Denied))
      _ = assertEquals(count,0L)
      metadata <- checked(f.system.catalog.grant(f.system.context,f.system.dataset.id,actor,None,Set(Action.ReadMetadata)))
      receipt <- checked(f.system.catalog.accept(ctx,preview.candidate.id,key("protected")))
      retry <- checked(f.system.catalog.accept(ctx,preview.candidate.id,key("protected")))
      _ = assertEquals(receipt,retry)
      _ <- checked(f.system.catalog.revoke(f.system.context,f.system.dataset.id,metadata.id))
      retryDenied <- f.system.catalog.accept(ctx,preview.candidate.id,key("protected"))
      readDenied <- f.system.catalog.receipt(ctx,preview.candidate.id,key("protected"))
      _ = assertEquals(retryDenied,Left(Problem.Denied))
      _ = assertEquals(readDenied,Left(Problem.Denied))
    yield () }
  }

  private def waitForLocks(f: PgFixture, count: Int): IO[Unit] =
    def poll(left: Int): IO[Unit] = sql"""SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
      AND wait_event_type='Lock' AND query LIKE '%organization_event%' OR
      (datname=current_database() AND wait_event_type='Lock' AND query LIKE '%organization_policy%')"""
      .query[Long].unique.transact(f.database.transactor).flatMap { n =>
        if n >= count then IO.unit else if left == 0 then IO.raiseError(new AssertionError("expected independent lock waiters"))
        else IO.sleep(50.millis) *> poll(left-1)
      }
    poll(100)
