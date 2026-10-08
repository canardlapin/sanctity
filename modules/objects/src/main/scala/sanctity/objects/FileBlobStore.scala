package sanctity.objects

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.{BlobStore, Checked, Failure}
import sanctity.kernel.*

import java.nio.channels.FileChannel
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest

/** Content-addressed local storage. Only this adapter may mutate its private root. */
final class FileBlobStore[F[_]] private (root: Path)(using F: Async[F]) extends BlobStore[F]:
  private def location(digest: Digest): Path = root.resolve("objects").resolve(digest.value.take(2)).resolve(digest.value)
  private def digest(bytes: Array[Byte]): Digest =
    Digest.from(bytes.map(b => f"${b & 0xff}%02x").mkString).fold(p => throw Failure(p), identity)

  def put(bytes: Stream[F, Byte]): F[BlobRef] =
    Resource.make(F.blocking(Files.createTempFile(root.resolve("staging"), "blob-", ".part")))(
      path => F.blocking(Files.deleteIfExists(path)).void
    ).use { temporary =>
      for
        measured <- Resource.fromAutoCloseable(F.blocking(Files.newOutputStream(temporary))).use { output =>
          F.delay(MessageDigest.getInstance("SHA-256")).flatMap { md =>
            bytes.chunks.evalMap { chunk =>
              F.blocking {
                val array = chunk.toArray
                output.write(array)
                md.update(array)
                array.length.toLong
              }
            }.compile.fold(0L)(Math.addExact).flatMap { size =>
              F.delay(digest(md.digest())).flatMap(hash => Checked.lift[F, BlobRef](BlobRef.from(hash, size)))
            }
          }
        }
        _ <- F.blocking(FileBlobStore.force(temporary, false))
        destination = location(measured.digest)
        _ <- F.blocking(Files.createDirectories(destination.getParent))
        _ <- F.blocking {
          try
            val _ = Files.move(temporary, destination)
          catch case _: FileAlreadyExistsException => ()
        }
        _ <- F.blocking {
          FileBlobStore.force(destination, false)
          FileBlobStore.force(destination.getParent, true)
          FileBlobStore.force(root.resolve("objects"), true)
        }
        _ <- verify(measured)
      yield measured
    }

  def read(blob: BlobRef): Stream[F, Byte] =
    Stream.eval(F.delay(MessageDigest.getInstance("SHA-256"))).flatMap { md =>
      // State belongs to a fresh stream evaluation, never to the reusable BlobStore.
      Stream.eval(cats.effect.Ref.of[F, Long](0L)).flatMap { count =>
        val content = fs2.io.readInputStream(F.blocking {
          val path = location(blob.digest)
          if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then throw Failure(Problem.Integrity("missing blob"))
          if !LocalFiles.attributes(path).isRegularFile then throw Failure(Problem.Integrity("blob is not a regular file"))
          Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
        }, 64 * 1024)
        content.chunks.evalMap { chunk =>
          count.updateAndGet(n => Math.addExact(n, chunk.size.toLong)).flatMap { size =>
            Checked.lift[F, Unit](Either.cond(size <= blob.size, (), Problem.Integrity("blob exceeds recorded size"))) *>
              F.delay(md.update(chunk.toArray)).as(chunk)
          }
        }.flatMap(Stream.chunk) ++ Stream.exec {
          count.get.flatMap { size =>
            F.delay(digest(md.digest())).flatMap { actual =>
              Checked.lift[F, Unit](Either.cond(size == blob.size && actual == blob.digest, (), Problem.Integrity("blob digest or size mismatch")))
            }
          }
        }
      }
    }

  def verify(blob: BlobRef): F[Unit] = read(blob).compile.drain

object FileBlobStore:
  private def force(path: Path, directory: Boolean): Unit =
    val mode = if directory then StandardOpenOption.READ else StandardOpenOption.WRITE
    val channel = FileChannel.open(path, mode, LinkOption.NOFOLLOW_LINKS)
    try channel.force(true)
    finally channel.close()

  def open[F[_]: Async](root: Path): F[FileBlobStore[F]] =
    Async[F].blocking {
      val _ = Files.createDirectories(root.resolve("objects"))
      val _ = Files.createDirectories(root.resolve("staging"))
      val absolute = root.toAbsolutePath.normalize
      force(absolute.resolve("objects"), true)
      force(absolute.resolve("staging"), true)
      force(absolute, true)
      force(absolute.getParent, true)
      new FileBlobStore[F](absolute)
    }
