package sanctity.app

import io.circe.{HCursor, Json}
import io.circe.jawn.parse
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

/** The same symbolic corpus can be consumed by F03.2/F03.3's SQL/HTTP adapters.
  * Expected decisions and visible rows come only from the retained ADR fixture.
  */
object OrganizationAuthorityFixtures:
  def id[A](name: String): Id[A] = Id[A](UUID.nameUUIDFromBytes(name.getBytes(UTF_8)))
  def principal(name: String): PrincipalId = id("principal/" + name)
  def dataset(name: String): DatasetId = id("dataset/" + name)
  def projectId(name: String): ProjectId = id("project/" + name)
  def lab(name: String): LabId = id("lab/" + name)
  def release(d: String, name: String): ReleaseId = id("release/" + d + "/" + name)
  def scope(d: String, r: Option[String] = None): DataScope = DataScope(dataset(d), r.map(release(d, _)))
  val generation: OrganizationGeneration = OrganizationGeneration.initial
  private def name(s: String): OrganizationName = OrganizationName.from(s).toOption.get
  private val description = OrganizationDescription.from("").toOption.get
  private val epoch = Instant.EPOCH
  private val root = Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
    .find(p => Files.isRegularFile(p.resolve("docs/decisions/institutional-authority-cases.json"))).get
  val corpus: Json = parse(Files.readString(root.resolve("docs/decisions/institutional-authority-cases.json"))).toOption.get
  private val baseline = corpus.hcursor.downField("baseline").success.get
  val now: Instant = Instant.parse(baseline.get[String]("clock").toOption.get)
  val cases: Vector[Json] = corpus.hcursor.get[Vector[Json]]("cases").toOption.get
  private def entries(c: HCursor, field: String): Vector[(String, Json)] = c.downField(field).focus.get.asObject.get.toVector
  private def str(c: HCursor, field: String): String = c.get[String](field).toOption.get
  private def optional(c: HCursor, field: String): Option[String] = c.get[Option[String]](field).toOption.get
  private def roleId(who: String, project: String): ProjectParticipationId = id("role/" + who + "/" + project)
  private def memberId(who: String, labName: String): LabMembershipId = id("member/" + who + "/" + labName)
  val datasetNames: Map[DatasetId, String] = entries(baseline, "datasets").map((n, _) => dataset(n) -> n).toMap
  def scopeName(s: DataScope): String =
    val n = datasetNames(s.dataset)
    s.release.fold(n)(r => n + "/" + Vector("R1", "R2").find(release(n, _) == r).get)
  def admission(who: String): PolicyAdmission = PolicyAdmission(principal(who), true, None)
  def grant(j: Json, grantName: String): PolicyGrant =
    val c = j.hcursor
    PolicyGrant(id("grant/" + grantName), None, principal(str(c, "recipient")),
      scope(str(c, "dataset"), optional(c, "release")), c.get[Set[String]]("actions").toOption.get.map(Permission.valueOf),
      epoch, optional(c, "expiresAt").map(Instant.parse), GrantBasis.Independent, generation)

  val state: State =
    val people = entries(baseline, "principals")
    val projects = entries(baseline, "projects").map { (n, j) =>
      projectId(n) -> Project(projectId(n), lab(str(j.hcursor, "owningLab")), name(n), description,
        OrganizationStatus.Active, generation, epoch)
    }.toMap
    val labs = projects.values.map(_.lab).toSet.map(l => l -> Lab(l, name("Lab"), description,
      OrganizationStatus.Active, generation, epoch)).toMap
    val principalRecords = people.map { (n, j) => principal(n) -> OrganizationPrincipal(principal(n), name(n),
      j.hcursor.get[Boolean]("active").toOption.get, generation, epoch) }.toMap
    val eligibility = people.map { (n, j) => principal(n) -> optional(j.hcursor, "guestSponsorshipExpiresAt")
      .fold[Eligibility](Eligibility.Local)(e => Eligibility.Guest(Sponsorship(true, true, true, Instant.parse(e)))) }.toMap
    val roles = people.flatMap { (who, j) => j.hcursor.get[Vector[String]]("roles").toOption.get.map { text =>
      val parts = text.split(":", 2); (who, parts(0), parts(1)) } }
    val members = roles.collect { case (who, r, l) if Set("admin", "member")(r) =>
      val mid = memberId(who, l)
      mid -> LabMembership(mid, lab(l), principal(who), if r == "admin" then LabRole.Admin else LabRole.Member,
        AppointmentStatus.Active, None, generation, epoch) }.toMap
    val participants = roles.collect { case (who, r, pr) if Set("reader", "contributor", "steward")(r) =>
      val rid = roleId(who, pr)
      val role = r match
        case "reader" => OrganizationRole.Reader
        case "contributor" => OrganizationRole.Contributor
        case "steward" => OrganizationRole.Steward
        case other => throw new IllegalArgumentException("unmapped corpus role: " + other)
      rid -> ProjectParticipation(rid, projectId(pr), principal(who), role, ParticipationBasis.Independent,
        AppointmentStatus.Active, None, generation, epoch) }.toMap
    val stewards = roles.collect { case (who, "datasetSteward", d) =>
      val sid = id[DatasetStewardshipTag]("steward/" + who + "/" + d)
      sid -> DatasetStewardship(sid, dataset(d), principal(who), AppointmentStatus.Active, generation, epoch) }.toMap
    val datasets = entries(baseline, "datasets").map { (n, j) => dataset(n) -> DatasetState(dataset(n),
      j.hcursor.get[Set[String]]("releases").toOption.get.map(release(n, _)), Lifecycle.Available,
      head = Some(release(n, str(j.hcursor, "head")))) }.toMap
    val placements = entries(baseline, "datasets").map { (n, j) => dataset(n) -> DatasetPlacement(dataset(n),
      projectId(str(j.hcursor, "primaryProject")), PlacementPolicy.valueOf(str(j.hcursor, "accessMode")), generation, epoch) }.toMap
    val grants = entries(baseline, "grants").map((n, j) => grant(j, n)).map(g => g.id -> g).toMap
    val links = entries(baseline, "projects").flatMap { (n, j) =>
      j.hcursor.get[Option[Vector[String]]]("links").toOption.get.getOrElse(Vector.empty).map { d =>
        val lid = id[ProjectDatasetLinkTag]("link/" + n + "/" + d)
        lid -> ProjectDatasetLink(lid, projectId(n), dataset(d), generation, epoch) } }.toMap
    State(principalRecords, eligibility, datasets, labs, projects, members, participants, placements, stewards, grants, links)

  final case class Scenario(state: State, now: Instant, actor: PolicyAdmission, request: Request, expected: Json)
  def scenario(j: Json): Scenario =
    val c = j.hcursor
    val (s, clock) = c.get[Vector[Json]]("changes").toOption.get.foldLeft(state -> now) { case ((s, time), change) =>
      val fields = change.asObject.get.toVector
      require(fields.size == 1)
      val (op, value) = fields.head
      op match
        case "clock" => s -> Instant.parse(value.asString.get)
        case "disablePrincipal" =>
          val p = principal(value.asString.get); s.copy(principals = s.principals.updated(p, s.principals(p).copy(active = false))) -> time
        case "endGuestSponsorship" =>
          val p = principal(value.asString.get)
          val Eligibility.Guest(g) = s.eligibility(p): @unchecked
          s.copy(eligibility = s.eligibility.updated(p, Eligibility.Guest(g.copy(active = false)))) -> time
        case "endParticipation" =>
          val xs = value.asArray.get.map(_.asString.get); val rid = roleId(xs(0), xs(1))
          s.copy(participations = s.participations.updated(rid, s.participations(rid).copy(status = AppointmentStatus.Ended))) -> time
        case "addGrant" =>
          val g = grant(value, "added"); s.copy(grants = s.grants.updated(g.id, g)) -> time
        case "replaceRoleWithDerivedGrant" =>
          val gc = value.hcursor; val who = str(gc, "recipient")
          val previous = roleId(who, "P-memory"); val rid = id[ProjectParticipationTag](str(gc, "requiresParticipation"))
          val g = grant(value, "derived").copy(basis = GrantBasis.Derived(Set(Appointment.Participation(rid))))
          s.copy(participations = (s.participations - previous).updated(rid, s.participations(previous).copy(id = rid)),
            grants = s.grants.updated(g.id, g)) -> time
        case "endParticipationId" =>
          val rid = id[ProjectParticipationTag](value.asString.get)
          s.copy(participations = s.participations.updated(rid, s.participations(rid).copy(status = AppointmentStatus.Ended))) -> time
        case "rejoinWithoutRoleOrGrant" =>
          val mid = id[LabMembershipTag](value.asString.get)
          s.copy(memberships = s.memberships.updated(mid, LabMembership(mid, lab("L-A"), principal("bob"), LabRole.Member,
            AppointmentStatus.Active, None, generation, epoch))) -> time
        case "archiveProject" =>
          val pr = projectId(value.asString.get)
          s.copy(projects = s.projects.updated(pr, s.projects(pr).copy(status = OrganizationStatus.Archived))) -> time
        case "withdrawDataset" =>
          val d = dataset(value.asString.get)
          s.copy(datasets = s.datasets.updated(d, s.datasets(d).copy(lifecycle = Lifecycle.Withdrawn))) -> time
        case other => throw new IllegalArgumentException("unmapped corpus operation: " + other)
    }
    val actor = admission(str(c, "actor")); val target = str(c, "target"); val r = optional(c, "release")
    val action = str(c, "action")
    val request = action match
      case "Discover" | "ReadMetadata" | "ReadContent" | "ReadEvidence" | "Propose" | "Accept" | "ManageAccess" =>
        Request.Data(scope(target, r), Permission.valueOf(action))
      case "ReadHead" => Request.ReadHead(dataset(target))
      case "ListDatasets" => Request.ListDatasets(projectId(target))
      case "ManageLabMembership" => Request.ManageLabMembership(lab(target))
      case "ListSharedReleases" => Request.ListSharedReleases
      case "TraverseProvenance" => Request.Provenance(scope("D-open", r), scope(target, r))
      case "ReplayAcceptanceReceipt" => Request.Receipt(ReleaseRef(dataset(target), release(target, r.get)))
      case "DeliverCachedChunk" => Request.CachedChunk(ReleaseRef(dataset(target), release(target, r.get)))
      case "ReadFrozenSelection" => Request.Selection(target.split(" \\+ ").toVector.map(scope(_, r)), Permission.ReadMetadata)
      case "StartRun" =>
        val parts = target.split(" -> ")
        Request.StartRun(Vector(scope(parts(0), r)), dataset(parts(1)), true, true)
      case "DepositRunOutput" => Request.DepositRunOutput(dataset(target))
      case "GrantReadEvidence" => Request.IssueGrant(PolicyGrant(id("grant/proposed"), Some(actor.principal), principal("bob"),
        scope(target, r), Set(Permission.ReadEvidence), epoch, None, GrantBasis.Independent, generation))
      case other => throw new IllegalArgumentException("unmapped corpus action: " + other)
    Scenario(s, clock, actor, request, c.downField("expected").focus.get)

