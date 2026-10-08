package sanctity.objects

import cats.effect.{Async, Resource}
import fs2.Stream
import sanctity.application.{Failure, NativeSource, SourceEntry}
import sanctity.kernel.*

import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes

private[objects] object LocalFiles:
  def deleteTree[F[_]: Async](root: Path): F[Unit] = Async[F].blocking {
    if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      val walk = Files.walk(root)
      try
        val iterator = walk.sorted(java.util.Comparator.reverseOrder[Path]()).iterator()
        while iterator.hasNext do
          val _ = Files.deleteIfExists(iterator.next())
      finally walk.close()
  }

  def tempDirectory[F[_]: Async](parent: Path, prefix: String): Resource[F, Path] =
    Resource.make(Async[F].blocking(Files.createTempDirectory(parent, prefix)))(deleteTree[F])

  def attributes(path: Path): BasicFileAttributes =
    val attrs = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    if !attrs.isRegularFile && !attrs.isDirectory then
      throw Failure(Problem.Unsupported(s"only regular files and directories are supported: $path"))
    attrs

  def observation(attrs: BasicFileAttributes): String =
    s"${attrs.size()}:${attrs.lastModifiedTime()}:${Option(attrs.fileKey()).fold("")(_.toString)}"

/** Inventories a quiescent local directory; before/after observations detect ordinary mutation.
  * This adapter does not claim a filesystem snapshot or defend against a hostile local writer.
  */
final class NativeDirectorySource[F[_]: Async](root: Path) extends NativeSource[F]:
  val label: String = root.toAbsolutePath.normalize.toString

  def entries: Stream[F, SourceEntry] =
    Stream.eval(Async[F].blocking {
      if !LocalFiles.attributes(root).isDirectory then throw Failure(Problem.Unsupported("source must be a directory"))
    }) >> Stream.resource(Resource.fromAutoCloseable(Async[F].blocking(Files.walk(root)))).flatMap { walk =>
      Stream.unfoldEval(walk.iterator()) { iterator =>
        Async[F].blocking {
          if iterator.hasNext then Some(iterator.next() -> iterator) else None
        }
      }.filter(_ != root).evalMap { path =>
        Async[F].blocking {
          val relative = RelPath.from(root.relativize(path).toString).fold(p => throw Failure(p), identity)
          val attrs = LocalFiles.attributes(path)
          if attrs.isDirectory then SourceEntry.Directory(relative)
          else SourceEntry.File(relative, attrs.size(), LocalFiles.observation(attrs))
        }
      }
    }

  def read(file: SourceEntry.File): Stream[F, Byte] =
    val path = root.resolve(file.path.value)
    def unchanged: F[Unit] = Async[F].blocking {
      val attrs = LocalFiles.attributes(path)
      if !attrs.isRegularFile || LocalFiles.observation(attrs) != file.observation then
        throw Failure(Problem.Integrity(s"source changed since inventory: ${file.path.value}"))
    }
    Stream.exec(unchanged) ++
      fs2.io.readInputStream(Async[F].blocking(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)), 64 * 1024) ++
      Stream.exec(unchanged)
