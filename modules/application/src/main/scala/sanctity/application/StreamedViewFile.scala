package sanctity.application

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*
import java.security.MessageDigest

/** A generated file consumed by BlobStore.put, never materialized as a document.
  * Its path must also occur in the ordinary plan's evidence and projection steps.
  */
final case class StreamedViewFile[F[_]](path: RelPath, bytes: Stream[F, Byte])

private[application] object ViewStream:
  val ChunkSize: Int = 64 * 1024
  val FileLimit: Long = 2L * 1024 * 1024 * 1024
  val TotalLimit: Long = 8L * 1024 * 1024 * 1024

  def bounded[F[_]: Async](bytes: Stream[F, Byte], limit: Long): Stream[F, Byte] =
    Stream.eval(cats.effect.Ref.of[F, Long](0L)).flatMap { count =>
      bytes.chunkN(ChunkSize).evalMap { chunk =>
        count.updateAndGet(n => Math.addExact(n, chunk.size.toLong)).flatMap { size =>
          Checked.lift[F, Unit](Either.cond(size <= limit, (), Problem.LimitExceeded("streamed view file exceeds its byte budget"))).as(chunk)
        }
      }.flatMap(Stream.chunk)
    }

  /** Verify even stores supplied by other adapters; EOF is part of success. */
  def verified[F[_]: Async](bytes: Stream[F, Byte], blob: BlobRef): Stream[F, Byte] =
    Stream.eval(Async[F].delay(MessageDigest.getInstance("SHA-256"))).flatMap { md =>
      Stream.eval(cats.effect.Ref.of[F, Long](0L)).flatMap { count =>
        bytes.chunkN(ChunkSize).evalTap { c =>
          count.updateAndGet(n => Math.addExact(n, c.size.toLong)).flatMap { size =>
            Checked.lift[F, Unit](Either.cond(size <= blob.size, (), Problem.Integrity("streamed view input exceeds its source blob size"))) *>
              Async[F].delay(md.update(c.toArray))
          }
        }.unchunks ++ Stream.exec {
          count.get.flatMap { size =>
            Async[F].delay(Digest.from(md.digest().map(b => f"${b & 0xff}%02x").mkString)).flatMap { actual =>
              Checked.lift[F, Unit](Either.cond(size == blob.size && actual.contains(blob.digest), (), Problem.Integrity("streamed view input differs from its source blob")))
            }
          }
        }
      }
    }
