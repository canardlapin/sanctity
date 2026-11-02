package sanctity.application

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.Problem
import scala.concurrent.duration.*

/** Admission for continuing work, independent of the lifetime of its original request.
  * A slow or unavailable authority service cannot extend the last execution permit.
  */
object CurrentAuthority:
  val ChunkBytes: Int = 64 * 1024
  val ExecutionBound: FiniteDuration = 5.seconds
  private val PollInterval: FiniteDuration = 1.second

  def bytes[F[_]](source: Stream[F, Byte], authorize: F[Unit]): Stream[F, Byte] =
    source.chunkN(ChunkBytes).evalTap(_ => authorize).unchunks

  def during[F[_]: Async, A](authorize: F[Unit])(work: F[A]): F[A] =
    val F = Async[F]
    def expired[B]: F[B] = F.raiseError(Failure(Problem.Operational("execution authority lease expired")))
    def bounded[B](check: F[B], limit: FiniteDuration): F[B] =
      // A database driver may ignore cancellation. Retire its result at the
      // deadline without waiting for that driver to acknowledge cancellation.
      check.timeoutAndForget(limit).recoverWith {
        case _: java.util.concurrent.TimeoutException => expired[B]
      }
    // Charge authorization latency to the permit rather than starting a fresh five
    // seconds after a slow query. The monitor fails closed at the previous deadline.
    def monitor(admittedAt: FiniteDuration): F[A] =
      F.monotonic.flatMap { now =>
        val remaining = ExecutionBound - (now - admittedAt)
        if remaining <= Duration.Zero then expired[A]
        else
          bounded(F.sleep(PollInterval.min(remaining)) *> F.monotonic.flatMap { started =>
            authorize.as(started)
          }, remaining).flatMap(monitor)
      }
    for
      started <- F.monotonic
      _ <- bounded(authorize, ExecutionBound)
      result <- F.race(work, monitor(started)).map(_.merge)
    yield result
