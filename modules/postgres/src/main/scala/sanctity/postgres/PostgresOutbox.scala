package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import sanctity.application.{IdSource, Outbox}
import sanctity.kernel.*
import java.util.UUID

/** One independent delivery cursor per target. Sinks deduplicate (sequence,target). */
final class PostgresOutbox[F[_]: Async] private[postgres](
    xa: Transactor[F], catalog: PostgresCatalog[F], ids: IdSource[F]
) extends Outbox[F]:
  import Records.{lift, uuid}

  def claim(ctx: AccessContext, dataset: DatasetId, target: String, seconds: Int): F[Either[Problem, Option[EventLease]]] =
    ids.next[LeaseTag].flatMap { token => OperationsSql.run(xa) {
      for
        _ <- catalog.authorize(ctx, uuid(dataset), false, Action.ReadMetadata)
        _ <- OperationsSql.duration(seconds)
        _ <- lift(Either.cond(target.matches("[A-Za-z0-9_.-]{1,100}"), (), Problem.InvalidIdentifier(target)))
        _ <- sql"""INSERT INTO outbox_delivery(sequence,target) SELECT sequence,$target FROM outbox
          WHERE dataset_id=${uuid(dataset)} ON CONFLICT DO NOTHING""".update.run
        next <- sql"""SELECT d.sequence,o.release_id FROM outbox_delivery d JOIN outbox o ON o.sequence=d.sequence
          WHERE o.dataset_id=${uuid(dataset)} AND d.target=$target AND d.delivered_at IS NULL
            AND (d.lease_until IS NULL OR d.lease_until <= clock_timestamp())
          ORDER BY d.sequence LIMIT 1 FOR UPDATE OF d SKIP LOCKED""".query[(Long, UUID)].option
        result <- next.traverse { (sequence, release) =>
          for
            until <- OperationsSql.until(seconds)
            _ <- sql"""UPDATE outbox_delivery SET lease_token=${uuid(token)},lease_until=$until,attempts=attempts+1
              WHERE sequence=$sequence AND target=$target""".update.run
          yield EventLease(sequence, target, token, ReleaseRef(dataset, Id[ReleaseTag](release)), until.toInstant)
        }
      yield result
    }}

  def acknowledge(ctx: AccessContext, lease: EventLease): F[Either[Problem, Unit]] = OperationsSql.run(xa) {
    for
      _ <- catalog.authorize(ctx, uuid(lease.release.dataset), false, Action.ReadMetadata, Some(lease.release.release))
      changed <- sql"""UPDATE outbox_delivery d SET delivered_at=clock_timestamp(),lease_token=NULL,lease_until=NULL
        FROM outbox o WHERE d.sequence=o.sequence AND o.dataset_id=${uuid(lease.release.dataset)}
          AND o.release_id=${uuid(lease.release.release)} AND d.sequence=${lease.sequence} AND d.target=${lease.target}
          AND d.lease_token=${uuid(lease.token)} AND d.lease_until > clock_timestamp() AND d.delivered_at IS NULL""".update.run
      _ <- lift(Either.cond(changed == 1, (), Problem.Conflict("expired, completed or superseded delivery")))
    yield ()
  }
