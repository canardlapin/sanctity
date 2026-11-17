package sanctity.kernel

/** What the repository did with a file. A level describes the repository's work, never the quality of the data.
  *
  *   - Preserved: the bytes, digest and path are stored. Nothing is claimed about meaning.
  *   - Described: the name and its metadata were interpreted and indexed. The content was not opened.
  *   - Assessed: versioned content checks apply; their inputs and results are in the assessment.
  */
enum CoverageLevel:
  case Preserved, Described, Assessed

/** `group` is the data type or area a reader would file this under, for example `func`, `eeg` or `dataset`. */
final case class FileLevel(path: RelPath, group: String, level: CoverageLevel)

/** One level for each file of a manifest, recorded with the interpretation that assigned it. */
final class CoverageLevels private (val version: String, val files: Vector[FileLevel]):
  lazy val byPath: Map[RelPath, FileLevel] = files.map(f => f.path -> f).toMap

  /** File counts per group and level, in a stable order. */
  def summary: Vector[(String, CoverageLevel, Int)] =
    files.groupMapReduce(f => (f.group, f.level))(_ => 1)(_ + _).toVector
      .sortBy((key, _) => (key._1, key._2.ordinal)).map((key, count) => (key._1, key._2, count))

  private[kernel] def tokens: Vector[String] =
    Vector(files.size.toString) ++ files.flatMap(f => Vector(f.path.value, f.group, f.level.toString))

  override def equals(other: Any): Boolean = other match
    case c: CoverageLevels => version == c.version && files == c.files
    case _ => false
  override def hashCode(): Int = (version, files).hashCode()

object CoverageLevels:
  val Version: String = "sanctity-coverage-levels-v1"
  val MaxFiles: Int = 1000000

  def from(files: Vector[FileLevel], version: String = Version): Either[Problem, CoverageLevels] =
    val valid = version.matches("[a-z][a-z0-9.-]{0,99}") && files.size <= MaxFiles &&
      files.map(_.path).distinct.size == files.size && files.forall(_.group.matches("[a-z][a-z0-9-]{0,39}"))
    Either.cond(valid, new CoverageLevels(version, files.sortBy(_.path.value)),
      Problem.InvalidManifest("invalid, duplicate or oversized coverage levels"))