final class OrganizationAuthoritySuite extends ScalaCheckSuite:
  import OrganizationAuthorityFixtures.*
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(500)
  private val open = dataset("D-open")
  private val restricted = dataset("D-restricted")
  private val pr = projectId("P-memory")
  private val bob = admission("bob")
  private val alice = admission("alice")
  private val eve = admission("eve")
  private val r1 = scope("D-open", Some("R1"))
  private def permitsAs(s: State, who: String, permission: Permission, at: Instant = now): Boolean =
    permits(s, admission(who), Request.Data(r1, permission), at)
  private def independent(p: PrincipalId, actions: Set[Permission], releaseId: Option[ReleaseId] = None): PolicyGrant =
    PolicyGrant(id("grant/property"), Some(alice.principal), p, DataScope(open, releaseId), actions, Instant.EPOCH,
      None, GrantBasis.Independent, generation)
  private def withGrant(g: PolicyGrant, s: State = state): State = s.copy(grants = s.grants.updated(g.id, g))
  private val permissionGen = Gen.oneOf(Permission.values.toVector)
  private val actorGen = Gen.oneOf(Vector("alice", "bob", "carol", "dan", "eve", "sam", "outsider"))

  test("the retained corpus has exactly 38 unique, mapped cases") {
    assertEquals(cases.map(_.hcursor.get[String]("id").toOption.get).toSet, (1 to 38).map(n => f"A$n%02d").toSet)
    cases.foreach(scenario)
  }
  cases.foreach { j =>
    val key = j.hcursor.get[String]("id").toOption.get
    test("ADR002 " + key + ": " + j.hcursor.downField("expected").get[String]("reason").toOption.get) {
      val x = scenario(j)
      assertEquals(permits(x.state, x.actor, x.request, x.now), x.expected.hcursor.get[Boolean]("allow").toOption.get)
      x.expected.hcursor.downField("visible").focus.foreach { expected =>
        val actual = x.request match
          case Request.ListDatasets(project) =>
            val rows = visibleDatasets(x.state, x.actor, project, x.now).toOption.get
            Json.obj("datasets" -> Json.arr(rows.map(d => Json.fromString(datasetNames(d)))*), "count" -> Json.fromInt(rows.size))
          case Request.ListSharedReleases => Json.obj(
            "shares" -> Json.arr(sharedScopes(x.state, x.actor, x.now).toOption.get.map(s => Json.fromString(scopeName(s)))*),
            "parentProjects" -> Json.arr(), "currentHeads" -> Json.arr())
          case _ => fail("visible corpus assertion not mapped")
        assertEquals(actual, expected)
      }
    }
  }

  test("role/action matrix has no evidence inheritance, lab-admin bypass or implicit stewardship") {
    val allowed = Map("bob" -> Set(Permission.Discover, Permission.ReadMetadata, Permission.ReadContent),
      "dan" -> Set(Permission.Discover, Permission.ReadMetadata, Permission.ReadContent, Permission.Propose),
      "sam" -> (Permission.values.toSet - Permission.ReadEvidence), "alice" -> Permission.values.toSet,
      "carol" -> Set.empty[Permission])
    for (who, actions) <- allowed; action <- Permission.values do
      assertEquals(permits(state, admission(who), Request.Data(DataScope(open), action), now), actions(action))
    assert(!permits(state, admission("sam"), Request.ManageStewardship(open), now))
    assert(permits(state, alice, Request.ManageStewardship(open), now))
  }
  property("disablement, unavailable admission and missing identity eligibility override every data basis") {
    Prop.forAll(actorGen, permissionGen) { (who, permission) =>
      val a = admission(who); val request = Request.Data(DataScope(open), permission)
      val disabled = state.copy(principals = state.principals.updated(a.principal, state.principals(a.principal).copy(active = false)))
      !permits(disabled, a, request, now) && !permits(state, a.copy(current = false), request, now) &&
        !permits(state, a.copy(expiresAt = Some(now)), request, now) &&
        !permits(state.copy(eligibility = state.eligibility - a.principal), a, request, now)
    }
  }
  property("ExplicitOnly cannot widen inherited access and navigation links cannot authorize") {
    Prop.forAll(actorGen, permissionGen) { (who, permission) =>
      val a = admission(who); val req = Request.Data(r1, permission)
      val narrowed = state.copy(placements = state.placements.updated(open, state.placements(open).copy(policy = PlacementPolicy.ExplicitOnly)))
      (!permits(narrowed, a, req, now) || permits(state, a, req, now)) &&
        permits(state.copy(links = Map.empty), a, req, now) == permits(state, a, req, now)
    }
  }
  property("narrowing grant actions or fixing a release never creates access") {
    Prop.forAll(Gen.someOf(Permission.values.toVector.filterNot(_ == Permission.ManageAccess)), permissionGen) { (actions, permission) =>
      val g = independent(principal("outsider"), actions.toSet)
      val base = withGrant(g); val reduced = withGrant(g.copy(actions = g.actions - permission))
      val fixed = withGrant(g.copy(scope = r1, actions = g.actions.intersect(Set(Permission.Discover, Permission.ReadMetadata, Permission.ReadContent, Permission.ReadEvidence))))
      val a = admission("outsider")
      Vector(DataScope(open), r1, scope("D-open", Some("R2"))).forall { sc =>
        val req = Request.Data(sc, permission)
        (!permits(reduced, a, req, now) || permits(base, a, req, now)) &&
          (!permits(fixed, a, req, now) || permits(base, a, req, now))
      }
    }
  }
  property("expiry denies at the exact boundary and never resurrects afterward") {
    Prop.forAll(Gen.choose(0L, 100000L)) { offset =>
      val g = independent(principal("outsider"), Set(Permission.ReadContent)).copy(expiresAt = Some(now))
      val s = withGrant(g)
      permitsAs(s, "outsider", Permission.ReadContent, now.minusNanos(1)) &&
        !permitsAs(s, "outsider", Permission.ReadContent, now.plusSeconds(offset))
    }
  }
  test("all named derived appointments must remain applicable; rejoining with a new ID is insufficient") {
    val member = state.memberships.values.find(_.principal == bob.principal).get
    val role = state.participations.values.find(_.principal == bob.principal).get
    val base = state.copy(placements = state.placements.updated(open, state.placements(open).copy(policy = PlacementPolicy.ExplicitOnly)))
    val g = independent(bob.principal, Set(Permission.ReadContent)).copy(basis =
      GrantBasis.Derived(Set(Appointment.Membership(member.id), Appointment.Participation(role.id))))
    val s = withGrant(g, base)
    assert(permitsAs(s, "bob", Permission.ReadContent))
    val ended = s.copy(memberships = s.memberships.updated(member.id, member.copy(status = AppointmentStatus.Ended)))
    assert(!permitsAs(ended, "bob", Permission.ReadContent))
    val next = member.copy(id = id("new-membership"))
    assert(!permitsAs(ended.copy(memberships = ended.memberships.updated(next.id, next)), "bob", Permission.ReadContent))
    val other = role.copy(project = projectId("P-methods"))
    assert(!permitsAs(s.copy(participations = s.participations.updated(role.id, other)), "bob", Permission.ReadContent))
    val wrongPerson = member.copy(principal = alice.principal)
    assert(!permitsAs(s.copy(memberships = s.memberships.updated(member.id, wrongPerson)), "bob", Permission.ReadContent))
    assert(!validGrantShape(g.copy(basis = GrantBasis.Derived(Set.empty))))
  }
  test("membership-derived project roles expire while independent participation and grants survive") {
    val role = state.participations.values.find(_.principal == bob.principal).get
    val member = state.memberships.values.find(_.principal == bob.principal).get
    val derived = role.copy(basis = ParticipationBasis.Membership(member.id))
    val s = state.copy(participations = state.participations.updated(role.id, derived),
      memberships = state.memberships.updated(member.id, member.copy(expiresAt = Some(now))))
    assert(!permitsAs(s, "bob", Permission.ReadContent))
    assert(permitsAs(s.copy(participations = s.participations.updated(role.id, role)), "bob", Permission.ReadContent))
    assert(permitsAs(withGrant(independent(bob.principal, Set(Permission.ReadContent), r1.release), s), "bob", Permission.ReadContent))
  }
  test("guests need verified current approval and finite appointments/grants within sponsorship expiry") {
    val Eligibility.Guest(sponsor) = state.eligibility(eve.principal): @unchecked
    val g = state.grants.values.head
    val req = Request.Data(g.scope, Permission.ReadContent)
    for invalid <- Vector(sponsor.copy(active = false), sponsor.copy(approved = false),
      sponsor.copy(verifiedBinding = false), sponsor.copy(expiresAt = now)) do
      assert(!permits(state.copy(eligibility = state.eligibility.updated(eve.principal, Eligibility.Guest(invalid))), eve, req, now))
    for expiry <- Vector(None, Some(sponsor.expiresAt.plusNanos(1))) do
      assert(!permits(withGrant(g.copy(expiresAt = expiry)), eve, req, now))
    assert(permits(withGrant(g.copy(expiresAt = Some(sponsor.expiresAt))), eve, req, now))
  }
  test("issuer departure preserves independent shares but issuance requires every action and nondelegation") {
    val g = independent(principal("outsider"), Set(Permission.ReadEvidence))
    assert(permits(state, alice, Request.IssueGrant(g), now))
    assert(!permits(state, admission("sam"), Request.IssueGrant(g.copy(issuer = Some(principal("sam")))), now))
    assert(!permits(state, alice, Request.IssueGrant(g.copy(actions = Set(Permission.ManageAccess))), now))
    assert(!permits(state, alice, Request.IssueGrant(g.copy(scope = r1, actions = Set(Permission.Accept))), now))
    val granted = withGrant(g)
    val disabled = granted.copy(principals = granted.principals.updated(alice.principal, granted.principals(alice.principal).copy(active = false)))
    assert(permitsAs(disabled, "outsider", Permission.ReadEvidence))
  }
  test("archive preserves reads and revocation, blocks new writes and grants; withdrawal overrides reads") {
    val archived = state.copy(projects = state.projects.updated(pr, state.projects(pr).copy(status = OrganizationStatus.Archived)))
    for permission <- Permission.values do
      assertEquals(permits(archived, alice, Request.Data(DataScope(open), permission), now),
        Set(Permission.Discover, Permission.ReadMetadata, Permission.ReadContent, Permission.ReadEvidence)(permission))
    val g = independent(bob.principal, Set(Permission.ReadContent))
    assert(!permits(archived, alice, Request.IssueGrant(g), now))
    assert(permits(withGrant(g, archived), alice, Request.RevokeGrant(g.id), now))
    for lifecycle <- Vector(Lifecycle.Withdrawn, Lifecycle.Quarantined, Lifecycle.Unavailable) do
      assert(!permitsAs(state.copy(datasets = state.datasets.updated(open, state.datasets(open).copy(lifecycle = lifecycle))), "alice", Permission.ReadContent))
  }
  test("unknown scopes and malformed references fail closed without historical-curator fallback") {
    val known = Request.Data(r1, Permission.ReadContent)
    assert(!permits(state.copy(placements = state.placements - open), alice, known, now))
    assert(!permits(state.copy(labs = Map.empty), alice, known, now))
    assert(!permits(state.copy(stewardships = Map.empty), alice, Request.Data(DataScope(restricted), Permission.ReadContent), now))
    assert(!permits(state, alice, Request.Data(DataScope(open, Some(id("unknown-release"))), Permission.ReadContent), now))
    assert(!permits(state, alice, Request.Selection(Vector.empty, Permission.ReadContent), now))
    assert(!permits(state, alice, Request.Selection(Vector(r1), Permission.Accept), now))
  }
  test("receipt, cached bytes, candidate evidence, provenance and processing preserve distinct prerequisites") {
    assert(permits(state, bob, Request.Receipt(ReleaseRef(open, r1.release.get)), now))
    assert(!permits(state, bob, Request.Provenance(r1, scope("D-restricted", Some("R1"))), now))
    assert(permits(state, alice, Request.Provenance(r1, scope("D-restricted", Some("R1"))), now))
    assert(!permits(state, admission("dan"), Request.Candidate(open, Vector(r1)), now))
    assert(permits(state, alice, Request.Candidate(open, Vector(r1)), now))
    assert(permits(state, admission("dan"), Request.StartRun(Vector(r1), open, true, true), now))
    assert(!permits(state, admission("dan"), Request.StartRun(Vector(r1), open, false, true), now))
    assert(!permits(state, admission("dan"), Request.StartRun(Vector(r1), open, true, false), now))
    assert(!permits(state, bob, Request.DepositRunOutput(open), now))
  }
  test("self explanations separate surviving share and role bases without disclosing other identities") {
    val g = independent(bob.principal, Set(Permission.ReadContent), r1.release)
    val s = withGrant(g)
    val before = explain(s, bob, bob.principal, r1, Permission.ReadContent, now).toOption.get
    assertEquals(before.bases.size, 2)
    val role = s.participations.values.find(_.principal == bob.principal).get
    val ended = s.copy(participations = s.participations.updated(role.id, role.copy(status = AppointmentStatus.Ended)))
    val after = explain(ended, bob, bob.principal, r1, Permission.ReadContent, now).toOption.get
    assertEquals(after.bases, Vector(Basis.Share(g.id, r1, generation, None, false)))
    assertEquals(explain(s, admission("carol"), bob.principal, r1, Permission.ReadContent, now), Left(Problem.Denied))
    assertEquals(explain(s, bob, alice.principal, r1, Permission.ReadContent, now), Left(Problem.Denied))
    assert(explain(s, alice, bob.principal, r1, Permission.ReadContent, now).isRight)
    assertEquals(explain(s, admission("sam"), alice.principal, r1, Permission.ReadEvidence, now), Left(Problem.Denied))
    assertEquals(explain(s, alice, id("unknown-person"), r1, Permission.ReadContent, now), Left(Problem.Denied))
  }
  property("hidden datasets, links and grant recipients cannot change a permitted self explanation or directory") {
    Prop.forAll(Gen.choose(1, 100000)) { n =>
      val g = independent(principal("outsider"), Set(Permission.ReadContent)).copy(id = id("hidden/" + n), scope = DataScope(restricted))
      val hidden = withGrant(g).copy(datasets = state.datasets.updated(restricted,
        state.datasets(restricted).copy(releases = state.datasets(restricted).releases + id("hidden-release/" + n))))
      explain(state, bob, bob.principal, r1, Permission.ReadContent, now) == explain(hidden, bob, bob.principal, r1, Permission.ReadContent, now) &&
        visibleDatasets(state, bob, pr, now) == visibleDatasets(hidden, bob, pr, now) &&
        sharedScopes(state, eve, now) == sharedScopes(hidden, eve, now)
    }
  }
  test("fixed shares and legacy metadata-only shares cannot enumerate projects, heads or hidden links") {
    val scopes = sharedScopes(state, eve, now).toOption.get
    assertEquals(scopes, Vector(scope("D-restricted", Some("R1"))))
    assertEquals(visibleDatasets(state, eve, pr, now), Left(Problem.Denied))
    assertEquals(visibleLinks(state, admission("outsider"), projectId("P-methods"), now), Right(Vector.empty))
    val legacy = Grant(id("legacy"), open, principal("outsider"), r1.release, Set(Action.ReadMetadata))
    val g = PolicyGrant.legacy(legacy, Instant.EPOCH, generation)
    assertEquals(g.actions, Set(Permission.ReadMetadata))
    val s = withGrant(g)
    assert(permitsAs(s, "outsider", Permission.ReadMetadata))
    assert(!permits(s, admission("outsider"), Request.ReadHead(open), now))
    assertEquals(sharedScopes(s, admission("outsider"), now), Right(Vector.empty))
  }
  test("effective-access explanations include the earliest membership, role, share or sponsorship expiry") {
    val member = state.memberships.values.find(_.principal == bob.principal).get
    val role = state.participations.values.find(_.principal == bob.principal).get
    val deadline = now.plusSeconds(10)
    val derived = role.copy(basis = ParticipationBasis.Membership(member.id), expiresAt = Some(now.plusSeconds(20)))
    val g = independent(bob.principal, Set(Permission.ReadContent)).copy(
      basis = GrantBasis.Derived(Set(Appointment.Participation(role.id))), expiresAt = Some(now.plusSeconds(30)))
    val s = withGrant(g).copy(memberships = state.memberships.updated(member.id, member.copy(expiresAt = Some(deadline))),
      participations = state.participations.updated(role.id, derived))
    val explanation = explain(s, bob, bob.principal, r1, Permission.ReadContent, now).toOption.get
    assertEquals(explanation.bases, Vector(Basis.ProjectRole(role.id, role.role, generation, Some(deadline)),
      Basis.Share(g.id, g.scope, generation, Some(deadline), true)))
    assert(!permitsAs(s, "bob", Permission.ReadContent, deadline))
  }
  test("a quarantined or unknown current head cannot be disclosed through dataset-wide metadata rights") {
    val d = state.datasets(open)
    val hidden = state.copy(datasets = state.datasets.updated(open,
      d.copy(releaseLifecycle = Map(d.head.get -> Lifecycle.Quarantined))))
    assert(!permits(hidden, bob, Request.ReadHead(open), now))
    assert(permits(hidden, bob, Request.Data(r1, Permission.ReadContent), now))
    val invalid = state.copy(datasets = state.datasets.updated(open, d.copy(head = Some(id("unknown-head")))))
    assert(!permits(invalid, bob, Request.ReadHead(open), now))
  }
  property("ending one participation cannot remove another independent role or share") {
    Prop.forAll(Gen.choose(1, 100000)) { n =>
      val original = state.participations.values.find(_.principal == bob.principal).get
      val another = original.copy(id = id("independent-role/" + n))
      val roles = state.participations.updated(original.id, original.copy(status = AppointmentStatus.Ended)).updated(another.id, another)
      val s = state.copy(participations = roles)
      permitsAs(s, "bob", Permission.ReadContent) &&
        permitsAs(withGrant(independent(bob.principal, Set(Permission.ReadContent)),
          s.copy(participations = roles - another.id)), "bob", Permission.ReadContent)
    }
  }
