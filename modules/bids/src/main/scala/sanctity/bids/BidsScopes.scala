package sanctity.bids

import bids4s.BidsPath
import sanctity.kernel.*

/** Whether content the repository does not interpret may be accepted.
  * Inclusive keeps it, labelled as preserved. Strict refuses a dataset that contains it.
  */
enum AdmissionPolicy:
  case Inclusive, Strict

  def name: String = this match
    case Inclusive => "sanctity-bids-inclusive-admission-v1"
    case Strict => "sanctity-bids-strict-admission-v1"

/** Decides which files of a raw BIDS dataset are interpreted, and records a coverage level for every file.
  *
  * Files outside the interpreted scope never reach the BIDS rules, so an arbitrary file under `sourcedata/` or in a
  * data type without a profile can neither fail a name check nor be mistaken for metadata.
  */
object BidsScopes:
  enum Reason:
    case ReservedDirectory, UnknownDirectory, UninterpretedDataType, RecordingDirectory

  /** A file kept without interpretation. `root` names the directory or data type the notice is aggregated under. */
  final case class Kept(file: Entry.File, group: String, reason: Reason, root: String)
  final case class Partition(interpreted: Vector[Entry.File], preserved: Vector[Kept])

  private val reserved = Set("sourcedata", "code", "derivatives", "phenotype")
  private val assessedDocuments = Set("dataset_description.json", "participants.tsv", "participants.json")
  private val describedDocuments = Set("README", "README.md", "README.rst", "README.txt", "CHANGES", "LICENSE", "CITATION.cff", ".bidsignore")
  private val namingCodes = Set("InvalidName", "InvalidEntity", "MissingRequiredField", "InvalidPath")
  private val unrecognizedCodes = Set("UnrecognizedFile", "UnsupportedFileRole")
  private val context = RepositoryBidsContext.Repository

  private def parts(path: RelPath): Vector[String] = path.value.split('/').toVector
  private def label(name: String): String = if name.matches("[a-z][a-z0-9-]{0,39}") then name else "other"

  /** Position of the datatype folder in a path below `sub-*`, after an optional `ses-*` level. */
  private def folderIndex(segments: Vector[String]): Option[Int] =
    Option.when(segments.size > 1 && segments.head.startsWith("sub-"))(if segments.lift(1).exists(_.startsWith("ses-")) then 2 else 1)
      .filter(_ < segments.size - 1)

  def datatypeFolder(path: RelPath): Option[String] =
    val segments = parts(path)
    folderIndex(segments).map(segments)

  def partition(manifest: Manifest): Partition =
    val (kept, read) = manifest.files.partitionMap { file =>
      val segments = parts(file.path)
      if segments.size == 1 || segments.head == "stimuli" then Right(file)
      else if !segments.head.startsWith("sub-") then
        val top = segments.head
        Left(if reserved(top) then Kept(file, top, Reason.ReservedDirectory, top) else Kept(file, "other", Reason.UnknownDirectory, top))
      else folderIndex(segments) match
        case None => Right(file)
        case Some(at) =>
          val folder = segments(at)
          RepositoryBidsContext.modalityOfFolder(folder) match
            case None => Left(Kept(file, label(folder), Reason.UninterpretedDataType, folder))
            case Some(m) if m.level == CoverageLevel.Described && segments.size - 1 > at + 1 =>
              Left(Kept(file, folder, Reason.RecordingDirectory, folder))
            case Some(_) => Right(file)
    }
    Partition(read, kept)

  /** The interpreted files with the directories that contain them. */
  def scoped(manifest: Manifest, partition: Partition): Either[Problem, Manifest] =
    val needed = partition.interpreted.flatMap(f => Iterator.iterate(f.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten).toSet
    Manifest.from(partition.interpreted ++ manifest.entries.collect { case d: Entry.Directory if needed(d.path) => d })

  /** One notice per directory or data type, never one per file. */
  def notices(partition: Partition, policy: AdmissionPolicy): Vector[Finding] =
    val refusal = if policy == AdmissionPolicy.Strict then Severity.Error else Severity.Warning
    partition.preserved.groupBy(k => (k.reason, k.root)).toVector.sortBy((key, _) => (key._1.ordinal, key._2)).flatMap { (key, files) =>
      val (reason, root) = key
      val count = if files.size == 1 then "1 file is" else s"${files.size} files are"
      reason match
        case Reason.ReservedDirectory => None
        case Reason.UnknownDirectory => Some(Finding("UninterpretedDirectory", refusal, None,
          s"top-level directory '$root' is not a BIDS subject directory or a reserved directory; $count preserved without interpretation"))
        case Reason.UninterpretedDataType => Some(Finding("UninterpretedDataType", refusal, None,
          s"no installed profile interprets '$root' folders; $count preserved without interpretation"))
        case Reason.RecordingDirectory => Some(Finding("RecordingDirectoryPreserved", Severity.Warning, None,
          s"$count preserved inside recording directories under '$root' folders; recordings stored as directories are not interpreted yet"))
    }

  /** BIDS sets no naming rules for stimulus files, so name findings about them are dropped under either policy.
    * Under the inclusive policy a badly named file in a described data type is kept and reported, not refused.
    */
  def relax(findings: Vector[Finding], policy: AdmissionPolicy): Vector[Finding] =
    val named = findings.filterNot(f => (namingCodes ++ unrecognizedCodes)(f.code) && f.path.exists(_.value.startsWith("stimuli/")))
    if policy == AdmissionPolicy.Strict then named
    else named.map { f =>
      val described = f.path.flatMap(datatypeFolder).flatMap(RepositoryBidsContext.modalityOfFolder).exists(_.level == CoverageLevel.Described)
      if f.severity == Severity.Error && namingCodes(f.code) && described then
        f.copy(severity = Severity.Warning, message = f.message + "; the file is preserved without description")
      else f
    }

  def levels(partition: Partition, interpretation: Interpretation): Either[Problem, CoverageLevels] =
    val indexed = interpretation.facts.map(_.path).toSet
    def named(path: RelPath): Option[CoverageLevel] =
      context.parsePath(BidsPath(path.value)).toOption.flatMap(_.datatype).flatMap(RepositoryBidsContext.modalityOfSpec).map(_.level)
    def role(name: String): Option[CoverageLevel] =
      context.parseGeneric(name).toOption.flatMap(n => RepositoryBidsContext.levelOfRole(n.kind, n.extension))
    def atLeastDescribed(path: RelPath, level: CoverageLevel): CoverageLevel =
      if indexed(path) && level == CoverageLevel.Preserved then CoverageLevel.Described else level
    val read = partition.interpreted.map { file =>
      val path = file.path
      val segments = parts(path)
      val name = path.fileName
      if segments.size == 1 then
        val level = if assessedDocuments(name) then CoverageLevel.Assessed else if describedDocuments(name) then CoverageLevel.Described
          else named(path).orElse(role(name)).getOrElse(CoverageLevel.Preserved)
        FileLevel(path, "dataset", atLeastDescribed(path, level))
      else if segments.head == "stimuli" then
        FileLevel(path, "stimuli", if interpretation.byPath.contains(path) then CoverageLevel.Described else CoverageLevel.Preserved)
      else folderIndex(segments) match
        case Some(at) => FileLevel(path, label(segments(at)), atLeastDescribed(path, named(path).getOrElse(CoverageLevel.Preserved)))
        case None => FileLevel(path, "dataset", atLeastDescribed(path, named(path).orElse(role(name)).getOrElse(CoverageLevel.Preserved)))
    }
    CoverageLevels.from(read ++ partition.preserved.map(k => FileLevel(k.file.path, k.group, CoverageLevel.Preserved)))
