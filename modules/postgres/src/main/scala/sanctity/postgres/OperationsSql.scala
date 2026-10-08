package sanctity.postgres

import cats.syntax.all.*
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*
import doobie.postgres.implicits.*
import cats.effect.Async
import sanctity.application.Failure
import sanctity.kernel.*
import java.util.UUID
import java.sql.Timestamp

private[postgres] object OperationsSql:
  def active: ConnectionIO[Unit] =
    sql"SELECT recovery_required FROM repository_state WHERE singleton=TRUE FOR SHARE".query[Boolean].unique
      .flatMap(required => Records.lift(Either.cond(!required, (), Problem.Conflict("repository requires recovery reconciliation"))))

  def duration(seconds: Int): ConnectionIO[Unit] =
    Records.lift(Either.cond(seconds >= 1 && seconds <= 3600, (), Problem.LimitExceeded("lease duration must be 1–3600 seconds")))

  def run[F[_]: Async, A](xa: Transactor[F])(program: ConnectionIO[A]): F[Either[Problem, A]] =
    program.transact(xa).attempt.map(_.left.map {
      case Failure(problem) => problem
      case _ => Problem.Operational("durable operation transaction failed")
    })

  def fence(lease: WorkLease): ConnectionIO[Unit] =
    sql"""SELECT EXISTS(SELECT 1 FROM import_operation WHERE id=${Records.uuid(lease.operation)}
      AND lease_token=${Records.uuid(lease.token)} AND lease_until > clock_timestamp())""".query[Boolean].unique
      .flatMap(valid => Records.lift(Either.cond(valid, (), Problem.Conflict("expired or superseded worker lease"))))

  def fenceCandidate(candidate: UUID, lease: Option[WorkLease]): ConnectionIO[Unit] =
    sql"SELECT id FROM import_operation WHERE candidate_id=$candidate FOR UPDATE".query[UUID].option.flatMap {
      case None => Records.lift(Either.cond(lease.isEmpty, (), Problem.Conflict("candidate is not bound to this operation")))
      case Some(id) => lease match
        case Some(value) if Records.uuid(value.operation) == id => fence(value)
        case _ => Records.lift(Left(Problem.Conflict("durable import acceptance requires its current worker lease")))
    }

  def until(seconds: Int): ConnectionIO[Timestamp] =
    sql"SELECT clock_timestamp() + $seconds * interval '1 second'".query[Timestamp].unique
