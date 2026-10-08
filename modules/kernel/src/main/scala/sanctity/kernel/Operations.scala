package sanctity.kernel

import java.time.Instant

sealed trait OperationTag
type OperationId = Id[OperationTag]
sealed trait LeaseTag
type LeaseId = Id[LeaseTag]

final case class WorkLease(operation: OperationId, token: LeaseId, expiresAt: Instant)
final case class EventLease(sequence: Long, target: String, token: LeaseId, release: ReleaseRef, expiresAt: Instant)
