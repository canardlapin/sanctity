package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.Fragment
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.FunSuite
import sanctity.application.*
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.time.Instant
import java.util.UUID

final class OrganizationPersistenceSuite extends FunSuite:
  import Records.uuid
  import JsonCodecs.given
  import OrganizationCodecs.given
  private def checked[A](v: IO[Either[Problem,A]]): IO[A] = v.flatMap(Checked.lift[IO,A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def name(s: String): OrganizationName = OrganizationName.from(s).toOption.get
  private val description = OrganizationDescription.from("Synthetic organization persistence fixture").toOption.get
  private def sqlRun(f: PgFixture, text: String): IO[Unit] = Fragment.const(text).update.run.transact(f.database.transactor).void
  private def bootstrap(f: PgFixture, operator: PrincipalId): IO[Unit] = for
    report <- f.database.organization.preview(operator)
    _ <- f.database.organization.bootstrap(operator, PostgresOrganizationCatalog.previewDigest(report))
  yield ()
  private def fixture(run: PgFixture => IO[Unit]): Unit = PostgresFixture.resource.use(run).unsafeRunSync()
  private def mutate(f: PgFixture, ctx: AccessContext, command: OrganizationCommand,
      refs: Set[OrganizationRef], label: String): IO[OrganizationReceipt] = for
    before <- f.database.organization.snapshot
    request = OrganizationMutation(key(label), refs.map(r => r -> before.generation(r).get).toMap, command)
    receipt <- checked(f.database.organization.execute(ctx,request))
    expected = ReferenceOrganizationCatalog.decide(before, Set(f.system.context.principal), ctx, request, receipt.at).toOption.get
    after <- f.database.organization.snapshot
    _ = assertEquals(after, expected._1)
    _ = assertEquals(receipt, expected._2)
  yield receipt

  test("explicit bootstrap preserves immutable releases and exact grant scopes; operator gains no dataset read bypass") {
    fixture { f =>
      val s = f.system
      val ids = IdSource.random[IO]
      for
        operator <- ids.next[PrincipalTag]
        reader <- ids.next[PrincipalTag]
        preview <- checked(s.repository.importNative(s.context,s.dataset.id,new NativeDirectorySource[IO](f.root.resolve("source"))))
        receipt <- checked(s.repository.accept(s.context,preview.candidate.id,key("legacy")))
        release <- checked(s.catalog.release(s.context,receipt.release))
        grant <- checked(s.catalog.grant(s.context,s.dataset.id,reader,Some(receipt.release.release),Set(Action.ReadMetadata)))
        token <- checked(f.database.credentials(ids).issue(reader,"revoked",600))
        _ <- checked(f.database.credentials(ids).revoke(token.id))
        notReady <- f.database.checkOrganizationReady.attempt
        _ = assert(notReady.isLeft)
        _ <- bootstrap(f,operator)
        _ <- f.database.checkOrganizationReady
        now <- checked(s.catalog.release(s.context,receipt.release))
        _ = assertEquals((now.ref,now.parent,now.activity,now.acceptedBy,now.acceptedAt),
          (release.ref,release.parent,release.activity,release.acceptedBy,release.acceptedAt))
        _ = assertEquals(JsonCodecs.encode(now.manifest),JsonCodecs.encode(release.manifest))
        _ = assertEquals(JsonCodecs.encode(now.interpretation),JsonCodecs.encode(release.interpretation))
        _ = assertEquals(JsonCodecs.encode(now.assessment),JsonCodecs.encode(release.assessment))
        snap <- f.database.organization.snapshot
        _ = assertEquals(snap.knownDatasets(s.dataset.id).curator,s.context.principal)
        _ = assertEquals(snap.placements(s.dataset.id).policy,PlacementPolicy.ExplicitOnly)
        _ = assert(snap.steward(s.dataset.id,s.context.principal))
        denied <- s.catalog.dataset(AccessContext(operator),s.dataset.id)
        allowed <- s.catalog.check(AccessContext(reader),s.dataset.id,Action.ReadMetadata,Some(receipt.release.release))
        bytes <- s.catalog.check(AccessContext(reader),s.dataset.id,Action.ReadContent,Some(receipt.release.release))
        future <- s.catalog.check(AccessContext(reader),s.dataset.id,Action.ReadMetadata)
        revoked <- f.database.credentials(ids).authenticate(token.secret)
        _ = assert(denied.isLeft && allowed.isRight && bytes.isLeft && future.isLeft && revoked.isLeft)
        actions <- sql"SELECT actions FROM access_grant WHERE id=${uuid(grant.id)}".query[List[String]].unique.transact(f.database.transactor)
        _ = assertEquals(actions,List("ReadMetadata"))
        genesis <- sql"SELECT durability,revision,payload->'changes'->'migration'->>'operator' FROM organization_event".query[(String,Long,String)].unique.transact(f.database.transactor)
        _ = assertEquals(genesis,("LocalOnly",0L,operator.render))
      yield ()
    }
  }

  test("changed preview is refused; failed bootstrap rolls back every mapping; retry returns the original report") {
    fixture { f => for
      preview <- f.database.organization.preview(f.system.context.principal)
      person <- IdSource.random[IO].next[PrincipalTag]
      _ <- checked(f.system.catalog.grant(f.system.context,f.system.dataset.id,person,None,Set(Action.ReadMetadata)))
      stale <- f.database.organization.bootstrap(f.system.context.principal,PostgresOrganizationCatalog.previewDigest(preview)).attempt
      _ = assert(stale.isLeft)
      reviewed <- f.database.organization.preview(f.system.context.principal)
      digest = PostgresOrganizationCatalog.previewDigest(reviewed)
      _ <- sqlRun(f,"CREATE FUNCTION fail_organization() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$; CREATE TRIGGER fail_organization BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION fail_organization()")
      failed <- f.database.organization.bootstrap(f.system.context.principal,digest).attempt
      state <- sql"SELECT initialized,(SELECT count(*) FROM organization_lab),(SELECT count(*) FROM organization_placement) FROM organization_policy".query[(Boolean,Long,Long)].unique.transact(f.database.transactor)
      _ = assert(failed.isLeft)
      _ = assertEquals(state,(false,0L,0L))
      _ <- sqlRun(f,"DROP TRIGGER fail_organization ON organization_event; DROP FUNCTION fail_organization()")
      applied <- f.database.organization.bootstrap(f.system.context.principal,digest)
      retry <- f.database.organization.bootstrap(f.system.context.principal,digest)
      _ = assertEquals(applied,reviewed)
      _ = assertEquals(retry,reviewed)
      other <- f.database.organization.bootstrap(person,digest).attempt
      _ = assert(other.isLeft)
      count <- sql"SELECT count(*) FROM organization_event".query[Long].unique.transact(f.database.transactor)
      _ = assertEquals(count,1L)
    yield () }
  }

  test("SQL organization writes and durable retries round-trip exactly through reference semantics") {
    fixture { f =>
      val ctx=f.system.context
      val ids=IdSource.random[IO]
      val lab=OrganizationMigration.lab(ctx.principal)
      val project=OrganizationMigration.project(f.system.dataset)
      for
        person <- ids.next[PrincipalTag]
        _ <- checked(f.system.catalog.grant(ctx,f.system.dataset.id,person,None,Set(Action.ReadMetadata)))
        _ <- bootstrap(f,ctx.principal)
        membership <- ids.next[LabMembershipTag]
        participation <- ids.next[ProjectParticipationTag]
        _ <- mutate(f,ctx,OrganizationCommand.RenameLab(lab,name("Lab renamed"),description),Set(OrganizationRef.Lab(lab)),"rename-lab")
        _ <- mutate(f,ctx,OrganizationCommand.AddMember(membership,lab,person,LabRole.Member,Some(Instant.now.plusSeconds(600))),Set(OrganizationRef.Lab(lab)),"member")
        _ <- mutate(f,ctx,OrganizationCommand.AddParticipant(participation,project,person,OrganizationRole.Reader,ParticipationBasis.Membership(membership),None),Set(OrganizationRef.Project(project),OrganizationRef.Membership(membership)),"participant")
        impact <- checked(f.database.organization.inheritanceImpact(ctx,f.system.dataset.id))
        _ = assert(impact.recipients.contains(person))
        _ <- mutate(f,ctx,OrganizationCommand.SetInheritance(f.system.dataset.id,PlacementPolicy.ProjectMembers,impact),Set(OrganizationRef.Placement(f.system.dataset.id),OrganizationRef.Project(project)),"inheritance")
        _ <- mutate(f,ctx,OrganizationCommand.EndMember(membership),Set(OrganizationRef.Membership(membership),OrganizationRef.Lab(lab)),"end-member")
        after <- checked(f.database.organization.inheritanceImpact(ctx,f.system.dataset.id))
        _ = assert(!after.recipients.contains(person))
        metadata <- f.system.catalog.check(AccessContext(person),f.system.dataset.id,Action.ReadMetadata)
        _ = assert(metadata.isRight) // Independent grant survives ended membership.
        _ <- mutate(f,ctx,OrganizationCommand.EndParticipant(participation),Set(OrganizationRef.Participation(participation),OrganizationRef.Project(project)),"end-participant")
        newLab <- ids.next[LabTag]
        admin <- ids.next[LabMembershipTag]
        _ <- mutate(f,ctx,OrganizationCommand.CreateLab(newLab,ctx.principal,admin,name("Second lab"),description),Set.empty,"create-lab")
        newProject <- ids.next[ProjectTag]
        steward <- ids.next[ProjectParticipationTag]
        _ <- mutate(f,ctx,OrganizationCommand.CreateProject(newProject,newLab,ctx.principal,steward,name("Second project"),description),Set(OrganizationRef.Lab(newLab)),"create-project")
        _ <- mutate(f,ctx,OrganizationCommand.ArchiveProject(newProject,true),Set(OrganizationRef.Project(newProject),OrganizationRef.Lab(newLab)),"archive-project")
        _ <- mutate(f,ctx,OrganizationCommand.ArchiveLab(newLab,true),Set(OrganizationRef.Lab(newLab)),"archive-lab")
      yield ()
    }
  }

  test("concurrent updates have one winner; exact retries preserve receipt and different intent conflicts") {
    fixture { f =>
      val ctx=f.system.context
      val project=OrganizationMigration.project(f.system.dataset)
      val refs: Map[OrganizationRef,OrganizationGeneration]=Map(OrganizationRef.Project(project) -> OrganizationGeneration.initial)
      val a=OrganizationMutation(key("concurrent-a"),refs,OrganizationCommand.RenameProject(project,name("A"),description))
      val b=OrganizationMutation(key("concurrent-b"),refs,OrganizationCommand.RenameProject(project,name("B"),description))
      for
        _ <- bootstrap(f,ctx.principal)
        result <- (f.database.organization.execute(ctx,a),f.database.organization.execute(ctx,b)).parTupled
        _ = assertEquals(Vector(result._1,result._2).count(_.isRight),1)
        winner = if result._1.isRight then a else b
        expected = if result._1.isRight then result._1 else result._2
        retried <- f.database.organization.execute(ctx,winner)
        _ = assertEquals(retried,expected)
        conflict <- f.database.organization.execute(ctx,winner.copy(command=OrganizationCommand.RenameProject(project,name("different"),description)))
        _ = assert(conflict.isLeft)
        snap <- f.database.organization.snapshot
        _ = assertEquals(snap.sequence,1L)
      yield ()
    }
  }

  test("removing an explicit steward removes the historical curator's authority without rewriting attribution") {
    fixture { f =>
      val ctx=f.system.context
      val dataset=f.system.dataset.id
      for
        successor <- IdSource.random[IO].next[PrincipalTag]
        appointment <- IdSource.random[IO].next[DatasetStewardshipTag]
        _ <- checked(f.system.catalog.grant(ctx,dataset,successor,None,Set(Action.ReadMetadata)))
        _ <- bootstrap(f,ctx.principal)
        _ <- mutate(f,ctx,OrganizationCommand.AddSteward(appointment,dataset,successor),Set(OrganizationRef.Placement(dataset)),"successor")
        old=OrganizationMigration.stewardship(dataset)
        _ <- mutate(f,ctx,OrganizationCommand.EndSteward(old),Set(OrganizationRef.Stewardship(old),OrganizationRef.Placement(dataset)),"end-old")
        denied <- f.system.catalog.dataset(ctx,dataset)
        current <- checked(f.system.catalog.dataset(AccessContext(successor),dataset))
        _ = assert(denied.isLeft)
        _ = assertEquals(current.curator,ctx.principal)
        state <- f.database.organization.snapshot
        request=OrganizationMutation(key("last"),Map(OrganizationRef.Stewardship(appointment)->state.generation(OrganizationRef.Stewardship(appointment)).get,OrganizationRef.Placement(dataset)->state.placements(dataset).generation),OrganizationCommand.EndSteward(appointment))
        last <- f.database.organization.execute(AccessContext(successor),request)
        _ = assert(last.isLeft)
      yield ()
    }
  }

  test("new dataset plus stewardship is atomic, generation checked, and idempotent across response loss") {
    fixture { f =>
      val ctx=f.system.context
      val project=OrganizationMigration.project(f.system.dataset)
      for
        _ <- bootstrap(f,ctx.principal)
        created <- checked(f.database.organization.createDataset(ctx,f.system.dataset.custodian,project,key("atomic"),OrganizationGeneration.initial))
        retried <- checked(f.database.organization.createDataset(ctx,f.system.dataset.custodian,project,key("atomic"),OrganizationGeneration.initial))
        _ = assertEquals(created,retried)
        stale <- f.database.organization.createDataset(ctx,f.system.dataset.custodian,project,key("stale"),OrganizationGeneration.initial)
        _ = assert(stale.isLeft)
        legacy <- f.system.catalog.create(ctx,f.system.dataset.custodian).attempt
        _ = assert(legacy.isLeft)
        count <- sql"SELECT count(*) FROM dataset".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(count,2L)
        state <- f.database.organization.snapshot
        _ = assert(state.steward(created._1.id,ctx.principal))
        _ = assertEquals(state.placements(created._1.id).policy,PlacementPolicy.ExplicitOnly)
      yield ()
    }
  }

  test("archive serializes with real import leases; archived data stays readable and new work is refused") {
    fixture { f =>
      val s=f.system
      val ids=IdSource.random[IO]
      val journal=f.database.journal(ids)
      val importer=new DurableImports[IO](s.catalog,journal,s.store,s.repository,ids)
      val project=OrganizationMigration.project(s.dataset)
      val lab=OrganizationMigration.lab(s.context.principal)
      for
        _ <- bootstrap(f,s.context.principal)
        op <- checked(importer.begin(s.context,s.dataset.id,key("lease"),new NativeDirectorySource[IO](f.root.resolve("source"))))
        lease <- checked(journal.claim(s.context,op.id,60))
        request=OrganizationMutation(key("archive"),Map(OrganizationRef.Project(project)->OrganizationGeneration.initial,OrganizationRef.Lab(lab)->OrganizationGeneration.initial),OrganizationCommand.ArchiveProject(project,true))
        busy <- f.database.organization.execute(s.context,request)
        _ = assert(busy.isLeft)
        _ <- journal.surrender(s.context,lease)
        _ <- checked(f.database.organization.execute(s.context,request))
        denied <- journal.claim(s.context,op.id,60)
        readable <- s.catalog.dataset(s.context,s.dataset.id)
        _ = assert(denied.isLeft && readable.isRight)
      yield ()
    }
  }

  test("disablement atomically invalidates credentials and active leases and denies cached principal contexts") {
    fixture { f =>
      val s=f.system
      val ids=IdSource.random[IO]
      val journal=f.database.journal(ids)
      val importer=new DurableImports[IO](s.catalog,journal,s.store,s.repository,ids)
      for
        operator <- ids.next[PrincipalTag]
        token <- checked(f.database.credentials(ids).issue(s.context.principal,"disable",600))
        _ <- bootstrap(f,operator)
        op <- checked(importer.begin(s.context,s.dataset.id,key("disable-lease"),new NativeDirectorySource[IO](f.root.resolve("source"))))
        _ <- checked(journal.claim(s.context,op.id,60))
        request=OrganizationMutation(key("disable"),Map(OrganizationRef.Principal(s.context.principal)->OrganizationGeneration.initial),OrganizationCommand.DisablePrincipal(s.context.principal))
        _ <- checked(f.database.organization.execute(AccessContext(operator),request))
        denied <- s.catalog.dataset(s.context,s.dataset.id)
        login <- f.database.credentials(ids).authenticate(token.secret)
        lease <- sql"SELECT lease_token IS NULL FROM import_operation".query[Boolean].unique.transact(f.database.transactor)
        _ = assert(denied.isLeft && login.isLeft && lease)
        attributed <- sql"SELECT curator FROM dataset".query[UUID].unique.transact(f.database.transactor)
        _ = assertEquals(attributed,uuid(s.context.principal))
      yield ()
    }
  }

  test("legacy policy snapshots cannot reopen an organization-enabled restore") {
    fixture { f => for
      old <- f.database.recovery.capturePolicy
      _ <- bootstrap(f,f.system.context.principal)
      capture <- f.database.recovery.capturePolicy.attempt
      _ = assert(capture.isLeft)
      _ <- f.database.recovery.requireReconciliation
      reconcile <- f.database.recovery.reconcile(old,old.digest,f.system.store)
      _ = assert(reconcile.isLeft)
      closed <- sql"SELECT recovery_required FROM repository_state".query[Boolean].unique.transact(f.database.transactor)
      _ = assert(closed)
    yield () }
  }

  test("a failing event insert rolls back record changes, generation and retry receipt together") {
    fixture { f =>
      val ctx=f.system.context
      val project=OrganizationMigration.project(f.system.dataset)
      val request=OrganizationMutation(key("rollback"),Map(OrganizationRef.Project(project)->OrganizationGeneration.initial),OrganizationCommand.RenameProject(project,name("Uncommitted"),description))
      for
        _ <- bootstrap(f,ctx.principal)
        before <- f.database.organization.snapshot
        _ <- sqlRun(f,"CREATE FUNCTION reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$; CREATE TRIGGER reject_event BEFORE INSERT ON organization_event FOR EACH ROW EXECUTE FUNCTION reject_event()")
        failed <- f.database.organization.execute(ctx,request)
        after <- f.database.organization.snapshot
        _ = assert(failed.isLeft)
        _ = assertEquals(after,before)
        _ <- sqlRun(f,"DROP TRIGGER reject_event ON organization_event; DROP FUNCTION reject_event()")
        _ <- checked(f.database.organization.execute(ctx,request))
      yield ()
    }
  }

  test("navigation links round-trip and SQL rejects invalid references and versions") {
    fixture { f =>
      val ctx=f.system.context
      for
        _ <- bootstrap(f,ctx.principal)
        linkId <- IdSource.random[IO].next[ProjectDatasetLinkTag]
        link=ProjectDatasetLink(linkId,OrganizationMigration.project(f.system.dataset),f.system.dataset.id,OrganizationGeneration.initial,Instant.now)
        payload=JsonCodecs.encode(link)
        _ <- sql"INSERT INTO organization_link(payload) VALUES($payload::jsonb)".update.run.transact(f.database.transactor)
        stored <- f.database.organization.snapshot
        _ = assertEquals(stored.links(linkId),link)
        wrong=JsonCodecs.encode(link.copy(project=Id[ProjectTag](UUID.randomUUID()),id=Id[ProjectDatasetLinkTag](UUID.randomUUID())))
        invalid <- sql"INSERT INTO organization_link(payload) VALUES($wrong::jsonb)".update.run.transact(f.database.transactor).attempt
        badVersion <- sqlRun(f,"UPDATE organization_lab SET payload=jsonb_set(payload,'{version}','2')").attempt
        _ = assert(invalid.isLeft && badVersion.isLeft)
        _ = assert(JsonCodecs.decode[ProjectDatasetLink](payload.replace("\"version\":1","\"version\":2")).isLeft)
      yield ()
    }
  }
