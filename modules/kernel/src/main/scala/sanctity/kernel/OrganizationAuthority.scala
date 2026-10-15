package sanctity.kernel

import java.time.Instant

/** Pure ADR002 rules. All inputs are trusted, current adapter state, never request claims.
  * F03.2 must call this under the policy transaction guard; evaluating a snapshot does
  * not authenticate a credential, implement a session, or make a cached decision current.
  * The legacy Action/Grant wire contract is adapted explicitly until that integration.
  */
object OrganizationAuthority:
  enum Permission:
    case Discover, ReadMetadata, ReadContent, ReadEvidence, Propose, Accept, ManageAccess
  object Permission:
    def fromLegacy(action: Action): Permission = action match
      case Action.ReadMetadata => ReadMetadata
      case Action.ReadContent => ReadContent
      case Action.Propose => Propose
      case Action.Accept => Accept
      case Action.ManageAccess => ManageAccess

  enum Lifecycle:
    case Available, Quarantined, Withdrawn, Unavailable
  final case class DataScope(dataset: DatasetId, release: Option[ReleaseId] = None)
  final case class DatasetState(id: DatasetId, releases: Set[ReleaseId], lifecycle: Lifecycle,
      releaseLifecycle: Map[ReleaseId, Lifecycle] = Map.empty, head: Option[ReleaseId] = None)
  final case class Sponsorship(verifiedBinding: Boolean, approved: Boolean, active: Boolean,
      expiresAt: Instant)
  enum Eligibility:
    /** A trusted local credential admission; this does not assert an institutional identity. */
    case Local
    case Guest(sponsorship: Sponsorship)
  final case class PolicyAdmission(principal: PrincipalId, current: Boolean, expiresAt: Option[Instant])
  enum Appointment:
    case Membership(id: LabMembershipId)
    case Participation(id: ProjectParticipationId)
  enum GrantBasis:
    case Independent
    case Derived(requires: Set[Appointment])
  final case class PolicyGrant(id: GrantId, issuer: Option[PrincipalId], recipient: PrincipalId,
      scope: DataScope, actions: Set[Permission], createdAt: Instant, expiresAt: Option[Instant],
      basis: GrantBasis, generation: OrganizationGeneration, revoked: Boolean = false)
  object PolicyGrant:
    /** Migration never adds discovery, evidence, expiry or inferred issuer identity. */
    def legacy(grant: Grant, createdAt: Instant, generation: OrganizationGeneration): PolicyGrant =
      PolicyGrant(grant.id, None, grant.recipient, DataScope(grant.dataset, grant.release),
        grant.actions.map(Permission.fromLegacy), createdAt, None, GrantBasis.Independent, generation)

  final case class State(
      principals: Map[PrincipalId, OrganizationPrincipal], eligibility: Map[PrincipalId, Eligibility],
      datasets: Map[DatasetId, DatasetState], labs: Map[LabId, Lab], projects: Map[ProjectId, Project],
      memberships: Map[LabMembershipId, LabMembership], participations: Map[ProjectParticipationId, ProjectParticipation],
      placements: Map[DatasetId, DatasetPlacement], stewardships: Map[DatasetStewardshipId, DatasetStewardship],
      grants: Map[GrantId, PolicyGrant], links: Map[ProjectDatasetLinkId, ProjectDatasetLink] = Map.empty)

  enum Request:
    case Data(scope: DataScope, permission: Permission)
    case ReadHead(dataset: DatasetId)
    case DiscoverLab(lab: LabId)
    case ManageLabMembership(lab: LabId)
    case DiscoverProject(project: ProjectId)
    case ManageProject(project: ProjectId)
    case ManageStewardship(dataset: DatasetId)
    case ListDatasets(project: ProjectId)
    case ListSharedReleases
    case Receipt(ref: ReleaseRef)
    case CachedChunk(ref: ReleaseRef)
    case Selection(scopes: Vector[DataScope], permission: Permission)
    case Provenance(from: DataScope, to: DataScope)
    case Candidate(dataset: DatasetId, evidence: Vector[DataScope])
    case StartRun(inputs: Vector[DataScope], destination: DatasetId, recipeAllowed: Boolean, leaseCurrent: Boolean)
    case DepositRunOutput(destination: DatasetId)
    case IssueGrant(grant: PolicyGrant)
    case RevokeGrant(id: GrantId)

  enum Basis:
    case Stewardship(id: DatasetStewardshipId, generation: OrganizationGeneration, expiresAt: Option[Instant])
    case ProjectRole(id: ProjectParticipationId, role: OrganizationRole,
        generation: OrganizationGeneration, expiresAt: Option[Instant])
    case Share(id: GrantId, scope: DataScope, generation: OrganizationGeneration,
        expiresAt: Option[Instant], derived: Boolean)
  final case class Explanation(scope: DataScope, permission: Permission, allowed: Boolean, bases: Vector[Basis])

  private val reads = Set(Permission.Discover, Permission.ReadMetadata, Permission.ReadContent, Permission.ReadEvidence)
  private val reader = Set(Permission.Discover, Permission.ReadMetadata, Permission.ReadContent)
  private def roleActions(role: OrganizationRole): Set[Permission] = role match
    case OrganizationRole.Reader => reader
    case OrganizationRole.Contributor => reader + Permission.Propose
    case OrganizationRole.Steward => reader ++ Set(Permission.Propose, Permission.Accept, Permission.ManageAccess)
  private def live(created: Instant, expires: Option[Instant], now: Instant): Boolean =
    !now.isBefore(created) && expires.forall(now.isBefore)
  private def eligible(s: State, p: PrincipalId, now: Instant): Boolean =
    s.principals.get(p).exists(x => x.id == p && x.active && !now.isBefore(x.createdAt)) &&
      s.eligibility.get(p).exists {
        case Eligibility.Local => true
        case Eligibility.Guest(g) => g.verifiedBinding && g.approved && g.active && now.isBefore(g.expiresAt)
      }
  private def admitted(s: State, a: PolicyAdmission, now: Instant): Boolean =
    a.current && a.expiresAt.forall(now.isBefore) && eligible(s, a.principal, now)
  private def guestExpiry(s: State, p: PrincipalId, expiry: Option[Instant]): Boolean = s.eligibility.get(p) match
    case Some(Eligibility.Guest(g)) => expiry.exists(e => !e.isAfter(g.expiresAt))
    case Some(Eligibility.Local) => true
    case None => false
  private def earliest(values: Option[Instant]*): Option[Instant] =
    values.flatten.reduceOption((a, b) => if a.isBefore(b) then a else b)
  private def sponsorExpiry(s: State, p: PrincipalId): Option[Instant] = s.eligibility.get(p).flatMap {
    case Eligibility.Guest(g) => Some(g.expiresAt)
    case Eligibility.Local => None
  }
  private def roleExpiry(s: State, r: ProjectParticipation): Option[Instant] =
    val member = r.basis match
      case ParticipationBasis.Independent => None
      case ParticipationBasis.Membership(id) => s.memberships.get(id).flatMap(_.expiresAt)
    earliest(r.expiresAt, member, sponsorExpiry(s, r.principal))
  private def shareExpiry(s: State, g: PolicyGrant): Option[Instant] =
    val dependencies = g.basis match
      case GrantBasis.Independent => Vector.empty
      case GrantBasis.Derived(refs) => refs.toVector.map {
        case Appointment.Membership(id) => s.memberships.get(id).flatMap(_.expiresAt)
        case Appointment.Participation(id) => s.participations.get(id).flatMap(roleExpiry(s, _))
      }
    earliest((Vector(g.expiresAt, sponsorExpiry(s, g.recipient)) ++ dependencies)*)
  private def project(s: State, id: ProjectId): Option[Project] =
    s.projects.get(id).filter(p => p.id == id && s.labs.get(p.lab).exists(_.id == p.lab))
  private def membership(s: State, id: LabMembershipId, p: PrincipalId, now: Instant): Option[LabMembership] =
    s.memberships.get(id).filter(m => m.id == id && m.principal == p && s.labs.get(m.lab).exists(_.id == m.lab) &&
      eligible(s, p, now) && m.status == AppointmentStatus.Active && live(m.createdAt, m.expiresAt, now) &&
      guestExpiry(s, p, m.expiresAt))
  private def participation(s: State, id: ProjectParticipationId, p: PrincipalId, now: Instant): Option[ProjectParticipation] =
    s.participations.get(id).filter(r => r.id == id && r.principal == p && project(s, r.project).nonEmpty &&
      eligible(s, p, now) && r.status == AppointmentStatus.Active && live(r.createdAt, r.expiresAt, now) &&
      guestExpiry(s, p, r.expiresAt) && (r.basis match
        case ParticipationBasis.Independent => true
        case ParticipationBasis.Membership(m) => membership(s, m, p, now).nonEmpty))
  private def roles(s: State, p: PrincipalId, pr: ProjectId, now: Instant): Vector[ProjectParticipation] =
    s.participations.keys.toVector.flatMap(participation(s, _, p, now)).filter(_.project == pr).sortBy(_.id.render)
  private def labAdmin(s: State, p: PrincipalId, lab: LabId, now: Instant): Boolean =
    s.memberships.keys.exists(id => membership(s, id, p, now).exists(m => m.lab == lab && m.role == LabRole.Admin))
  private def placement(s: State, d: DatasetId): Option[DatasetPlacement] =
    s.placements.get(d).filter(x => x.dataset == d && project(s, x.project).nonEmpty)
  private def writable(s: State, d: DatasetId): Boolean = placement(s, d).exists { x =>
    project(s, x.project).exists(p => p.status == OrganizationStatus.Active && s.labs(p.lab).status == OrganizationStatus.Active)
  }
  private def exists(s: State, scope: DataScope): Boolean =
    s.datasets.get(scope.dataset).exists(d => d.id == scope.dataset && scope.release.forall(d.releases.contains)) &&
      placement(s, scope.dataset).nonEmpty
  private def available(s: State, scope: DataScope): Boolean = exists(s, scope) && s.datasets.get(scope.dataset).exists(d =>
    d.lifecycle == Lifecycle.Available && scope.release.forall(r => d.releaseLifecycle.getOrElse(r, Lifecycle.Available) == Lifecycle.Available))
  private def stewards(s: State, p: PrincipalId, d: DatasetId, now: Instant): Vector[DatasetStewardship] =
    s.stewardships.toVector.collect { case (id, x) if x.id == id && x.dataset == d && x.principal == p &&
      x.status == AppointmentStatus.Active && !now.isBefore(x.createdAt) => x }.sortBy(_.id.render)

  def validGrantShape(g: PolicyGrant): Boolean =
    g.actions.nonEmpty && !g.actions.contains(Permission.ManageAccess) &&
      (g.scope.release.isEmpty || g.actions.subsetOf(reads)) &&
      g.expiresAt.forall(_.isAfter(g.createdAt)) && (g.basis match
        case GrantBasis.Independent => true
        case GrantBasis.Derived(refs) => refs.nonEmpty)
  private def applicable(s: State, g: PolicyGrant, p: PrincipalId, now: Instant): Boolean =
    g.recipient == p && !g.revoked && validGrantShape(g) && live(g.createdAt, g.expiresAt, now) &&
      guestExpiry(s, p, g.expiresAt) && (g.basis match
        case GrantBasis.Independent => true
        case GrantBasis.Derived(refs) => refs.forall {
          case Appointment.Membership(id) => membership(s, id, p, now).nonEmpty
          case Appointment.Participation(id) => participation(s, id, p, now).exists(r =>
            placement(s, g.scope.dataset).exists(_.project == r.project))
        })

  private def bases(s: State, p: PrincipalId, scope: DataScope, permission: Permission, now: Instant,
      revocation: Boolean = false): Vector[Basis] =
    val scopeValid = exists(s, scope) && (revocation || available(s, scope))
    if !eligible(s, p, now) || !scopeValid || (!reads(permission) && scope.release.nonEmpty) ||
        (!reads(permission) && !revocation && !writable(s, scope.dataset)) then Vector.empty
    else
      val explicit = stewards(s, p, scope.dataset, now).map(x => Basis.Stewardship(x.id, x.generation, sponsorExpiry(s, p)))
      val inherited = placement(s, scope.dataset).toVector.filter(_.policy == PlacementPolicy.ProjectMembers)
        .flatMap(x => roles(s, p, x.project, now)).filter(r => roleActions(r.role)(permission))
        .map(r => Basis.ProjectRole(r.id, r.role, r.generation, roleExpiry(s, r)))
      val grants = s.grants.toVector.sortBy(_._1.render).collect {
        case (id, g) if id == g.id && g.scope.dataset == scope.dataset &&
            (g.scope.release.isEmpty || g.scope.release == scope.release) && g.actions(permission) && applicable(s, g, p, now) =>
          Basis.Share(g.id, g.scope, g.generation, shareExpiry(s, g), g.basis != GrantBasis.Independent)
      }
      explicit ++ inherited ++ grants

  /** Denials deliberately have no resource-existence or hidden-policy explanation. */
  def permits(s: State, admission: PolicyAdmission, request: Request, now: Instant): Boolean =
    if !admitted(s, admission, now) then false
    else
      val p = admission.principal
      def data(scope: DataScope, action: Permission): Boolean = bases(s, p, scope, action, now).nonEmpty
      def projectVisible(id: ProjectId): Boolean = project(s, id).exists(pr =>
        roles(s, p, id, now).nonEmpty || labAdmin(s, p, pr.lab, now))
      request match
        case Request.Data(scope, permission) => data(scope, permission)
        case Request.ReadHead(d) => data(DataScope(d), Permission.ReadMetadata) &&
          s.datasets(d).head.forall(r => available(s, DataScope(d, Some(r))))
        case Request.DiscoverLab(lab) => s.memberships.keys.exists(id => membership(s, id, p, now).exists(_.lab == lab))
        case Request.ManageLabMembership(lab) => s.labs.get(lab).exists(_.status == OrganizationStatus.Active) && labAdmin(s, p, lab, now)
        case Request.DiscoverProject(pr) => projectVisible(pr)
        case Request.ManageProject(pr) => project(s, pr).exists(x => x.status == OrganizationStatus.Active &&
          s.labs(x.lab).status == OrganizationStatus.Active && roles(s, p, pr, now).exists(_.role == OrganizationRole.Steward))
        case Request.ManageStewardship(d) => available(s, DataScope(d)) && writable(s, d) && stewards(s, p, d, now).nonEmpty
        case Request.ListDatasets(pr) => projectVisible(pr)
        case Request.ListSharedReleases => true
        case Request.Receipt(ref) => data(DataScope(ref.dataset, Some(ref.release)), Permission.ReadMetadata)
        case Request.CachedChunk(ref) => data(DataScope(ref.dataset, Some(ref.release)), Permission.ReadContent)
        case Request.Selection(scopes, action) => scopes.nonEmpty && reads(action) && scopes.forall(data(_, action))
        case Request.Provenance(from, to) => data(from, Permission.ReadMetadata) && data(to, Permission.ReadMetadata)
        case Request.Candidate(d, evidence) => data(DataScope(d), Permission.Propose) && data(DataScope(d), Permission.ReadMetadata) &&
          evidence.forall(e => data(e, Permission.ReadMetadata) && data(e, Permission.ReadEvidence))
        case Request.StartRun(inputs, destination, recipeAllowed, leaseCurrent) =>
          recipeAllowed && leaseCurrent && inputs.nonEmpty && inputs.forall(data(_, Permission.ReadContent)) &&
            data(DataScope(destination), Permission.Propose)
        case Request.DepositRunOutput(d) => data(DataScope(d), Permission.Propose)
        case Request.IssueGrant(g) => validGrantShape(g) && g.issuer.contains(p) && !g.revoked &&
          !now.isBefore(g.createdAt) && g.expiresAt.forall(now.isBefore) && eligible(s, g.recipient, now) &&
          applicable(s, g, g.recipient, now) && data(DataScope(g.scope.dataset), Permission.ManageAccess) &&
          g.actions.forall(data(g.scope, _))
        case Request.RevokeGrant(id) => s.grants.get(id).exists(g => g.id == id &&
          bases(s, p, DataScope(g.scope.dataset), Permission.ManageAccess, now, revocation = true).nonEmpty)

  /** Only already authorized IDs are returned; counts must be computed on this projection. */
  def visibleDatasets(s: State, a: PolicyAdmission, projectId: ProjectId, now: Instant): Either[Problem, Vector[DatasetId]] =
    if !permits(s, a, Request.ListDatasets(projectId), now) then Left(Problem.Denied)
    else Right(s.placements.valuesIterator.filter(_.project == projectId).map(_.dataset).toVector.distinct
      .filter(d => permits(s, a, Request.Data(DataScope(d), Permission.Discover), now)).sortBy(_.render))

  /** A fixed share exposes its own scope, never a project, head, grant issuer or other recipient. */
  def sharedScopes(s: State, a: PolicyAdmission, now: Instant): Either[Problem, Vector[DataScope]] =
    if !admitted(s, a, now) then Left(Problem.Denied)
    else Right(s.grants.toVector.collect { case (id, g) if g.id == id && applicable(s, g, a.principal, now) &&
      g.actions(Permission.Discover) && available(s, g.scope) => g.scope }.distinct
      .sortBy(x => (x.dataset.render, x.release.fold("")(_.render))))

  def visibleLinks(s: State, a: PolicyAdmission, projectId: ProjectId, now: Instant): Either[Problem, Vector[DatasetId]] =
    if !permits(s, a, Request.DiscoverProject(projectId), now) then Left(Problem.Denied)
    else Right(s.links.toVector.collect { case (id, link) if id == link.id && link.project == projectId &&
      permits(s, a, Request.Data(DataScope(link.dataset), Permission.Discover), now) => link.dataset }.distinct.sortBy(_.render))

  /** Self explanations require the requested right; others require current ManageAccess
    * and that same right. This projection never includes issuer/recipient identities,
    * private parent names, unrelated scopes or a global revision that leaks hidden changes.
    */
  def explain(s: State, viewer: PolicyAdmission, subject: PrincipalId, scope: DataScope,
      permission: Permission, now: Instant): Either[Problem, Explanation] =
    val canRead = permits(s, viewer, Request.Data(scope, permission), now)
    val canManage = permits(s, viewer, Request.Data(DataScope(scope.dataset), Permission.ManageAccess), now)
    val knownHere = stewards(s, subject, scope.dataset, now).nonEmpty ||
      s.grants.values.exists(g => g.recipient == subject && g.scope.dataset == scope.dataset) ||
      placement(s, scope.dataset).exists(x => x.policy == PlacementPolicy.ProjectMembers &&
        s.participations.values.exists(r => r.principal == subject && r.project == x.project))
    if !canRead || (subject != viewer.principal && (!canManage || !knownHere)) then Left(Problem.Denied)
    else
      val reasons = bases(s, subject, scope, permission, now)
      Right(Explanation(scope, permission, reasons.nonEmpty, reasons))
