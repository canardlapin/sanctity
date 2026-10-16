package sanctity.application

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import sanctity.kernel.*
import java.time.Instant

/** Atomic organization reference semantics. F02.2/F03 join these with scientific transactions.
  * This catalog is not durable, does not authenticate browsers and supplies no dataset-read bypass.
  */
final class ReferenceOrganizationCatalog[F[_]] private (
    state: Ref[F, ReferenceOrganizationCatalog.Snapshot], operators: Set[PrincipalId]
)(using F: Sync[F]):
  import ReferenceOrganizationCatalog.*

  private[sanctity] def snapshot: F[Snapshot] = state.get

  /** Adapter input for the archival fence; never an untrusted request parameter. */
  private[sanctity] def activeWork(project: ProjectId, token: LeaseId, active: Boolean): F[Either[Problem, Unit]] =
    state.modify { s =>
      val result = for
        p <- s.projects.get(project).toRight(Problem.Denied)
        _ <- require(!active || p.status == OrganizationStatus.Active, Problem.Conflict("project is archived"))
        next <- touch(s, Set(OrganizationRef.Project(project)))
        held = s.activeWork.getOrElse(project, Set.empty)
        updated = if active then held + token else held - token
      yield next.copy(activeWork = if updated.isEmpty then s.activeWork - project else s.activeWork.updated(project, updated))
      result.fold(p => s -> Left(p), n => n -> Right(()))
    }

  def inheritanceImpact(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, OrganizationAccessImpact]] =
    F.realTimeInstant.flatMap { now => state.get.map { s =>
      for
        _ <- require(s.steward(dataset, ctx.principal), Problem.Denied)
        placement <- s.placements.get(dataset).toRight(Problem.Denied)
      yield s.impact(placement.project, now)
    }}

  def execute(ctx: AccessContext, request: OrganizationMutation): F[Either[Problem, OrganizationReceipt]] =
    F.realTimeInstant.flatMap { now => state.modify { s =>
      val result = decide(s, operators, ctx, request, now)
      result.fold(p => s -> Left(p), (next, receipt) => next -> Right(receipt))
    }}

