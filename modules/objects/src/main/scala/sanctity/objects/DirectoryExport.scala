package sanctity.objects

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked, ExportTarget}
import sanctity.kernel.*

import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}

/** Copies verified bytes into an isolated, new destination. Existing paths are never reused. */
final class DirectoryExport[F[_]](destination: Path)(using F: Async[F]) extends ExportTarget[F]:
  def materialize(manifest: Manifest, store: BlobStore[F]): F[ExportReceipt] =
    val target = destination.toAbsolutePath.normalize
    for
      _ <- F.blocking(Files.createDirectories(target.getParent))
      exists <- F.blocking(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
      _ <- Checked.lift[F, Unit](Either.cond(!exists, (), Problem.Conflict("export destination already exists")))
      receipt <- LocalFiles.tempDirectory[F](target.getParent, ".sanctity-export-").use { staging =>
        for
          _ <- manifest.directories.sortBy(_.value.count(_ == '/')).traverse_ { path =>
            F.blocking(Files.createDirectory(staging.resolve(path.value))).void
          }
          _ <- manifest.files.traverse_ { file =>
            store.read(file.blob).through(fs2.io.writeOutputStream(F.blocking(
              Files.newOutputStream(staging.resolve(file.path.value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            ))).compile.drain
          }
          // A single rename publishes the complete tree on this filesystem.
          _ <- F.blocking(Files.move(staging, target))
        yield ExportReceipt(manifest.id, manifest.files.size, manifest.directories.size, manifest.files.map(_.blob.size).sum)
      }
    yield receipt
