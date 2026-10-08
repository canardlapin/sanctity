package sanctity.application

import sanctity.kernel.*

final case class ImportOperation(id: OperationId, dataset: DatasetId, owner: PrincipalId, key: IdempotencyKey,
    source: String, inventory: Vector[SourceEntry], acquired: Vector[Entry.File],
    candidate: Option[CandidateId], receipt: Option[AcceptanceReceipt])

trait ImportJournal[F[_]]:
  def begin(ctx: AccessContext, dataset: DatasetId, key: IdempotencyKey, source: String, inventory: Vector[SourceEntry]): F[Either[Problem, ImportOperation]]
  def get(ctx: AccessContext, id: OperationId): F[Either[Problem, ImportOperation]]
  def claim(ctx: AccessContext, id: OperationId, seconds: Int): F[Either[Problem, WorkLease]]
  def renew(ctx: AccessContext, lease: WorkLease, seconds: Int): F[Either[Problem, WorkLease]]
  def record(ctx: AccessContext, lease: WorkLease, file: Entry.File): F[Either[Problem, Entry.File]]
  def bind(ctx: AccessContext, lease: WorkLease, candidate: CandidateId): F[Either[Problem, Unit]]
  def finish(ctx: AccessContext, lease: WorkLease, receipt: AcceptanceReceipt): F[Either[Problem, Unit]]
  def surrender(ctx: AccessContext, lease: WorkLease): F[Unit]

trait Outbox[F[_]]:
  def claim(ctx: AccessContext, dataset: DatasetId, target: String, seconds: Int): F[Either[Problem, Option[EventLease]]]
  def acknowledge(ctx: AccessContext, lease: EventLease): F[Either[Problem, Unit]]