object ReferenceOrganizationCatalog:
  private[sanctity] def decide(s: Snapshot, operators: Set[PrincipalId], ctx: AccessContext,
      request: OrganizationMutation, now: Instant): Either[Problem, (Snapshot, OrganizationReceipt)] =
    val scope = commandScope(s, request.command)
    for
      _ <- authorize(s, operators, ctx.principal, request.command, now)
      outcome <- s.retries.get((ctx.principal, scope, request.key)) match
        case Some((original, receipt)) =>
          require(original == request, Problem.Conflict("organization key belongs to another intent")).as(s -> receipt)
        case None =>
          for
            required <- prerequisites(s, request.command)
            _ <- require(request.expected.keySet == required, Problem.Conflict("required organization preconditions missing or extra"))
            _ <- required.toVector.traverse_ { ref =>
              require(s.generation(ref).contains(request.expected(ref)), Problem.Conflict("stale organization generation"))
            }
            changed <- transition(s, ctx.principal, request.command, now)
            _ <- if request.command.isInstanceOf[OrganizationCommand.DisablePrincipal] then Right(())
              else preserveStewards(s, changed, now)
            _ <- require(s.sequence < Long.MaxValue, Problem.Conflict("organization audit sequence exhausted"))
            affected = changed.references.filter(r => s.generation(r) != changed.generation(r))
            receipt = OrganizationReceipt(s.sequence + 1, ctx.principal, request.key, affected, now)
            audited = changed.copy(sequence = receipt.sequence,
              audit = s.audit :+ OrganizationAudit(receipt, request.command),
              retries = s.retries.updated((ctx.principal, scope, request.key), request -> receipt))
          yield audited -> receipt
    yield outcome

  final case class RecoveryNeeds(labs: Set[LabId], projects: Set[ProjectId], datasets: Set[DatasetId])
  final case class Snapshot private[sanctity] (
      principals: Map[PrincipalId, OrganizationPrincipal], knownDatasets: Map[DatasetId, Dataset],
      labs: Map[LabId, Lab] = Map.empty, projects: Map[ProjectId, Project] = Map.empty,
      memberships: Map[LabMembershipId, LabMembership] = Map.empty,
      participations: Map[ProjectParticipationId, ProjectParticipation] = Map.empty,
      placements: Map[DatasetId, DatasetPlacement] = Map.empty,
      stewardships: Map[DatasetStewardshipId, DatasetStewardship] = Map.empty,
      links: Map[ProjectDatasetLinkId, ProjectDatasetLink] = Map.empty,
      moves: Map[MoveRequestId, DatasetMoveRequest] = Map.empty,
      activeWork: Map[ProjectId, Set[LeaseId]] = Map.empty, sequence: Long = 0,
      audit: Vector[OrganizationAudit] = Vector.empty,
      private[sanctity] val retries: Map[(PrincipalId, String, IdempotencyKey), (OrganizationMutation, OrganizationReceipt)] = Map.empty
  ):
    def live(principal: PrincipalId): Boolean = principals.get(principal).exists(_.active)
    def live(m: LabMembership, now: Instant): Boolean =
      live(m.principal) && m.status == AppointmentStatus.Active && m.expiresAt.forall(now.isBefore)
    def live(p: ProjectParticipation, now: Instant): Boolean =
      live(p.principal) && p.status == AppointmentStatus.Active && p.expiresAt.forall(now.isBefore) && (p.basis match
        case ParticipationBasis.Independent => true
        case ParticipationBasis.Membership(id) => memberships.get(id).exists(m => m.principal == p.principal && live(m, now)))
    def admin(lab: LabId, principal: PrincipalId, now: Instant): Boolean =
      memberships.values.exists(m => m.lab == lab && m.principal == principal && m.role == LabRole.Admin && live(m, now))
    def projectRole(project: ProjectId, principal: PrincipalId, now: Instant): Option[OrganizationRole] =
      participations.values.find(p => p.project == project && p.principal == principal && live(p, now)).map(_.role)
    def steward(dataset: DatasetId, principal: PrincipalId): Boolean =
      live(principal) && stewardships.values.exists(s =>
        s.dataset == dataset && s.principal == principal && s.status == AppointmentStatus.Active)
    def impact(project: ProjectId, now: Instant): OrganizationAccessImpact =
      OrganizationAccessImpact(project, projects(project).generation,
        participations.values.filter(p => p.project == project && live(p, now)).map(p => p.principal -> p.role).toMap)
    def recoveryNeeds(now: Instant): RecoveryNeeds = RecoveryNeeds(
      labs.keySet.filterNot(l => memberships.values.exists(m => m.lab == l && m.role == LabRole.Admin && live(m, now))),
      projects.keySet.filterNot(p => participations.values.exists(m => m.project == p && m.role == OrganizationRole.Steward && live(m, now))),
      placements.keySet.filterNot(d => stewardships.values.exists(s => s.dataset == d && steward(d, s.principal))))
    def generation(ref: OrganizationRef): Option[OrganizationGeneration] = ref match
      case OrganizationRef.Principal(id) => principals.get(id).map(_.generation)
      case OrganizationRef.Lab(id) => labs.get(id).map(_.generation)
      case OrganizationRef.Project(id) => projects.get(id).map(_.generation)
      case OrganizationRef.Membership(id) => memberships.get(id).map(_.generation)
      case OrganizationRef.Participation(id) => participations.get(id).map(_.generation)
      case OrganizationRef.Placement(id) => placements.get(id).map(_.generation)
      case OrganizationRef.Stewardship(id) => stewardships.get(id).map(_.generation)
      case OrganizationRef.Link(id) => links.get(id).map(_.generation)
      case OrganizationRef.Move(id) => moves.get(id).map(_.generation)
    /** Roles `principal` currently holds for a move of `dataset` from `source` to `destination`. */
    def moveRoles(dataset: DatasetId, source: ProjectId, destination: ProjectId, principal: PrincipalId, now: Instant): Set[MoveRole] =
      Set(MoveRole.DatasetSteward -> steward(dataset, principal),
        MoveRole.SourceSteward -> projectRole(source, principal, now).contains(OrganizationRole.Steward),
        MoveRole.DestinationSteward -> projectRole(destination, principal, now).contains(OrganizationRole.Steward)).collect { case (r, true) => r }
    def references: Set[OrganizationRef] =
      principals.keySet.map(OrganizationRef.Principal.apply) ++ labs.keySet.map(OrganizationRef.Lab.apply) ++
        projects.keySet.map(OrganizationRef.Project.apply) ++ memberships.keySet.map(OrganizationRef.Membership.apply) ++
        participations.keySet.map(OrganizationRef.Participation.apply) ++ placements.keySet.map(OrganizationRef.Placement.apply) ++
        stewardships.keySet.map(OrganizationRef.Stewardship.apply) ++ links.keySet.map(OrganizationRef.Link.apply) ++
        moves.keySet.map(OrganizationRef.Move.apply)

  def create[F[_]: Sync](principals: Vector[OrganizationPrincipal], datasets: Vector[Dataset],
      operators: Set[PrincipalId]): F[ReferenceOrganizationCatalog[F]] =
    val valid = principals.map(_.id).distinct.size == principals.size && datasets.map(_.id).distinct.size == datasets.size &&
      operators.nonEmpty && operators.subsetOf(principals.filter(_.active).map(_.id).toSet) &&
      datasets.forall(d => principals.exists(_.id == d.curator))
    Checked.lift[F, Unit](require(valid, Problem.Unsupported("invalid organization bootstrap references"))) *>
      Ref.of[F, Snapshot](Snapshot(principals.map(p => p.id -> p).toMap, datasets.map(d => d.id -> d).toMap))
        .map(new ReferenceOrganizationCatalog(_, operators))

  private def require(value: Boolean, problem: => Problem): Either[Problem, Unit] = Either.cond(value, (), problem)
  private def conflict(value: Boolean, message: String): Either[Problem, Unit] = require(value, Problem.Conflict(message))
  private def parent(s: Snapshot, d: DatasetId): Either[Problem, ProjectId] = s.placements.get(d).map(_.project).toRight(Problem.Denied)
  private[sanctity] def commandScope(s: Snapshot, c: OrganizationCommand): String = c match
    case OrganizationCommand.CreateLab(_, _, _, _, _) => "installation"
    case OrganizationCommand.RenameLab(id, _, _) => "lab:" + id.render
    case OrganizationCommand.CreateProject(_, lab, _, _, _, _) => "lab:" + lab.render
    case OrganizationCommand.RenameProject(id, _, _) => "project:" + id.render
    case OrganizationCommand.AddMember(_, lab, _, _, _) => "lab:" + lab.render
    case OrganizationCommand.EndMember(id) => "lab:" + s.memberships.get(id).map(_.lab.render).getOrElse("")
    case OrganizationCommand.AddParticipant(_, project, _, _, _, _) => "project:" + project.render
    case OrganizationCommand.EndParticipant(id) => "project:" + s.participations.get(id).map(_.project.render).getOrElse("")
    case OrganizationCommand.PlaceNewDataset(dataset, _, _) => "dataset:" + dataset.render
    case OrganizationCommand.AddSteward(_, dataset, _) => "dataset:" + dataset.render
    case OrganizationCommand.EndSteward(id) => "dataset:" + s.stewardships.get(id).map(_.dataset.render).getOrElse("")
    case OrganizationCommand.SetInheritance(dataset, _, _) => "dataset:" + dataset.render
    case OrganizationCommand.ArchiveProject(id, _) => "project:" + id.render
    case OrganizationCommand.ArchiveLab(id, _) => "lab:" + id.render
    case OrganizationCommand.DisablePrincipal(id) => "principal:" + id.render
    case OrganizationCommand.TransferSteward(from, _, _) => "dataset:" + s.stewardships.get(from).map(_.dataset.render).getOrElse("")
    case OrganizationCommand.RequestMove(_, dataset, _, _, _) => "dataset:" + dataset.render
    case OrganizationCommand.ApproveMove(id) => "dataset:" + s.moves.get(id).map(_.dataset.render).getOrElse("")
    case OrganizationCommand.CommitMove(id) => "dataset:" + s.moves.get(id).map(_.dataset.render).getOrElse("")
    case OrganizationCommand.WithdrawMove(id) => "dataset:" + s.moves.get(id).map(_.dataset.render).getOrElse("")
    case OrganizationCommand.RecoverSteward(dataset, _, _, _) => "dataset:" + dataset.render

  private def authorize(s: Snapshot, operators: Set[PrincipalId], actor: PrincipalId,
      c: OrganizationCommand, now: Instant): Either[Problem, Unit] =
    def project(id: ProjectId): Boolean = s.projectRole(id, actor, now).contains(OrganizationRole.Steward)
    val allowed = s.live(actor) && (c match
      case OrganizationCommand.CreateLab(_, _, _, _, _) | OrganizationCommand.DisablePrincipal(_) => operators(actor)
      case OrganizationCommand.RenameLab(id, _, _) => s.admin(id, actor, now)
      case OrganizationCommand.ArchiveLab(id, _) => s.admin(id, actor, now)
      case OrganizationCommand.CreateProject(_, lab, _, _, _, _) => s.admin(lab, actor, now)
      case OrganizationCommand.AddMember(_, lab, _, _, _) => s.admin(lab, actor, now)
      case OrganizationCommand.EndMember(id) => s.memberships.get(id).exists(m => s.admin(m.lab, actor, now))
      case OrganizationCommand.RenameProject(id, _, _) => project(id)
      case OrganizationCommand.ArchiveProject(id, _) => project(id)
      case OrganizationCommand.AddParticipant(_, id, _, _, _, _) => project(id)
      case OrganizationCommand.EndParticipant(id) => s.participations.get(id).exists(p => project(p.project))
      case OrganizationCommand.PlaceNewDataset(d, p, _) => s.knownDatasets.get(d).exists(_.curator == actor) &&
        s.projectRole(p, actor, now).exists(_ != OrganizationRole.Reader)
      case OrganizationCommand.AddSteward(_, d, _) => s.steward(d, actor)
      case OrganizationCommand.SetInheritance(d, _, _) => s.steward(d, actor)
      case OrganizationCommand.EndSteward(id) => s.stewardships.get(id).exists(a => s.steward(a.dataset, actor))
      case OrganizationCommand.TransferSteward(from, _, _) => s.stewardships.get(from).exists(a => s.steward(a.dataset, actor))
      case OrganizationCommand.RequestMove(_, d, destination, _, _) =>
        s.placements.get(d).exists(p => s.moveRoles(d, p.project, destination, actor, now).nonEmpty)
      case OrganizationCommand.ApproveMove(id) => s.moves.get(id).exists(m => s.moveRoles(m.dataset, m.source, m.destination, actor, now).nonEmpty)
      case OrganizationCommand.CommitMove(id) => s.moves.get(id).exists(m => s.moveRoles(m.dataset, m.source, m.destination, actor, now).nonEmpty)
      case OrganizationCommand.WithdrawMove(id) => s.moves.get(id).exists(m => m.requestedBy == actor ||
        s.moveRoles(m.dataset, m.source, m.destination, actor, now).nonEmpty)
      case OrganizationCommand.RecoverSteward(_, _, _, _) => operators(actor))
    require(allowed, Problem.Denied)

  private def prerequisites(s: Snapshot, c: OrganizationCommand): Either[Problem, Set[OrganizationRef]] =
    import OrganizationRef.*
    c match
      case OrganizationCommand.CreateLab(_, _, _, _, _) => Right(Set.empty)
      case OrganizationCommand.RenameLab(id, _, _) => Right(Set(Lab(id)))
      case OrganizationCommand.ArchiveLab(id, _) => Right(Set(Lab(id)))
      case OrganizationCommand.CreateProject(_, lab, _, _, _, _) => Right(Set(Lab(lab)))
      case OrganizationCommand.AddMember(_, lab, _, _, _) => Right(Set(Lab(lab)))
      case OrganizationCommand.RenameProject(id, _, _) => Right(Set(Project(id)))
      case OrganizationCommand.ArchiveProject(id, _) => s.projects.get(id).map(p => Set(Project(id), Lab(p.lab))).toRight(Problem.Denied)
      case OrganizationCommand.EndMember(id) => s.memberships.get(id).map(m => Set(Membership(id), Lab(m.lab))).toRight(Problem.Denied)
      case OrganizationCommand.AddParticipant(_, project, _, _, basis, _) => Right(Set(Project(project)) ++ (basis match
        case ParticipationBasis.Independent => Set.empty
        case ParticipationBasis.Membership(id) => Set(Membership(id))))
      case OrganizationCommand.EndParticipant(id) => s.participations.get(id).map(p => Set(Participation(id), Project(p.project))).toRight(Problem.Denied)
      case OrganizationCommand.PlaceNewDataset(_, project, _) => Right(Set(Project(project)))
      case OrganizationCommand.AddSteward(_, dataset, _) => Right(Set(Placement(dataset)))
      case OrganizationCommand.EndSteward(id) => s.stewardships.get(id).map(a => Set(Stewardship(id), Placement(a.dataset))).toRight(Problem.Denied)
      case OrganizationCommand.SetInheritance(dataset, _, _) => parent(s, dataset).map(p => Set(Placement(dataset), Project(p)))
      case OrganizationCommand.DisablePrincipal(id) => Right(Set(Principal(id)))
      case OrganizationCommand.TransferSteward(from, _, _) => s.stewardships.get(from).map(a => Set(Stewardship(from), Placement(a.dataset))).toRight(Problem.Denied)
      case OrganizationCommand.RequestMove(_, dataset, destination, _, _) =>
        parent(s, dataset).map(source => Set(Placement(dataset), Project(source), Project(destination)))
      case OrganizationCommand.ApproveMove(id) => Right(Set(Move(id)))
      case OrganizationCommand.CommitMove(id) => Right(Set(Move(id)))
      case OrganizationCommand.WithdrawMove(id) => Right(Set(Move(id)))
      case OrganizationCommand.RecoverSteward(dataset, _, _, _) => Right(Set(Placement(dataset)))

  private def touch(s: Snapshot, refs: Set[OrganizationRef]): Either[Problem, Snapshot] = refs.toVector.foldLeft[Either[Problem, Snapshot]](Right(s)) { (result, ref) =>
    result.flatMap { current => current.generation(ref).toRight(Problem.Denied).flatMap(_.next).map { generation => ref match
      case OrganizationRef.Principal(id) => current.copy(principals = current.principals.updated(id, current.principals(id).copy(generation = generation)))
      case OrganizationRef.Lab(id) => current.copy(labs = current.labs.updated(id, current.labs(id).copy(generation = generation)))
      case OrganizationRef.Project(id) => current.copy(projects = current.projects.updated(id, current.projects(id).copy(generation = generation)))
      case OrganizationRef.Membership(id) => current.copy(memberships = current.memberships.updated(id, current.memberships(id).copy(generation = generation)))
      case OrganizationRef.Participation(id) => current.copy(participations = current.participations.updated(id, current.participations(id).copy(generation = generation)))
      case OrganizationRef.Placement(id) => current.copy(placements = current.placements.updated(id, current.placements(id).copy(generation = generation)))
      case OrganizationRef.Stewardship(id) => current.copy(stewardships = current.stewardships.updated(id, current.stewardships(id).copy(generation = generation)))
      case OrganizationRef.Link(id) => current.copy(links = current.links.updated(id, current.links(id).copy(generation = generation)))
      case OrganizationRef.Move(id) => current.copy(moves = current.moves.updated(id, current.moves(id).copy(generation = generation)))
    }}
  }

  private def preserveStewards(before: Snapshot, after: Snapshot, now: Instant): Either[Problem, Unit] =
    val old = before.recoveryNeeds(now); val next = after.recoveryNeeds(now)
    conflict(next.labs.subsetOf(old.labs) && next.projects.subsetOf(old.projects) && next.datasets.subsetOf(old.datasets),
      "transition would remove the last eligible administrator or steward")

  private def transition(s: Snapshot, actor: PrincipalId, c: OrganizationCommand, now: Instant): Either[Problem, Snapshot] =
    val initial = OrganizationGeneration.initial
    def principal(id: PrincipalId): Either[Problem, Unit] = require(s.live(id), Problem.Denied)
    def expiry(value: Option[Instant]): Either[Problem, Unit] = conflict(value.forall(now.isBefore), "appointment already expired")
    def activeLab(id: LabId): Either[Problem, Unit] = conflict(s.labs.get(id).exists(_.status == OrganizationStatus.Active), "lab is unavailable or archived")
    def activeProject(id: ProjectId): Either[Problem, Unit] =
      for
        p <- s.projects.get(id).toRight(Problem.Denied)
        _ <- activeLab(p.lab)
        _ <- conflict(p.status == OrganizationStatus.Active, "project is archived")
      yield ()
    c match
      case OrganizationCommand.CreateLab(id, admin, membership, name, description) =>
        for
          _ <- principal(admin)
          _ <- conflict(!s.labs.contains(id) && !s.memberships.contains(membership), "organization identity already exists")
        yield s.copy(labs = s.labs.updated(id, Lab(id, name, description, OrganizationStatus.Active, initial, now)),
          memberships = s.memberships.updated(membership, LabMembership(membership, id, admin, LabRole.Admin, AppointmentStatus.Active, None, initial, now)))
      case OrganizationCommand.RenameLab(id, name, description) =>
        touch(s, Set(OrganizationRef.Lab(id))).map(n => n.copy(labs = n.labs.updated(id, n.labs(id).copy(name = name, description = description))))
      case OrganizationCommand.CreateProject(id, lab, steward, participation, name, description) =>
        for
          _ <- activeLab(lab)
          _ <- principal(steward)
          _ <- conflict(!s.projects.contains(id) && !s.participations.contains(participation), "organization identity already exists")
          n <- touch(s, Set(OrganizationRef.Lab(lab)))
        yield n.copy(projects = n.projects.updated(id, Project(id, lab, name, description, OrganizationStatus.Active, initial, now)),
          participations = n.participations.updated(participation, ProjectParticipation(participation, id, steward,
            OrganizationRole.Steward, ParticipationBasis.Independent, AppointmentStatus.Active, None, initial, now)))
      case OrganizationCommand.RenameProject(id, name, description) =>
        touch(s, Set(OrganizationRef.Project(id))).map(n => n.copy(projects = n.projects.updated(id, n.projects(id).copy(name = name, description = description))))
      case OrganizationCommand.AddMember(id, lab, person, role, expiresAt) =>
        for
          _ <- activeLab(lab)
          _ <- principal(person)
          _ <- expiry(expiresAt)
          _ <- conflict(!s.memberships.contains(id) && !s.memberships.values.exists(m => m.lab == lab && m.principal == person && s.live(m, now)), "membership already exists")
          n <- touch(s, Set(OrganizationRef.Lab(lab)))
        yield n.copy(memberships = n.memberships.updated(id, LabMembership(id, lab, person, role, AppointmentStatus.Active, expiresAt, initial, now)))
      case OrganizationCommand.EndMember(id) =>
        val m = s.memberships(id)
        val derived = s.participations.values.filter(_.basis == ParticipationBasis.Membership(id)).map(p => OrganizationRef.Project(p.project)).toSet
        touch(s, derived ++ Set(OrganizationRef.Membership(id), OrganizationRef.Lab(m.lab)))
          .map(n => n.copy(memberships = n.memberships.updated(id, n.memberships(id).copy(status = AppointmentStatus.Ended))))
      case OrganizationCommand.AddParticipant(id, project, person, role, basis, expiresAt) =>
        for
          _ <- activeProject(project)
          _ <- principal(person)
          _ <- expiry(expiresAt)
          _ <- basis match
            case ParticipationBasis.Independent => Right(())
            case ParticipationBasis.Membership(member) => require(s.memberships.get(member).exists(m => m.principal == person && s.live(m, now)), Problem.Denied)
          _ <- conflict(!s.participations.contains(id) && !s.participations.values.exists(p => p.project == project && p.principal == person && s.live(p, now)), "participation already exists")
          n <- touch(s, Set(OrganizationRef.Project(project)))
        yield n.copy(participations = n.participations.updated(id, ProjectParticipation(id, project, person, role, basis, AppointmentStatus.Active, expiresAt, initial, now)))
      case OrganizationCommand.EndParticipant(id) =>
        touch(s, Set(OrganizationRef.Participation(id), OrganizationRef.Project(s.participations(id).project)))
          .map(n => n.copy(participations = n.participations.updated(id, n.participations(id).copy(status = AppointmentStatus.Ended))))
      case OrganizationCommand.PlaceNewDataset(dataset, project, stewardship) =>
        for
          _ <- activeProject(project)
          _ <- conflict(s.knownDatasets.get(dataset).exists(_.head.isEmpty), "existing accepted data requires the migration protocol")
          _ <- conflict(!s.placements.contains(dataset) && !s.stewardships.contains(stewardship), "dataset is already placed or appointment identity reused")
          n <- touch(s, Set(OrganizationRef.Project(project)))
        yield n.copy(placements = n.placements.updated(dataset, DatasetPlacement(dataset, project, PlacementPolicy.ExplicitOnly, initial, now)),
          stewardships = n.stewardships.updated(stewardship, DatasetStewardship(stewardship, dataset, actor, AppointmentStatus.Active, initial, now)))
      case OrganizationCommand.AddSteward(id, dataset, person) =>
        for
          _ <- principal(person)
          p <- parent(s, dataset)
          _ <- activeProject(p)
          _ <- conflict(!s.stewardships.contains(id) && !s.stewardships.values.exists(a => a.dataset == dataset && a.principal == person && a.status == AppointmentStatus.Active), "steward appointment already exists")
          n <- touch(s, Set(OrganizationRef.Placement(dataset)))
        yield n.copy(stewardships = n.stewardships.updated(id, DatasetStewardship(id, dataset, person, AppointmentStatus.Active, initial, now)))
      case OrganizationCommand.EndSteward(id) =>
        touch(s, Set(OrganizationRef.Stewardship(id), OrganizationRef.Placement(s.stewardships(id).dataset)))
          .map(n => n.copy(stewardships = n.stewardships.updated(id, n.stewardships(id).copy(status = AppointmentStatus.Ended))))
      case OrganizationCommand.SetInheritance(dataset, policy, confirmed) =>
        for
          project <- parent(s, dataset)
          _ <- activeProject(project)
          _ <- conflict(confirmed == s.impact(project, now), "role inheritance impact has changed")
          n <- touch(s, Set(OrganizationRef.Placement(dataset)))
        yield n.copy(placements = n.placements.updated(dataset, n.placements(dataset).copy(policy = policy)))
      case OrganizationCommand.ArchiveProject(id, archived) =>
        for
          _ <- if archived then conflict(s.activeWork.getOrElse(id, Set.empty).isEmpty, "project has active work") else activeLab(s.projects(id).lab)
          n <- touch(s, Set(OrganizationRef.Project(id), OrganizationRef.Lab(s.projects(id).lab)))
        yield n.copy(projects = n.projects.updated(id, n.projects(id).copy(status = if archived then OrganizationStatus.Archived else OrganizationStatus.Active)))
      case OrganizationCommand.ArchiveLab(id, archived) =>
        for
          _ <- conflict(!archived || s.projects.values.filter(_.lab == id).forall(_.status == OrganizationStatus.Archived), "lab has active projects")
          n <- touch(s, Set(OrganizationRef.Lab(id)))
        yield n.copy(labs = n.labs.updated(id, n.labs(id).copy(status = if archived then OrganizationStatus.Archived else OrganizationStatus.Active)))
      case OrganizationCommand.TransferSteward(from, to, person) =>
        val giving = s.stewardships(from)
        for
          _ <- conflict(giving.status == AppointmentStatus.Active, "stewardship already ended")
          _ <- principal(person)
          _ <- activeProject(s.placements(giving.dataset).project)
          _ <- conflict(!s.stewardships.contains(to) && !s.stewardships.values.exists(a => a.dataset == giving.dataset &&
            a.principal == person && a.status == AppointmentStatus.Active), "receiving steward already appointed")
          n <- touch(s, Set(OrganizationRef.Stewardship(from), OrganizationRef.Placement(giving.dataset)))
        yield n.copy(stewardships = n.stewardships.updated(from, n.stewardships(from).copy(status = AppointmentStatus.Ended))
          .updated(to, DatasetStewardship(to, giving.dataset, person, AppointmentStatus.Active, initial, now)))
      case OrganizationCommand.RequestMove(id, dataset, destination, policy, keepLink) =>
        for
          source <- parent(s, dataset)
          _ <- activeProject(source)
          _ <- activeProject(destination)
          _ <- conflict(source != destination, "dataset is already in that project")
          _ <- conflict(!s.moves.contains(id) && !s.moves.values.exists(m => m.dataset == dataset && m.status == MoveStatus.Open),
            "an open move request already exists for this dataset")
          _ <- conflict(keepLink.forall(l => !s.links.contains(l) && !s.links.values.exists(x => x.project == source && x.dataset == dataset)),
            "link identity reused or link already present")
          frozen = Set[OrganizationRef](OrganizationRef.Placement(dataset), OrganizationRef.Project(source), OrganizationRef.Project(destination))
            .map(r => r -> s.generation(r).get).toMap
          approvals = s.moveRoles(dataset, source, destination, actor, now).map(_ -> actor).toMap
        yield s.copy(moves = s.moves.updated(id, DatasetMoveRequest(id, dataset, source, destination, policy, keepLink, frozen,
          approvals, actor, MoveStatus.Open, initial, now)))
      case OrganizationCommand.ApproveMove(id) =>
        val m = s.moves(id)
        val roles = s.moveRoles(m.dataset, m.source, m.destination, actor, now) -- m.approvals.keySet
        for
          _ <- conflict(m.status == MoveStatus.Open, "move request is closed")
          _ <- conflict(m.frozen.forall((r, g) => s.generation(r).contains(g)), "placement or project changed; request the move again")
          _ <- conflict(roles.nonEmpty, "no further approval is needed from you")
          n <- touch(s, Set(OrganizationRef.Move(id)))
        yield n.copy(moves = n.moves.updated(id, n.moves(id).copy(approvals = m.approvals ++ roles.map(_ -> actor))))
      case OrganizationCommand.CommitMove(id) =>
        val m = s.moves(id)
        for
          _ <- conflict(m.status == MoveStatus.Open, "move request is closed")
          _ <- conflict(m.frozen.forall((r, g) => s.generation(r).contains(g)), "placement or project changed; request the move again")
          _ <- conflict(MoveRole.values.forall(role => m.approvals.get(role).exists(p =>
            s.moveRoles(m.dataset, m.source, m.destination, p, now)(role))), "every required steward must currently approve")
          _ <- activeProject(m.source)
          _ <- activeProject(m.destination)
          n <- touch(s, Set(OrganizationRef.Move(id), OrganizationRef.Placement(m.dataset),
            OrganizationRef.Project(m.source), OrganizationRef.Project(m.destination)))
        yield
          val placed = n.placements.updated(m.dataset, n.placements(m.dataset).copy(project = m.destination, policy = m.policy))
          val linked = m.keepLink.fold(n.links)(l => n.links.updated(l, ProjectDatasetLink(l, m.source, m.dataset, initial, now)))
          n.copy(placements = placed, links = linked, moves = n.moves.updated(id, n.moves(id).copy(status = MoveStatus.Committed)))
      case OrganizationCommand.WithdrawMove(id) =>
        for
          _ <- conflict(s.moves(id).status == MoveStatus.Open, "move request is closed")
          n <- touch(s, Set(OrganizationRef.Move(id)))
        yield n.copy(moves = n.moves.updated(id, n.moves(id).copy(status = MoveStatus.Withdrawn)))
      case OrganizationCommand.RecoverSteward(dataset, stewardship, person, _) =>
        for
          _ <- principal(person)
          _ <- conflict(s.placements.contains(dataset), "dataset is not placed")
          _ <- conflict(s.recoveryNeeds(now).datasets(dataset), "the dataset still has an eligible steward")
          _ <- conflict(!s.stewardships.contains(stewardship), "appointment identity reused")
          n <- touch(s, Set(OrganizationRef.Placement(dataset)))
        yield n.copy(stewardships = n.stewardships.updated(stewardship, DatasetStewardship(stewardship, dataset, person, AppointmentStatus.Active, initial, now)))
      case OrganizationCommand.DisablePrincipal(id) =>
        val labs = s.memberships.values.filter(_.principal == id).map(m => OrganizationRef.Lab(m.lab)).toSet
        val projects = s.participations.values.filter(_.principal == id).map(p => OrganizationRef.Project(p.project)).toSet
        val placements = s.stewardships.values.filter(_.principal == id).map(a => OrganizationRef.Placement(a.dataset)).toSet
        touch(s, labs ++ projects ++ placements + OrganizationRef.Principal(id))
          .map(n => n.copy(principals = n.principals.updated(id, n.principals(id).copy(active = false))))
