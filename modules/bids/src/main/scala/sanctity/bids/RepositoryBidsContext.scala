package sanctity.bids

import bids4s.{BidsContext, BidsDatatypeSpec, BidsKind, BidsRegistry, DatatypeScope, EntityKey, EntityRule}
import sanctity.kernel.CoverageLevel

/** One data type the repository understands, and how far.
  *
  * A modality owns the names in its datatype folders. `Assessed` modalities also contribute content checks;
  * `Described` modalities are interpreted from names and sidecar metadata alone.
  */
final case class BidsModality(id: String, title: String, level: CoverageLevel, specs: Vector[BidsDatatypeSpec],
    rules: Option[ModalityRules] = None):
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

  // The described data types follow the file rules of the BIDS schema (bids-specification f9d3b8a, schema 1.11.3-dev:
  // rules/files/raw/{eeg,ieeg,meg,nirs,pet,perf,motion,channels,photo,task,events}.yaml). One specification here is one
  // schema rule: a set of suffixes that share entities. Recordings the schema stores as directories (.ds/, .mefd/,
  // .ome.zarr/ and BTi/4D) are preserved by BidsScopes, not named here. The schema lets a MEG head shape have any
  // extension; the common ones are listed.
  private val base = Vector(required(Subject), optional(Session))
  private val recording = base ++ Vector(optional(Acquisition), index(Run))
  private val task = recording :+ required(Task)
  private val sampled = optional(custom("recording"))
  private val processing = optional(custom("proc"))
  private val images = Vector("jpg", "png", "tif")
  private val channels = kind("channels", "json", "tsv")
  private val events = kind("events", "tsv", "json")
  private def timeseries(name: String, folder: String, entities: Vector[EntityRule]): BidsDatatypeSpec =
    raw(name, folder, entities, kind("physio", "tsv.gz", "json"), kind("physioevents", "tsv.gz", "json"), kind("stim", "tsv.gz", "json"))
  private def photo(name: String, folder: String): BidsDatatypeSpec =
    raw(name, folder, base :+ optional(Acquisition), kind("photo", images*))

  /** EEG and intracranial EEG share every rule except the recording formats. */
  private def electrophysiology(folder: String, formats: String*): Vector[BidsDatatypeSpec] = Vector(
    raw(folder, folder, task, kind(folder, formats*), channels, events),
    raw(s"$folder-electrodes", folder, recording ++ Vector(optional(Task), optional(Space)), kind("electrodes", "json", "tsv")),
    raw(s"$folder-coordsystem", folder, base ++ Vector(optional(Task), optional(Acquisition), optional(Space)), kind("coordsystem", "json")),
    photo(s"$folder-photo", folder),
    timeseries(s"$folder-timeseries", folder, task :+ sampled))

  private val eeg = electrophysiology("eeg", "json", "edf", "vhdr", "vmrk", "eeg", "set", "fdt", "bdf")
  private val ieeg = electrophysiology("ieeg", "json", "edf", "vhdr", "eeg", "vmrk", "set", "fdt", "nwb")

  private val meg = Vector(
    raw("meg", "meg", task ++ Vector(processing, index(custom("split"))),
      kind("meg", "json", "fif", "sqd", "con", "raw", "ave", "mrk", "kdf", "mhd", "raw.mhd", "trg", "chn")),
    raw("meg-channels", "meg", task :+ processing, channels),
    raw("meg-events", "meg", task, events),
    raw("meg-calibration", "meg", base :+ EntityRule(Acquisition, required = true, Some("calibration")), kind("meg", "dat")),
    raw("meg-crosstalk", "meg", base :+ EntityRule(Acquisition, required = true, Some("crosstalk")), kind("meg", "fif")),
    raw("meg-headshape", "meg", base :+ optional(Acquisition), kind("headshape", "pos", "elp", "hsp", "txt")),
    raw("meg-markers", "meg", base ++ Vector(optional(Task), optional(Acquisition), optional(Space)), kind("markers", "sqd", "mrk")),
    raw("meg-coordsystem", "meg", base ++ Vector(optional(Task), optional(Acquisition)), kind("coordsystem", "json")),
    raw("meg-electrodes", "meg", recording ++ Vector(optional(Task), optional(Space), processing), kind("electrodes", "json", "tsv")),
    photo("meg-photo", "meg"),
    timeseries("meg-timeseries", "meg", task ++ Vector(sampled, processing)))

  private val nirs = Vector(
    raw("nirs", "nirs", task, kind("nirs", "snirf", "json"), channels, events),
    raw("nirs-optodes", "nirs", base :+ optional(Acquisition), kind("optodes", "tsv", "json")),
    raw("nirs-coordsystem", "nirs", base ++ Vector(optional(Task), optional(Acquisition)), kind("coordsystem", "json")),
    photo("nirs-photo", "nirs"),
    timeseries("nirs-timeseries", "nirs", task :+ sampled))

  private val tracer = base ++ Vector(optional(Task), optional(custom("trc")), optional(Reconstruction), index(Run))
  private val pet = Vector(
    raw("pet", "pet", tracer, kind("pet", "nii.gz", "nii", "json"), events),
    raw("pet-blood", "pet", tracer :+ required(custom("recording")), kind("blood", "tsv", "json")),
    timeseries("pet-timeseries", "pet", base ++ Vector(index(Run), required(Task), sampled, optional(custom("trc")), optional(Reconstruction))))

  private val labelled = recording ++ Vector(optional(Reconstruction), optional(Direction))
  private val perfusionImage = labelled ++ Vector(optional(custom("part")), index(Echo))
  private val perfusion = Vector(
    raw("perf", "perf", perfusionImage, kind("asl", "nii.gz", "nii", "json"), kind("m0scan", "nii.gz", "nii", "json")),
    raw("perf-norf", "perf", perfusionImage :+ optional(Modality), kind("noRF", "nii.gz", "nii", "json")),
    raw("perf-context", "perf", labelled, kind("aslcontext", "tsv")),
    raw("perf-labeling", "perf", recording :+ optional(Reconstruction), kind("asllabeling", images*)),
    timeseries("perf-timeseries", "perf", labelled :+ sampled))

  private val motion = Vector(
    raw("motion", "motion", task :+ required(custom("tracksys")), kind("motion", "tsv", "json"), channels),
    raw("motion-events", "motion", task :+ optional(custom("tracksys")), events),
    timeseries("motion-timeseries", "motion", task ++ Vector(sampled, optional(custom("tracksys")))))

  /** Registration order is significant: the first specification that accepts a name wins. */
  val Modalities: Vector[BidsModality] = Vector(
    MriModality.descriptor,
    BehaviouralModality.descriptor,
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
