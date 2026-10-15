package sanctity.kernel

import java.time.Instant

sealed trait LabTag
sealed trait ProjectTag
sealed trait LabMembershipTag
sealed trait ProjectParticipationTag
sealed trait DatasetStewardshipTag
sealed trait ProjectDatasetLinkTag
type LabId = Id[LabTag]
type ProjectId = Id[ProjectTag]
type LabMembershipId = Id[LabMembershipTag]
type ProjectParticipationId = Id[ProjectParticipationTag]
type DatasetStewardshipId = Id[DatasetStewardshipTag]
type ProjectDatasetLinkId = Id[ProjectDatasetLinkTag]

opaque type OrganizationName = String
object OrganizationName:
  def from(value: String): Either[Problem, OrganizationName] =
    Either.cond(value.codePoints().anyMatch(c => !Character.isWhitespace(c) && !Character.isSpaceChar(c)) && value.codePointCount(0, value.length) <= 200 &&
      !value.codePoints().anyMatch(c => Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff), value,
      Problem.Unsupported("organization name requires 1–200 code points without controls"))
  extension (name: OrganizationName) def value: String = name

opaque type OrganizationDescription = String
object OrganizationDescription:
  def from(value: String): Either[Problem, OrganizationDescription] =
    Either.cond(value.codePointCount(0, value.length) <= 4000 &&
      !value.codePoints().anyMatch(c => Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff), value,
      Problem.Unsupported("organization description exceeds 4000 code points or contains controls"))
  extension (description: OrganizationDescription) def value: String = description

opaque type OrganizationGeneration = Long
object OrganizationGeneration:
  val initial: OrganizationGeneration = 0L
  def from(value: Long): Either[Problem, OrganizationGeneration] =
    Either.cond(value >= 0, value, Problem.Unsupported("negative organization generation"))
  extension (generation: OrganizationGeneration)
    def value: Long = generation
    def next: Either[Problem, OrganizationGeneration] =
      Either.cond(generation < Long.MaxValue, generation + 1, Problem.Conflict("organization generation exhausted"))

enum OrganizationRole:
  case Reader, Contributor, Steward
enum LabRole:
  case Member, Admin
enum OrganizationStatus:
  case Active, Archived
enum AppointmentStatus:
  case Active, Ended
enum PlacementPolicy:
  case ExplicitOnly, ProjectMembers
enum ParticipationBasis:
  case Independent
  case Membership(id: LabMembershipId)

/** Directory state is independent of immutable scientific records and of login bindings. */
final case class OrganizationPrincipal(id: PrincipalId, label: OrganizationName, active: Boolean,
    generation: OrganizationGeneration, createdAt: Instant)
final case class Lab(id: LabId, name: OrganizationName, description: OrganizationDescription,
    status: OrganizationStatus, generation: OrganizationGeneration, createdAt: Instant)
final case class Project(id: ProjectId, lab: LabId, name: OrganizationName, description: OrganizationDescription,
    status: OrganizationStatus, generation: OrganizationGeneration, createdAt: Instant)
final case class LabMembership(id: LabMembershipId, lab: LabId, principal: PrincipalId, role: LabRole,
    status: AppointmentStatus, expiresAt: Option[Instant], generation: OrganizationGeneration, createdAt: Instant)
final case class ProjectParticipation(id: ProjectParticipationId, project: ProjectId, principal: PrincipalId,
    role: OrganizationRole, basis: ParticipationBasis, status: AppointmentStatus, expiresAt: Option[Instant],
    generation: OrganizationGeneration, createdAt: Instant)
final case class DatasetPlacement(dataset: DatasetId, project: ProjectId, policy: PlacementPolicy,
    generation: OrganizationGeneration, createdAt: Instant)
final case class DatasetStewardship(id: DatasetStewardshipId, dataset: DatasetId, principal: PrincipalId,
    status: AppointmentStatus, generation: OrganizationGeneration, createdAt: Instant)
final case class ProjectDatasetLink(id: ProjectDatasetLinkId, project: ProjectId, dataset: DatasetId,
    generation: OrganizationGeneration, createdAt: Instant)

enum OrganizationRef:
  case Principal(id: PrincipalId)
  case Lab(id: LabId)
  case Project(id: ProjectId)
  case Membership(id: LabMembershipId)
  case Participation(id: ProjectParticipationId)
  case Placement(id: DatasetId)
  case Stewardship(id: DatasetStewardshipId)
  case Link(id: ProjectDatasetLinkId)

/** A frozen role-inheritance impact; F02.3/F03 join explicit grants and disclosure actions. */
final case class OrganizationAccessImpact(project: ProjectId, generation: OrganizationGeneration,
    recipients: Map[PrincipalId, OrganizationRole])

enum OrganizationCommand:
  case CreateLab(id: LabId, admin: PrincipalId, membership: LabMembershipId,
      name: OrganizationName, description: OrganizationDescription)
  case RenameLab(id: LabId, name: OrganizationName, description: OrganizationDescription)
  case CreateProject(id: ProjectId, lab: LabId, steward: PrincipalId, participation: ProjectParticipationId,
      name: OrganizationName, description: OrganizationDescription)
  case RenameProject(id: ProjectId, name: OrganizationName, description: OrganizationDescription)
  case AddMember(id: LabMembershipId, lab: LabId, principal: PrincipalId, role: LabRole, expiresAt: Option[Instant])
  case EndMember(id: LabMembershipId)
  case AddParticipant(id: ProjectParticipationId, project: ProjectId, principal: PrincipalId,
      role: OrganizationRole, basis: ParticipationBasis, expiresAt: Option[Instant])
  case EndParticipant(id: ProjectParticipationId)
  case PlaceNewDataset(dataset: DatasetId, project: ProjectId, stewardship: DatasetStewardshipId)
  case AddSteward(id: DatasetStewardshipId, dataset: DatasetId, principal: PrincipalId)
  case EndSteward(id: DatasetStewardshipId)
  case SetInheritance(dataset: DatasetId, policy: PlacementPolicy, confirmedImpact: OrganizationAccessImpact)
  case ArchiveProject(id: ProjectId, archived: Boolean)
  case ArchiveLab(id: LabId, archived: Boolean)
  case DisablePrincipal(id: PrincipalId)

final case class OrganizationMutation(key: IdempotencyKey, expected: Map[OrganizationRef, OrganizationGeneration],
    command: OrganizationCommand)
/** Historical receipts retain LocalOnly; Durable requires a verified independent COMMIT. */
enum PolicyDurability:
  case LocalOnly, Pending, Durable

final case class OrganizationReceipt(sequence: Long, actor: PrincipalId, key: IdempotencyKey,
    affected: Set[OrganizationRef], at: Instant, durability: PolicyDurability = PolicyDurability.LocalOnly)
final case class OrganizationAudit(receipt: OrganizationReceipt, command: OrganizationCommand)
