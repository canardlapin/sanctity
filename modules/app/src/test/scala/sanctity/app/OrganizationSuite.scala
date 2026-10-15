package sanctity.app

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import sanctity.application.{Checked, ReferenceOrganizationCatalog}
import sanctity.kernel.*
import java.time.Instant
import java.util.UUID

final class OrganizationSuite extends FunSuite:
  import OrganizationCommand.*
  private def id[A](n: Long): Id[A] = Id[A](new UUID(0, n))
  private val operator = id[PrincipalTag](1)
  private val alice = id[PrincipalTag](2)
  private val bob = id[PrincipalTag](3)
  private val guest = id[PrincipalTag](4)
  private val labA = id[LabTag](10)
  private val labB = id[LabTag](11)
  private val projectA = id[ProjectTag](20)
  private val projectB = id[ProjectTag](21)
  private val adminA = id[LabMembershipTag](30)
  private val adminB = id[LabMembershipTag](31)
  private val participantA = id[ProjectParticipationTag](40)
  private val participantB = id[ProjectParticipationTag](41)
  private val stewardA = id[DatasetStewardshipTag](50)
  private val stewardB = id[DatasetStewardshipTag](51)
  private val datasetA = id[DatasetTag](100)
  private val datasetB = id[DatasetTag](101)
  private def name(s: String): OrganizationName = OrganizationName.from(s).toOption.get
  private val label = name("A shared display name")
  private val description = OrganizationDescription.from("").toOption.get
  private val principals = Vector(operator, alice, bob, guest).map(p =>
    OrganizationPrincipal(p, label, true, OrganizationGeneration.initial, Instant.EPOCH))
  private val datasets = Vector(Dataset(datasetA, id[CustodianTag](900), alice, None),
    Dataset(datasetB, id[CustodianTag](900), bob, None))
  private def key: IdempotencyKey = IdempotencyKey.from(UUID.randomUUID().toString).toOption.get
  private def ok[A](effect: IO[Either[Problem, A]]): IO[A] = effect.flatMap(Checked.lift[IO, A])
  private def mutation(c: ReferenceOrganizationCatalog[IO], command: OrganizationCommand,
      refs: OrganizationRef*): IO[OrganizationMutation] = c.snapshot.map(s =>
    OrganizationMutation(key, refs.map(r => r -> s.generation(r).get).toMap, command))
  private def run(c: ReferenceOrganizationCatalog[IO], actor: PrincipalId, command: OrganizationCommand,
      refs: OrganizationRef*): IO[OrganizationReceipt] = mutation(c, command, refs*).flatMap(r => ok(c.execute(AccessContext(actor), r)))
  private def fixture(test: ReferenceOrganizationCatalog[IO] => IO[Unit]): Unit =
    (for
      c <- ReferenceOrganizationCatalog.create[IO](principals, datasets, Set(operator))
      _ <- run(c, operator, CreateLab(labA, alice, adminA, label, description))
      _ <- run(c, operator, CreateLab(labB, bob, adminB, label, description))
      _ <- run(c, alice, CreateProject(projectA, labA, alice, participantA, label, description), OrganizationRef.Lab(labA))
      _ <- run(c, bob, CreateProject(projectB, labB, bob, participantB, label, description), OrganizationRef.Lab(labB))
      _ <- run(c, alice, PlaceNewDataset(datasetA, projectA, stewardA), OrganizationRef.Project(projectA))
      _ <- run(c, bob, PlaceNewDataset(datasetB, projectB, stewardB), OrganizationRef.Project(projectB))
      _ <- test(c)
    yield ()).unsafeRunSync()

  test("display values count Unicode code points, reject controls/blank text and bound generations") {
    assert(OrganizationName.from("😀" * 200).isRight)
    assert(OrganizationName.from("😀" * 201).isLeft)
    Vector("", "   ", "\u2003", "name\n", "name\u0000", "\ud800").foreach(v => assert(OrganizationName.from(v).isLeft))
    assert(OrganizationDescription.from("x" * 4000).isRight)
    assert(OrganizationDescription.from("x" * 4001).isLeft)
    assert(OrganizationGeneration.from(-1).isLeft)
    assert(OrganizationGeneration.from(Long.MaxValue).toOption.get.next.isLeft)
  }

  test("bootstrap rejects duplicated principals and orphaned scientific attribution") {
    val result = for
      duplicates <- ReferenceOrganizationCatalog.create[IO](principals :+ principals.head, datasets, Set(operator)).attempt
      orphan <- ReferenceOrganizationCatalog.create[IO](principals, Vector(datasets.head.copy(curator = id[PrincipalTag](999))), Set(operator)).attempt
      _ = assert(duplicates.isLeft && orphan.isLeft)
    yield ()
    result.unsafeRunSync()
  }

  test("names may coincide while identities, placement, custody and attribution stay independent") {
    fixture { c => for
      before <- c.snapshot
      _ = assertEquals(before.labs(labA).name, before.labs(labB).name)
      _ = assertEquals(before.placements(datasetA).policy, PlacementPolicy.ExplicitOnly)
      _ <- run(c, alice, RenameProject(projectA, name("Renamed"), description), OrganizationRef.Project(projectA))
      after <- c.snapshot
      _ = assertEquals(after.knownDatasets, before.knownDatasets)
      _ = assertEquals(after.placements, before.placements)
      _ = assertEquals(after.projects(projectA).id, projectA)
      _ = assert(after.projects(projectA).generation.value > before.projects(projectA).generation.value)
      _ = assertEquals(after.audit.last.receipt.actor, alice)
    yield () }
  }

  test("operators, lab administrators and project stewards have separate administrative scopes") {
    fixture { c => for
      denied <- c.execute(AccessContext(operator), OrganizationMutation(key, Map.empty, RenameProject(projectA, name("Forbidden"), description)))
      _ = assertEquals(denied, Left(Problem.Denied))
      unknown <- c.execute(AccessContext(bob), OrganizationMutation(key, Map.empty, RenameLab(id[LabTag](999), label, description)))
      foreign <- c.execute(AccessContext(bob), OrganizationMutation(key, Map.empty, RenameLab(labA, label, description)))
      _ = assertEquals(unknown, foreign)
      newProject = id[ProjectTag](22)
      _ <- run(c, alice, CreateProject(newProject, labA, bob, id[ProjectParticipationTag](42), label, description), OrganizationRef.Lab(labA))
      s <- c.snapshot
      _ = assertEquals(s.projectRole(newProject, alice, Instant.now()), None)
      _ = assertEquals(s.projectRole(newProject, bob, Instant.now()), Some(OrganizationRole.Steward))
      roleOnly <- c.execute(AccessContext(bob), OrganizationMutation(key, Map.empty, AddSteward(id[DatasetStewardshipTag](52), datasetA, bob)))
      _ = assertEquals(roleOnly, Left(Problem.Denied))
    yield () }
  }

  test("orphaned references, reused identities and duplicate placement leave no audit or partial records") {
    fixture { c => for
      before <- c.snapshot
      bad <- mutation(c, AddMember(id[LabMembershipTag](32), labA, id[PrincipalTag](999), LabRole.Member, None), OrganizationRef.Lab(labA))
      result <- c.execute(AccessContext(alice), bad)
      duplicate <- mutation(c, PlaceNewDataset(datasetA, projectA, id[DatasetStewardshipTag](52)), OrganizationRef.Project(projectA))
      result2 <- c.execute(AccessContext(alice), duplicate)
      reused <- c.execute(AccessContext(operator), OrganizationMutation(key, Map.empty, CreateLab(labA, bob, adminB, label, description)))
      after <- c.snapshot
      _ = assert(result.isLeft && result2.isLeft && reused.isLeft)
      _ = assertEquals(after, before)
    yield () }
  }

  test("idempotent retries reauthorize today and conflicting payloads cannot overwrite intent") {
    fixture { c => for
      request <- mutation(c, RenameProject(projectA, name("After"), description), OrganizationRef.Project(projectA))
      first <- ok(c.execute(AccessContext(alice), request))
      retry <- ok(c.execute(AccessContext(alice), request))
      _ = assertEquals(retry, first)
      different <- c.execute(AccessContext(alice), request.copy(command = RenameProject(projectA, name("Different"), description)))
      _ = assert(different.isLeft)
      _ <- run(c, operator, DisablePrincipal(alice), OrganizationRef.Principal(alice))
      revoked <- c.execute(AccessContext(alice), request)
      _ = assertEquals(revoked, Left(Problem.Denied))
    yield () }
  }

  test("concurrent writers using one generation cannot both commit") {
    fixture { c => for
      first <- mutation(c, RenameProject(projectA, name("One"), description), OrganizationRef.Project(projectA))
      second = first.copy(key = key, command = RenameProject(projectA, name("Two"), description))
      results <- Vector(first, second).parTraverse(r => c.execute(AccessContext(alice), r))
      _ = assertEquals(results.count(_.isRight), 1)
      _ = assert(results.exists(_.left.toOption.exists { case _: Problem.Conflict => true; case _ => false }))
    yield () }
  }

  test("ending a membership ends only its derived participation; rejoining never resurrects the old basis") {
    fixture { c =>
      val member = id[LabMembershipTag](32); val derived = id[ProjectParticipationTag](42)
      val independent = id[ProjectParticipationTag](43)
      for
        _ <- run(c, alice, AddMember(member, labA, guest, LabRole.Member, None), OrganizationRef.Lab(labA))
        _ <- run(c, alice, AddParticipant(derived, projectA, guest, OrganizationRole.Reader, ParticipationBasis.Membership(member), None),
          OrganizationRef.Project(projectA), OrganizationRef.Membership(member))
        _ <- run(c, bob, AddParticipant(independent, projectB, guest, OrganizationRole.Reader, ParticipationBasis.Independent, None), OrganizationRef.Project(projectB))
        _ <- run(c, alice, EndMember(member), OrganizationRef.Membership(member), OrganizationRef.Lab(labA))
        ended <- c.snapshot
        _ = assertEquals(ended.projectRole(projectA, guest, Instant.now()), None)
        _ = assertEquals(ended.projectRole(projectB, guest, Instant.now()), Some(OrganizationRole.Reader))
        _ <- run(c, alice, AddMember(id[LabMembershipTag](33), labA, guest, LabRole.Member, None), OrganizationRef.Lab(labA))
        joined <- c.snapshot
        _ = assertEquals(joined.projectRole(projectA, guest, Instant.now()), None)
        _ = assertEquals(joined.participations(derived).basis, ParticipationBasis.Membership(member))
        reuse <- mutation(c, AddMember(member, labA, bob, LabRole.Member, None), OrganizationRef.Lab(labA))
        invalid <- c.execute(AccessContext(alice), reuse)
        _ = assert(invalid.isLeft)
      yield ()
    }
  }

  test("principal disablement overrides all organization appointments without rewriting scientific attribution") {
    fixture { c => for
      before <- c.snapshot
      _ <- run(c, operator, DisablePrincipal(alice), OrganizationRef.Principal(alice))
      after <- c.snapshot
      _ = assertEquals(after.knownDatasets, before.knownDatasets)
      _ = assertEquals(after.knownDatasets(datasetA).curator, alice)
      _ = assertEquals(after.recoveryNeeds(Instant.now()), ReferenceOrganizationCatalog.RecoveryNeeds(Set(labA), Set(projectA), Set(datasetA)))
      _ = assertEquals(after.projectRole(projectA, alice, Instant.now()), None)
      _ = assert(!after.steward(datasetA, alice))
      _ = assert(after.steward(datasetB, bob))
    yield () }
  }

  test("ordinary removal preserves the last lab admin, project steward and explicit dataset steward") {
    fixture { c => for
      before <- c.snapshot
      requests <- Vector(
        mutation(c, EndMember(adminA), OrganizationRef.Membership(adminA), OrganizationRef.Lab(labA)),
        mutation(c, EndParticipant(participantA), OrganizationRef.Participation(participantA), OrganizationRef.Project(projectA)),
        mutation(c, EndSteward(stewardA), OrganizationRef.Stewardship(stewardA), OrganizationRef.Placement(datasetA))).sequence
      results <- requests.traverse(r => c.execute(AccessContext(alice), r))
      after <- c.snapshot
      _ = assert(results.forall(_.isLeft))
      _ = assertEquals(after, before)
    yield () }
  }

  test("concurrent last-steward removals preserve an active successor and separate dataset responsibility") {
    fixture { c =>
      val second = id[ProjectParticipationTag](42)
      for
        _ <- run(c, alice, AddParticipant(second, projectA, bob, OrganizationRole.Steward, ParticipationBasis.Independent, None), OrganizationRef.Project(projectA))
        a <- mutation(c, EndParticipant(participantA), OrganizationRef.Participation(participantA), OrganizationRef.Project(projectA))
        b <- mutation(c, EndParticipant(second), OrganizationRef.Participation(second), OrganizationRef.Project(projectA))
        results <- Vector(alice -> a, bob -> b).parTraverse((actor, r) => c.execute(AccessContext(actor), r))
        after <- c.snapshot
        _ = assertEquals(results.count(_.isRight), 1)
        _ = assert(!after.recoveryNeeds(Instant.now()).projects(projectA))
        _ = assert(after.steward(datasetA, alice))
      yield ()
    }
  }

  test("expiry is exclusive at the deadline and expired appointments are reported for recovery") {
    fixture { c =>
      val expiry = Instant.now().plusSeconds(3600)
      val membership = id[LabMembershipTag](32); val participation = id[ProjectParticipationTag](42)
      for
        _ <- run(c, alice, AddMember(membership, labA, guest, LabRole.Member, Some(expiry)), OrganizationRef.Lab(labA))
        _ <- run(c, alice, AddParticipant(participation, projectA, guest, OrganizationRole.Steward, ParticipationBasis.Membership(membership), None),
          OrganizationRef.Project(projectA), OrganizationRef.Membership(membership))
        _ <- run(c, alice, EndParticipant(participantA), OrganizationRef.Participation(participantA), OrganizationRef.Project(projectA))
        s <- c.snapshot
        _ = assertEquals(s.projectRole(projectA, guest, expiry.minusNanos(1)), Some(OrganizationRole.Steward))
        _ = assertEquals(s.projectRole(projectA, guest, expiry), None)
        _ = assert(s.recoveryNeeds(expiry).projects(projectA))
        _ = assertEquals(s.memberships(membership).expiresAt, Some(expiry))
      yield ()
    }
  }

  test("inheritance is explicit and confirmed against the current recipient roles and policy generation") {
    fixture { c => for
      impact <- ok(c.inheritanceImpact(AccessContext(alice), datasetA))
      request <- mutation(c, SetInheritance(datasetA, PlacementPolicy.ProjectMembers, impact), OrganizationRef.Placement(datasetA), OrganizationRef.Project(projectA))
      _ <- run(c, alice, AddParticipant(id[ProjectParticipationTag](42), projectA, bob, OrganizationRole.Reader, ParticipationBasis.Independent, None), OrganizationRef.Project(projectA))
      stale <- c.execute(AccessContext(alice), request)
      _ = assert(stale.isLeft)
      freshPreconditions <- mutation(c, request.command, OrganizationRef.Placement(datasetA), OrganizationRef.Project(projectA))
      staleImpact <- c.execute(AccessContext(alice), freshPreconditions)
      _ = assert(staleImpact.isLeft)
      current <- ok(c.inheritanceImpact(AccessContext(alice), datasetA))
      _ = assertEquals(current.recipients(bob), OrganizationRole.Reader)
      _ <- run(c, alice, SetInheritance(datasetA, PlacementPolicy.ProjectMembers, current), OrganizationRef.Placement(datasetA), OrganizationRef.Project(projectA))
      after <- c.snapshot
      _ = assertEquals(after.placements(datasetA).policy, PlacementPolicy.ProjectMembers)
      _ = assertEquals(after.knownDatasets(datasetA), datasets.head)
    yield () }
  }

  test("archival fences active work and creation, preserves appointments, and requires an active parent to reopen") {
    fixture { c => for
      _ <- ok(c.activeWork(projectA, id[LeaseTag](1), true))
      _ <- ok(c.activeWork(projectA, id[LeaseTag](2), true))
      _ <- ok(c.activeWork(projectA, id[LeaseTag](1), false))
      archive <- mutation(c, ArchiveProject(projectA, true), OrganizationRef.Project(projectA), OrganizationRef.Lab(labA))
      busy <- c.execute(AccessContext(alice), archive)
      _ = assert(busy.isLeft)
      _ <- ok(c.activeWork(projectA, id[LeaseTag](2), false))
      _ <- run(c, alice, ArchiveProject(projectA, true), OrganizationRef.Project(projectA), OrganizationRef.Lab(labA))
      work <- c.activeWork(projectA, id[LeaseTag](3), true)
      _ = assert(work.isLeft)
      adding <- mutation(c, AddParticipant(id[ProjectParticipationTag](42), projectA, guest, OrganizationRole.Reader, ParticipationBasis.Independent, None), OrganizationRef.Project(projectA))
      denied <- c.execute(AccessContext(alice), adding)
      _ = assert(denied.isLeft)
      _ <- run(c, alice, ArchiveLab(labA, true), OrganizationRef.Lab(labA))
      reopening <- mutation(c, ArchiveProject(projectA, false), OrganizationRef.Project(projectA), OrganizationRef.Lab(labA))
      closed <- c.execute(AccessContext(alice), reopening)
      _ = assert(closed.isLeft)
      _ <- run(c, alice, ArchiveLab(labA, false), OrganizationRef.Lab(labA))
      _ <- run(c, alice, ArchiveProject(projectA, false), OrganizationRef.Project(projectA), OrganizationRef.Lab(labA))
      s <- c.snapshot
      _ = assertEquals(s.projectRole(projectA, alice, Instant.now()), Some(OrganizationRole.Steward))
      _ = assertEquals(s.knownDatasets, datasets.map(d => d.id -> d).toMap)
    yield () }
  }
