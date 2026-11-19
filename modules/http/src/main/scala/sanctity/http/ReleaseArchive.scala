package sanctity.http

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.zip.{Deflater, ZipEntry, ZipOutputStream}

/** One accepted release as a zip that can be checked after it is unpacked (SHARE2).
  *
  * The archive holds one folder with the release's files and directories exactly as released, and beside it a
  * `SHA256SUMS.txt` that lists every file's digest, so `shasum -a 256 -c SHA256SUMS.txt` verifies the unpacked copy
  * and the released tree itself stays clean. Entries are stored without compression: imaging data is usually
  * compressed already, and nothing is held in memory. The same release always produces the same bytes.
  *
  * The central directory is written only after every file was read in full. If reading fails part-way, as it does
  * when access is revoked during a download, the stream fails and what was sent is not a valid archive.
  */
object ReleaseArchive:
  val Checksums = "SHA256SUMS.txt"

  def root(ref: ReleaseRef): String = s"sanctity-${ref.dataset.render.take(8)}-${ref.release.render.take(8)}"

  private def ordered(manifest: Manifest): Vector[Entry.File] = manifest.files.sortBy(_.path.value)

  /** One line per file in the format `shasum` and `sha256sum` check: digest, two spaces, path below the archive root. */
  def checksums(root: String, manifest: Manifest): String =
    ordered(manifest).map(f => s"${f.blob.digest.value}  $root/${f.path.value}\n").mkString

  def zip[F[_]](root: String, manifest: Manifest, acceptedAt: Instant, read: Entry.File => Stream[F, Byte])(using F: Async[F]): Stream[F, Byte] =
    val time = LocalDateTime.ofInstant(acceptedAt, ZoneOffset.UTC)
    fs2.io.readOutputStream[F](64 * 1024) { out =>
      F.blocking { val z = new ZipOutputStream(out, UTF_8); z.setLevel(Deflater.NO_COMPRESSION); z }.flatMap { z =>
        def entry(name: String)(content: F[Unit]): F[Unit] =
          F.blocking { val e = new ZipEntry(name); e.setTimeLocal(time); z.putNextEntry(e) } *> content *> F.blocking(z.closeEntry())
        for
          _ <- entry(Checksums)(F.blocking(z.write(checksums(root, manifest).getBytes(UTF_8))))
          _ <- entry(root + "/")(F.unit)
          _ <- manifest.directories.sortBy(_.value).traverse_(d => entry(s"$root/${d.value}/")(F.unit))
          _ <- ordered(manifest).traverse_(f => entry(s"$root/${f.path.value}")(
            read(f).through(fs2.io.writeOutputStream(F.pure(z: java.io.OutputStream), closeAfterUse = false)).compile.drain))
          // Only a complete archive gets its central directory.
          _ <- F.blocking(z.finish())
        yield ()
      }
    }
