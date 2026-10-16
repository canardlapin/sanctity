package sanctity.application

import sanctity.kernel.*

/** Governed organization administration as the public API sees it (F02.3). Reads are projected by current authority
  * (ADR 002); every mutation is authorized by the shared decision function in the same transaction as its effects,
  * its event and its retry receipt. Snapshot and lease access stay trusted adapter methods, not part of this surface.
  */
trait OrganizationService[F[_]]:
  def execute(ctx: AccessContext, request: OrganizationMutation): F[Either[Problem, OrganizationReceipt]]
  /** An empty dataset in an active project, with ExplicitOnly placement and the caller's explicit stewardship, created
    * atomically by a project contributor or steward for a custodian already recognized by this installation.
    */
  def createDataset(ctx: AccessContext, custodian: CustodianId, project: ProjectId, key: IdempotencyKey,
      expectedProject: OrganizationGeneration): F[Either[Problem, (Dataset, OrganizationReceipt)]]
  /** Recipients whose roles would gain dataset actions under ProjectMembers; current explicit stewards only. */
  def inheritanceImpact(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, OrganizationAccessImpact]]
  def lab(ctx: AccessContext, id: LabId): F[Either[Problem, OrganizationService.LabView]]
  def project(ctx: AccessContext, id: ProjectId): F[Either[Problem, OrganizationService.ProjectView]]
  /** A dataset's placement and stewardship appointments, for its current explicit stewards. */
  def placement(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, OrganizationService.PlacementView]]
  /** A move request and its access-impact preview, for its requester and current holders of a required role. */
  def move(ctx: AccessContext, id: MoveRequestId): F[Either[Problem, OrganizationService.MoveView]]

object OrganizationService:
  /** `roster` is present only for the lab's current administrators. */
  final case class LabView(lab: Lab, roster: Option[Vector[LabMembership]])
  /** `roster` is present only for the project's current stewards. */
  final case class ProjectView(project: Project, roster: Option[Vector[ProjectParticipation]])
  final case class PlacementView(placement: DatasetPlacement, stewards: Vector[DatasetStewardship])
  /** `lost`: project roles whose inherited access ends; `gained`: roles that inherit at the destination; explicit
    * stewards and `grants` (unrevoked shares) survive the move unless separately ended or revoked.
    */
  final case class MoveView(request: DatasetMoveRequest, yourRoles: Set[MoveRole], lost: Map[PrincipalId, OrganizationRole],
      gained: Map[PrincipalId, OrganizationRole], stewards: Vector[DatasetStewardship], grants: Int)
