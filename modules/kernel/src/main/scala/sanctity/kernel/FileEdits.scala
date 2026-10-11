package sanctity.kernel

/** Sources refer to the same base manifest. Moves are simultaneous, so swaps need
  * no temporary scientific path. Each source is mentioned exactly once.
  */
enum FileEdit:
  case Move(from: RelPath, to: RelPath)
  case Remove(path: RelPath)

  def source: RelPath = this match
    case Move(from, _) => from
    case Remove(path) => path
  def destination: Option[RelPath] = this match
    case Move(_, to) => Some(to)
    case Remove(_) => None

object FileEdits:
  val MaxEntries = 10000

  def applyTo(base: Manifest, edits: Vector[FileEdit]): Either[Problem, Manifest] =
    val sources = edits.map(_.source)
    val destinations = edits.flatMap(_.destination)
    if edits.isEmpty then Left(Problem.Conflict("a file change requires at least one edit"))
    else if edits.size > MaxEntries || base.entries.size > MaxEntries then Left(Problem.LimitExceeded("file change exceeds 10000 entries"))
    else if sources.distinct.size != sources.size then Left(Problem.Conflict("a base file may be edited only once"))
    else if destinations.distinct.size != destinations.size then Left(Problem.Conflict("multiple moves have the same destination"))
    else if edits.exists(e => e.destination.contains(e.source)) then Left(Problem.Conflict("a move must change its path"))
    else if sources.exists(p => !base.byPath.get(p).exists(_.isInstanceOf[Entry.File])) then
      Left(Problem.Conflict("every edited source must be a file in the base manifest"))
    else
      val mapping = edits.map(e => e.source -> e.destination).toMap
      val files = base.files.flatMap { file =>
        mapping.getOrElse(file.path, Some(file.path)).map(p => file.copy(path = p))
      }
      // Preserve explicitly inventoried empty directories; add only required parents.
      val parents = files.flatMap(f => Iterator.iterate(f.path.parent)(_.flatMap(_.parent))
        .takeWhile(_.nonEmpty).flatten.toVector)
      val directories = (base.directories ++ parents).distinct.map(Entry.Directory.apply)
      if files.size + directories.size > MaxEntries then Left(Problem.LimitExceeded("file change exceeds 10000 entries"))
      else Manifest.from(directories ++ files)

/** Reverse dependency closure on each interpretation, including metadata applicability.
  * A newly applicable sidecar is therefore visible even without an old dependency edge.
  */
final case class DependencyImpact(before: Vector[RelPath], after: Vector[RelPath])
object DependencyImpact:
  val empty: DependencyImpact = DependencyImpact(Vector.empty, Vector.empty)
  def between(before: Interpretation, after: Interpretation, changed: Set[RelPath]): DependencyImpact =
    DependencyImpact(affected(before, changed), affected(after, changed))

  private def affected(at: Interpretation, changed: Set[RelPath]): Vector[RelPath] =
    val edges = at.artifacts.flatMap(a => a.sources.map(_ -> a.path)) ++
      at.relationships.toVector.flatMap(_.relationships).flatMap(r => r.to.map(_ -> r.from))
    val reverse = edges.groupMap(_._1)(_._2)
    var visited = changed
    var pending = changed.toList
    while pending.nonEmpty do
      val current = pending.head
      pending = pending.tail
      reverse.getOrElse(current, Vector.empty).foreach { p =>
        if !visited(p) then
          visited += p
          pending = p :: pending
      }
    visited.filter(at.byPath.contains).toVector.sortBy(_.value)

/** Binds an inspected plan and its retained receipt to the exact prepared output. */
final case class ChangeEvidence(plan: Digest, source: ReleaseRef, output: ManifestId, receipt: BlobRef)
