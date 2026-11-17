package sanctity.bids

import bids4s.{BidsContext, BidsDatatypeSpec, BidsKind, BidsRegistry, DatatypeScope, EntityKey, EntityRule}
import sanctity.kernel.CoverageLevel

/** One data type the repository understands, and how far.
  *
  * A modality owns the names in its datatype folders. `Assessed` modalities also contribute content checks;
  * `Described` modalities are interpreted from names and sidecar metadata alone.
  */
final case class BidsModality(id: String, title: String, level: CoverageLevel, specs: Vector[BidsDatatypeSpec]):
  val folders: Set[String] = specs.map(_.folder).toSet

/** The single naming context for repository datasets. Adding a data type means adding a modality here. */
object RepositoryBidsContext:
  import EntityKey.*

  private def kind(name: String, extensions: String*): BidsKind =
    BidsKind.from(name, extensions.toVector).fold(e => throw new IllegalArgumentException(e.message), identity)
  private def required(key: EntityKey): EntityRule = EntityRule(key, required = true)
  private def optional(key: EntityKey): EntityRule = EntityRule(key, required = false)
  private def index(key: EntityKey): EntityRule = EntityRule(key, required = false, Some("[0-9]+"))
  private def custom(short: String): EntityKey = EntityKey.Custom(short, short)
  private def raw(name: String, folder: String, entities: Vector[EntityRule], kinds: BidsKind*): BidsDatatypeSpec =
    BidsDatatypeSpec(name, folder, DatatypeScope.Raw, entities, kinds.toVector)

  private val images = Vector("jpg", "png", "tif")
  private val recording = Vector(required(Subject), optional(Session), required(Task), optional(Acquisition), index(Run))
  private val placed = Vector(required(Subject), optional(Session), optional(Acquisition), optional(Space))
  private val unplaced = Vector(required(Subject), optional(Session), optional(Acquisition))
  private val tables = Vector(kind("channels", "tsv", "json"), kind("events", "tsv", "json"))

  private val behavioural = raw("beh", "beh", recording, kind("beh", "tsv", "json"), kind("events", "tsv", "json"))

  private val eeg = Vector(
    raw("eeg", "eeg", recording, (kind("eeg", "edf", "vhdr", "vmrk", "eeg", "set", "fdt", "bdf", "json") +: tables)*),
    raw("eeg-layout", "eeg", placed, kind("electrodes", "tsv", "json"), kind("coordsystem", "json"), kind("photo", images*)),
    raw("eeg-physio", "eeg", recording :+ optional(custom("recording")), kind("physio", "tsv.gz", "json"), kind("stim", "tsv.gz", "json")))

  private val ieeg = Vector(
    raw("ieeg", "ieeg", recording, (kind("ieeg", "edf", "vhdr", "vmrk", "eeg", "set", "fdt", "nwb", "json") +: tables)*),
    raw("ieeg-layout", "ieeg", placed, kind("electrodes", "tsv", "json"), kind("coordsystem", "json"), kind("photo", images*)))

  private val meg = Vector(
    raw("meg", "meg", recording ++ Vector(optional(custom("proc")), index(custom("split"))),
      (kind("meg", "fif", "sqd", "con", "raw", "ave", "mrk", "kdf", "chn", "trg", "json") +: tables)*),
    raw("meg-calibration", "meg", Vector(required(Subject), optional(Session), EntityRule(Acquisition, required = true, Some("calibration|crosstalk"))),
      kind("meg", "dat", "fif")),
    raw("meg-layout", "meg", unplaced, kind("coordsystem", "json"), kind("headshape", "pos", "elp", "hsp"), kind("photo", images*)),
    raw("meg-markers", "meg", Vector(required(Subject), optional(Session), optional(Task), optional(Acquisition), optional(Space)),
      kind("markers", "sqd", "mrk")))

  private val nirs = Vector(
    raw("nirs", "nirs", recording, (kind("nirs", "snirf", "json") +: tables)*),
    raw("nirs-layout", "nirs", unplaced, kind("optodes", "tsv", "json"), kind("coordsystem", "json"), kind("photo", images*)))

  private val tracer = Vector(required(Subject), optional(Session), optional(Task), optional(custom("trc")), optional(Reconstruction), index(Run))
  private val pet = Vector(
    raw("pet", "pet", tracer, kind("pet", "nii.gz", "nii", "json"), kind("events", "tsv", "json")),
    raw("pet-blood", "pet", tracer :+ required(custom("recording")), kind("blood", "tsv", "json")))

  private val perfusion = Vector(
    raw("perf", "perf", Vector(required(Subject), optional(Session), optional(Acquisition), optional(Reconstruction), optional(Direction), index(Run)),
      kind("asl", "nii.gz", "nii", "json"), kind("m0scan", "nii.gz", "nii", "json"), kind("aslcontext", "tsv"), kind("asllabeling", "jpg")))

  private val tracked = Vector(required(Subject), optional(Session), required(Task), optional(Acquisition), index(Run))
  private val motion = Vector(
    raw("motion", "motion", tracked :+ required(custom("tracksys")), kind("motion", "tsv", "json"), kind("channels", "tsv", "json")),
    raw("motion-events", "motion", tracked :+ optional(custom("tracksys")), kind("events", "tsv", "json")))

  /** Registration order is significant: the first specification that accepts a name wins. */
  val Modalities: Vector[BidsModality] = Vector(
    BidsModality("mri", "MRI", CoverageLevel.Assessed, BidsRegistry.Builtin.specs),
    BidsModality("beh", "Behaviour", CoverageLevel.Assessed, Vector(behavioural)),
    BidsModality("eeg", "EEG", CoverageLevel.Described, eeg),
    BidsModality("ieeg", "Intracranial EEG", CoverageLevel.Described, ieeg),
    BidsModality("meg", "MEG", CoverageLevel.Described, meg),
    BidsModality("nirs", "NIRS", CoverageLevel.Described, nirs),
    BidsModality("pet", "PET", CoverageLevel.Described, pet),
    BidsModality("perf", "Perfusion MRI", CoverageLevel.Described, perfusion),
    BidsModality("motion", "Motion", CoverageLevel.Described, motion))

  private val bySpec: Map[String, BidsModality] = Modalities.flatMap(m => m.specs.map(_.name -> m)).toMap
  private val byFolder: Map[String, BidsModality] = Modalities.flatMap(m => m.folders.map(_ -> m)).toMap

  def modalityOfSpec(name: String): Option[BidsModality] = bySpec.get(name)
  def modalityOfFolder(folder: String): Option[BidsModality] = byFolder.get(folder)

  /** For a file outside a datatype folder, such as an inherited sidecar: the lowest level among the modalities that
    * register its role. A role shared by several data types never claims more than its weakest user.
    */
  def levelOfRole(kind: String, extension: String): Option[CoverageLevel] =
    Modalities.filter(_.specs.exists(_.kinds.exists(k => k.name == kind && k.extensions.contains(extension))))
      .map(_.level).minByOption(_.ordinal)

  val Statement: String =
    "BIDS names, JSON inheritance and query facts for every installed data type. " +
      "Assessed: MRI (anat, func, dwi, fmap) and behavioural tables, with the content checks listed in each assessment. " +
      "Described: EEG, intracranial EEG, MEG, NIRS, PET, perfusion MRI and motion; names and sidecar metadata are " +
      "interpreted and indexed, and recordings are not opened. Recordings stored as directories, data types without an " +
      "installed profile, and the sourcedata, code, derivatives and phenotype directories are preserved without interpretation. " +
      "No HED validation, signal processing or full BIDS conformance."

  val Repository: BidsContext = BidsContext.from("sanctity-bids-v1",
    Modalities.drop(1).flatMap(_.specs).foldLeft(BidsRegistry.Builtin)(_.withDatatype(_)), Some("1.11.2"), Statement)
    .fold(e => throw new IllegalArgumentException(e.message), identity)

  /** The behavioural profile is part of the repository context. The name is kept for its existing callers. */
  val Behavioural: BidsContext = Repository
