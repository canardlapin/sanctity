package sanctity.application

import cats.effect.Async
import fs2.Stream
import sanctity.kernel.*

/** A source backed only by explicitly inventoried immutable objects. */
final class BlobSource[F[_]: Async](manifest: Manifest, store: BlobStore[F], val label: String,
    override val evidence: Option[BlobRef] = None, override val retainedEvidence: Vector[BlobRef] = Vector.empty) extends NativeSource[F]:
  def entries: Stream[F, SourceEntry] = Stream.emits(manifest.entries.map {
    case Entry.Directory(path) => SourceEntry.Directory(path)
    case Entry.File(path, _, blob) => SourceEntry.File(path, blob.size, blob.digest.value)
  }).covary[F]

  def read(file: SourceEntry.File): Stream[F, Byte] =
    manifest.byPath.get(file.path) match
      case Some(Entry.File(_, _, blob)) if blob.size == file.size && blob.digest.value == file.observation => store.read(blob)
      case _ => Stream.raiseError[F](Failure(Problem.Integrity("read is outside the source inventory")))
