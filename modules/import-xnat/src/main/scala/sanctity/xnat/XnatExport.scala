package sanctity.xnat

import cats.effect.{Async, Resource}
import fs2.Stream
import sanctity.application.*
import sanctity.kernel.*
import java.nio.file.{Files, Path}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Resource ZIP plus its independently supplied file listing. Unknown archive files block accounting. */
object XnatExport:
  def open[F[_]: Async](archive: Path, inventory: XnatInventory, store: BlobStore[F],
      memberPrefix: String = ""): Resource[F, NativeSource[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(Checked.lift[F, Unit](Either.cond(memberPrefix.isEmpty ||
        (memberPrefix.endsWith("/") && RelPath.from(memberPrefix.stripSuffix("/")).isRight), (),
        Problem.InvalidPath(memberPrefix))))
      observed <- Resource.eval(F.blocking(Files.getLastModifiedTime(archive) -> Files.size(archive)))
      zip <- Resource.fromAutoCloseable(F.blocking(new ZipFile(archive.toFile)))
      _ <- Resource.eval(F.blocking {
        val members = zip.entries().asScala.toVector
        if members.size > 20000 || members.exists(e => RelPath.from(e.getName.stripSuffix("/")).isLeft) ||
            members.map(_.getName).distinct.size != members.size then throw Failure(Problem.Integrity("unsafe or duplicate ZIP inventory"))
        val files = members.filterNot(_.isDirectory)
        if files.map(_.getName).toSet != inventory.files.map(f => memberPrefix + f.path.value).toSet ||
          files.exists(e => inventory.files.find(f => memberPrefix + f.path.value == e.getName).forall(_.size != e.getSize)) then
          throw Failure(Problem.Integrity("archive differs from declared XNAT resource scope"))
      })
      receipt <- Resource.eval(store.put(Stream.emits(XnatInventory.bytes(inventory)).covary[F]))
    yield new NativeSource[F]:
      val label: String = s"xnat-export:${inventory.scope.installation}:${inventory.id.value}"
      override val evidence: Option[BlobRef] = Some(receipt)
      private def unchanged: F[Unit] = F.blocking {
        if (Files.getLastModifiedTime(archive) -> Files.size(archive)) != observed then
          throw Failure(Problem.Integrity("XNAT export changed during acquisition"))
      }
      def entries: Stream[F, SourceEntry] =
        val dirs = inventory.files.flatMap(f => Iterator.iterate(f.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten.toVector).distinct
        Stream.exec(unchanged) ++ Stream.emits(dirs.map(SourceEntry.Directory.apply) ++
          inventory.files.map(f => SourceEntry.File(f.path, f.size, f.observation))).covary[F]
      def read(file: SourceEntry.File): Stream[F, Byte] =
        inventory.files.find(f => f.path == file.path && f.size == file.size && f.observation == file.observation) match
          case None => Stream.raiseError[F](Failure(Problem.Denied))
          case Some(expected) =>
            Stream.exec(unchanged) ++ XnatInventory.verify(expected,
              fs2.io.readInputStream(F.blocking(zip.getInputStream(zip.getEntry(memberPrefix + file.path.value))), 64 * 1024)) ++ Stream.exec(unchanged)
