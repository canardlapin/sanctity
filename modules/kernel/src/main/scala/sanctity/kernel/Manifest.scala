package sanctity.kernel

final class BlobRef private (val digest: Digest, val size: Long):
  override def equals(other: Any): Boolean = other match
    case blob: BlobRef => digest == blob.digest && size == blob.size
    case _ => false
  override def hashCode(): Int = (digest, size).hashCode()
  override def toString: String = s"BlobRef(${digest.value},$size)"
object BlobRef:
  def from(digest: Digest, size: Long): Either[Problem, BlobRef] =
    Either.cond(size >= 0, new BlobRef(digest, size), Problem.InvalidManifest("negative blob size"))

enum Entry:
  case Directory(override val path: RelPath)
  case File(override val path: RelPath, artifact: ArtifactId, blob: BlobRef)

  def path: RelPath = this match
    case Directory(path) => path
    case File(path, _, _) => path

enum Change:
  case Put(path: RelPath, artifact: ArtifactId, blob: BlobRef)
  case Remove(path: RelPath)
  case Move(from: RelPath, to: RelPath)

final case class EntryDelta(path: RelPath, before: Option[Entry], after: Option[Entry])

/** Bounded, immutable reference manifest; production adapters may partition it. */
final class Manifest private (val id: ManifestId, val entries: Vector[Entry]):
  lazy val byPath: Map[RelPath, Entry] = entries.map(e => e.path -> e).toMap
  def files: Vector[Entry.File] = entries.collect { case file: Entry.File => file }
  def directories: Vector[RelPath] = entries.collect { case Entry.Directory(path) => path }

  def diff(other: Manifest): Vector[EntryDelta] =
    (byPath.keySet ++ other.byPath.keySet).toVector.sortBy(_.value).flatMap { path =>
      Option.when(byPath.get(path) != other.byPath.get(path))(EntryDelta(path, byPath.get(path), other.byPath.get(path)))
    }

  def change(changes: Vector[Change]): Either[Problem, Manifest] =
    changes.foldLeft[Either[Problem, Map[RelPath, Entry]]](Right(byPath)) { (acc, change) =>
      acc.flatMap { entries =>
        change match
          case Change.Put(path, artifact, blob) =>
            entries.get(path) match
              case Some(Entry.Directory(_)) => Left(Problem.Conflict(s"$path is a directory"))
              case Some(Entry.File(_, existing, _)) if existing != artifact =>
                Left(Problem.Conflict(s"$path already has another artifact identity"))
              case _ => Right(entries.updated(path, Entry.File(path, artifact, blob)))
          case Change.Remove(path) =>
            entries.get(path) match
              case Some(_: Entry.File) => Right(entries - path)
              case _ => Left(Problem.Conflict(s"file $path does not exist"))
          case Change.Move(from, to) =>
            if entries.contains(to) then Left(Problem.Conflict(s"destination $to already exists"))
            else entries.get(from) match
              case Some(Entry.File(_, artifact, blob)) =>
                Right((entries - from).updated(to, Entry.File(to, artifact, blob)))
              case _ => Left(Problem.Conflict(s"file $from does not exist"))
      }
    }.flatMap(entries => Manifest.from(entries.values.toVector))

object Manifest:
  def from(entries: Vector[Entry]): Either[Problem, Manifest] =
    val ordered = entries.sortBy(_.path.value)
    val paths = ordered.map(_.path)
    val directories = ordered.collect { case Entry.Directory(path) => path }.toSet
    val artifacts = ordered.collect { case Entry.File(_, artifact, _) => artifact }
    if paths.distinct.size != paths.size then Left(Problem.InvalidManifest("duplicate path"))
    else if paths.map(_.collisionKey).distinct.size != paths.size then
      Left(Problem.InvalidManifest("case or Unicode-normalization path collision"))
    else if artifacts.distinct.size != artifacts.size then Left(Problem.InvalidManifest("artifact appears at multiple paths"))
    else if paths.flatMap(_.parent).exists(parent => !directories(parent)) then
      Left(Problem.InvalidManifest("missing parent directory or parent is a file"))
    else
      val fields = ordered.flatMap {
        case Entry.Directory(path) => Vector("directory", path.value)
        case Entry.File(path, artifact, blob) =>
          Vector("file", path.value, artifact.render, blob.digest.value, blob.size.toString)
      }
      Right(new Manifest(ManifestId(Digest.framed("sanctity.manifest.v1", fields)), ordered))
