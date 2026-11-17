package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** A synthetic study with assessed, described and preserved content side by side. The recordings are placeholders:
  * described data types are read from names and sidecars, so their bytes are never opened.
  */
object CoverageFixture:
  val bold = SyntheticFixture.firstImage
  val eegHeader = "sub-01/eeg/sub-01_task-rest_eeg.vhdr"
  val eegMarkers = "sub-01/eeg/sub-01_task-rest_eeg.vmrk"
  val eegData = "sub-01/eeg/sub-01_task-rest_eeg.eeg"
  val eegSidecar = "sub-01/eeg/sub-01_task-rest_eeg.json"
  val eegChannels = "sub-01/eeg/sub-01_task-rest_channels.tsv"
  val eegElectrodes = "sub-01/eeg/sub-01_electrodes.tsv"
  val eegShared = "task-rest_eeg.json"
  val eegStray = "sub-01/eeg/session_notes.xyz"
  val pet = "sub-02/pet/sub-02_trc-FDG_pet.nii.gz"
  val petSidecar = "sub-02/pet/sub-02_trc-FDG_pet.json"
  val blood = "sub-02/pet/sub-02_trc-FDG_recording-manual_blood.tsv"
  val megFile = "sub-02/meg/sub-02_task-rest_run-01_meg.fif"
  val megMember = "sub-02/meg/sub-02_task-rest_run-02_meg.ds/sub-02_task-rest_run-02_meg.meg4"
  val megOther = "sub-02/meg/sub-02_task-rest_run-02_meg.ds/BadChannels"
  val microscopy = "sub-01/micr/sub-01_sample-A_SEM.png"
  val source = "sourcedata/scanner/session-notes.json"
  val code = "code/analysis.py"
  val extra = "extras/readme.txt"
  val brokenJson = "{not json"

  def write(root: Path, path: String, text: String): Unit =
    val at = root.resolve(path)
    Files.createDirectories(at.getParent)
    val _ = Files.writeString(at, text)

  /** The synthetic BOLD study, plus described data types only. Nothing here is outside an installed profile. */
  def described(root: Path): IO[Unit] = SyntheticFixture.create(root) *> IO.blocking {
    Vector(eegHeader, eegMarkers, eegData).foreach(write(root, _, "placeholder recording\n"))
    write(root, eegSidecar, """{"TaskName":"rest","SamplingFrequency":500,"EEGReference":"Cz","PowerLineFrequency":60}""")
    write(root, eegShared, """{"InstitutionName":"Synthetic Institute"}""")
    write(root, eegChannels, "name\ttype\tunits\nCz\tEEG\tuV\n")
    write(root, eegElectrodes, "name\tx\ty\tz\nCz\t0\t0\t1\n")
    write(root, pet, "placeholder image\n")
    write(root, petSidecar, """{"TracerName":"FDG"}""")
    write(root, blood, "time\tplasma_radioactivity\n0\t0\n")
    write(root, megFile, "placeholder recording\n")
  }

  /** Adds content no installed profile reads: a recording directory, an unknown data type and loose directories. */
  def mixed(root: Path): IO[Unit] = described(root) *> IO.blocking {
    write(root, megMember, "placeholder recording\n")
    write(root, megOther, "placeholder\n")
    write(root, microscopy, "placeholder image\n")
    write(root, eegStray, "left on the acquisition computer\n")
    write(root, source, brokenJson)
    write(root, code, "print('analysis')\n")
    write(root, extra, "kept for the record\n")
  }

  def archive(root: Path, description: String): IO[Unit] = IO.blocking {
    write(root, ArchiveInterpreter.Descriptor, description)
    write(root, "variants/cohort.vcf.gz", "placeholder variants\n")
    write(root, "documents/protocol.pdf", "placeholder protocol\n")
  }

  val archiveDescription: String =
    """{"Name":"Pilot genotyping export","Description":"Variant calls for the pilot cohort.","Authors":["A. Researcher"],""" +
      """"License":"CC-BY-4.0","Keywords":["genomics"],"DataTypes":["VCF","PDF"]}"""

/** The same journeys run against the reference and durable catalogs. */
abstract class CatalogCoverageSuite extends FunSuite:
  import CoverageFixture.*
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit

  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def repo(s: RepositorySystem, policy: AdmissionPolicy = AdmissionPolicy.Inclusive): NativeRepository[IO] =
    new NativeRepository[IO](s.catalog, s.store, new DatasetInterpreter[IO](s.store, policy), IdSource.random[IO])
  private def fresh(root: Path): IO[Path] =
    val at = root.resolve("source")
    IO.blocking(Files.exists(at)).flatMap(exists => if exists then TestFiles.remove(at) else IO.unit).as(at)
  private def imported(s: RepositorySystem, at: Path, policy: AdmissionPolicy = AdmissionPolicy.Inclusive): IO[Preview] =
    checked(repo(s, policy).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](at)))
  private def accepted(s: RepositorySystem, at: Path): IO[Release] =
    for
      preview <- imported(s, at)
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      receipt <- checked(repo(s).accept(s.context, preview.candidate.id, key("initial-coverage")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def level(r: Release, path: String): (String, CoverageLevel) =
    val found = r.interpretation.levels.flatMap(_.byPath.get(p(path))).getOrElse(fail(s"no level for $path"))
    found.group -> found.level

  test("a mixed study is accepted with one level for every file") {
    fixture { (s, root) =>
      for
        at <- fresh(root)
        _ <- mixed(at)
        release <- accepted(s, at)
        levels = release.interpretation.levels.getOrElse(fail("no levels recorded"))
        _ = assertEquals(levels.files.map(_.path).toSet, release.manifest.files.map(_.path).toSet)
        _ = Vector(
          bold -> ("func" -> CoverageLevel.Assessed), SyntheticFixture.sharedSidecar -> ("dataset" -> CoverageLevel.Assessed),
          "dataset_description.json" -> ("dataset" -> CoverageLevel.Assessed), "participants.tsv" -> ("dataset" -> CoverageLevel.Assessed),
          "README" -> ("dataset" -> CoverageLevel.Described),
          eegHeader -> ("eeg" -> CoverageLevel.Described), eegData -> ("eeg" -> CoverageLevel.Described),
          eegSidecar -> ("eeg" -> CoverageLevel.Described), eegChannels -> ("eeg" -> CoverageLevel.Described),
          eegElectrodes -> ("eeg" -> CoverageLevel.Described), eegShared -> ("dataset" -> CoverageLevel.Described),
          pet -> ("pet" -> CoverageLevel.Described), blood -> ("pet" -> CoverageLevel.Described),
          megFile -> ("meg" -> CoverageLevel.Described),
          megMember -> ("meg" -> CoverageLevel.Preserved), megOther -> ("meg" -> CoverageLevel.Preserved),
          eegStray -> ("eeg" -> CoverageLevel.Preserved), microscopy -> ("micr" -> CoverageLevel.Preserved),
          source -> ("sourcedata" -> CoverageLevel.Preserved), code -> ("code" -> CoverageLevel.Preserved),
          extra -> ("other" -> CoverageLevel.Preserved)
        ).foreach((path, expected) => assertEquals(level(release, path), expected, path))
        findings = release.assessment.findings
        _ = assertEquals(release.assessment.errors, Vector.empty)
        _ = assertEquals(release.assessment.policy, AdmissionPolicy.Inclusive.name)
        _ = assertEquals(release.interpretation.profile.name, RepositoryBidsInterpreter.Profile)
        _ = Vector("UninterpretedDataType" -> "'micr'", "UninterpretedDirectory" -> "'extras'", "RecordingDirectoryPreserved" -> "'meg'")
          .foreach((code, text) => assert(findings.exists(f => f.code == code && f.severity == Severity.Warning && f.message.contains(text)), code))
        _ = assert(findings.exists(f => f.code == "InvalidName" && f.severity == Severity.Warning && f.path.contains(p(eegStray))))
        // Reserved directories are expected content and are not reported.
        _ = assert(!findings.exists(f => f.message.contains("sourcedata") || f.message.contains("'code'")))
      yield ()
    }
  }

  test("described recordings carry inherited metadata and can be queried alongside assessed ones") {
    fixture { (s, root) =>
      for
        at <- fresh(root)
        _ <- mixed(at)
        release <- accepted(s, at)
        metadata <- checked(repo(s).explain(s.context, release.ref, p(eegHeader)))
        _ = assertEquals(metadata.fields("TaskName").value, MetadataValue.Text("rest"))
        _ = assertEquals(metadata.fields("TaskName").origin, p(eegSidecar))
        _ = assertEquals(metadata.fields("InstitutionName").origin, p(eegShared))
        tracer = release.interpretation.facts.find(_.path == p(pet)).getOrElse(fail("PET image is not indexed"))
        _ = assertEquals(tracer.suffix -> tracer.entities.get("trc"), "pet" -> Some("FDG"))
        both = ParticipantPredicate.All(Vector(ParticipantPredicate.HasArtifact(ArtifactPredicate.Suffix("bold")),
          ParticipantPredicate.HasArtifact(ArtifactPredicate.Suffix("eeg"))))
        selection <- checked(s.catalog.select(s.context, Vector(release.ref), ScientificQuery(both, ArtifactPredicate.Suffix("eeg"))))
        _ = assertEquals(selection.hits.map(_.path.value).toSet, Set(eegHeader, eegMarkers, eegData))
        _ = assertEquals(selection.hits.map(_.participant).toSet, Set("sub-01"))
        traced <- checked(s.catalog.select(s.context, Vector(release.ref), ScientificQuery(artifacts = ArtifactPredicate.Entity("trc", "FDG"))))
        _ = assertEquals(traced.hits.map(_.path.value).toSet, Set(pet, blood))
        // Files inside a recording directory are kept but not indexed.
        _ = assert(!release.interpretation.facts.exists(f => Set(megMember, megOther, microscopy)(f.path.value)))
      yield ()
    }
  }

  test("preserved content exports byte for byte, and the same content is always read the same way") {
    fixture { (s, root) =>
      for
        at <- fresh(root)
        _ <- mixed(at)
        release <- accepted(s, at)
        _ <- checked(repo(s).exportFull(s.context, release.ref, new DirectoryExport[IO](root.resolve("exported"))))
        _ <- IO.blocking {
          release.manifest.files.foreach(f => assert(Files.isRegularFile(root.resolve("exported").resolve(f.path.value)), f.path.value))
          assertEquals(Files.readString(root.resolve("exported").resolve(source)), brokenJson)
        }
        again <- new DatasetInterpreter[IO](s.store).prepare(release.manifest)
        _ = assertEquals(again._1.id, release.interpretation.id)
        _ = assertEquals(again._2.id, release.assessment.id)
      yield ()
    }
  }

  test("a described sidecar is corrected through the ordinary path, and a broken one blocks acceptance") {
    fixture { (s, root) =>
      for
        at <- fresh(root)
        _ <- described(at)
        release <- accepted(s, at)
        preview <- checked(repo(s).correctMetadata(s.context, release.ref, p(eegSidecar),
          """{"TaskName":"rest","SamplingFrequency":1000,"EEGReference":"Cz","PowerLineFrequency":60}""", "Recorded at 1 kHz"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        _ = assert(preview.metadataChanges.exists(c => c.path == p(eegHeader) && c.fields.exists(_.name == "SamplingFrequency")))
        _ = assertEquals(preview.candidate.interpretation.levels.map(_.byPath(p(eegHeader)).level), Some(CoverageLevel.Described))
        broken <- checked(repo(s).correctMetadata(s.context, release.ref, p(eegSidecar), brokenJson, "Illustrate a malformed sidecar"))
        _ = assert(!broken.candidate.assessment.admissible)
        _ = assert(broken.candidate.assessment.errors.exists(f => f.code == "InvalidJson" && f.path.contains(p(eegSidecar))))
      yield ()
    }
  }

  test("the strict policy refuses content without a profile and still accepts described data types") {
    fixture { (s, root) =>
      for
        at <- fresh(root)
        _ <- mixed(at)
        refused <- imported(s, at, AdmissionPolicy.Strict)
        _ = assert(!refused.candidate.assessment.admissible)
        _ = assertEquals(refused.candidate.assessment.policy, AdmissionPolicy.Strict.name)
        _ = assertEquals(refused.candidate.assessment.errors.map(_.code).toSet, Set("UninterpretedDataType", "UninterpretedDirectory", "InvalidName"))
        again <- fresh(root)
        _ <- described(again)
        allowed <- imported(s, again, AdmissionPolicy.Strict)
        _ = assert(allowed.candidate.assessment.admissible, allowed.candidate.assessment.errors.toString)
      yield ()
    }
  }

  test("an archive is accepted as preserved content with a checked description") {
    fixture { (s, root) =>
      for
        at <- fresh(root)
        _ <- archive(at, archiveDescription)
        release <- accepted(s, at)
        _ = assertEquals(release.interpretation.profile.name, ArchiveInterpreter.Profile)
        _ = assertEquals(release.assessment.policy, ArchiveInterpreter.Policy)
        _ = assertEquals(level(release, ArchiveInterpreter.Descriptor), "dataset" -> CoverageLevel.Assessed)
        _ = assertEquals(level(release, "variants/cohort.vcf.gz"), "archive" -> CoverageLevel.Preserved)
        _ = assertEquals(level(release, "documents/protocol.pdf"), "archive" -> CoverageLevel.Preserved)
        metadata <- checked(repo(s).explain(s.context, release.ref, p(ArchiveInterpreter.Descriptor)))
        _ = assertEquals(metadata.fields("Name").value, MetadataValue.Text("Pilot genotyping export"))
        _ = assertEquals(metadata.fields("Authors").value, MetadataValue.Array(Vector(MetadataValue.Text("A. Researcher"))))
        // An archive makes no scientific claims, so it has no scientific query index.
        query <- s.catalog.select(s.context, Vector(release.ref), ScientificQuery())
        _ = assert(query.isLeft)
        _ <- checked(repo(s).exportFull(s.context, release.ref, new DirectoryExport[IO](root.resolve("exported"))))
        _ <- IO.blocking(assertEquals(Files.readString(root.resolve("exported/variants/cohort.vcf.gz")), "placeholder variants\n"))
      yield ()
    }
  }

  test("a collection must declare exactly one kind, and an archive description must be well formed") {
    fixture { (s, root) =>
      def errors(prepare: Path => IO[Unit]): IO[Set[String]] =
        for
          at <- fresh(root)
          _ <- prepare(at)
          preview <- imported(s, at)
          _ = assert(!preview.candidate.assessment.admissible)
        yield preview.candidate.assessment.errors.map(_.code).toSet
      for
        unnamed <- errors(archive(_, """{"Description":"No name"}"""))
        _ = assertEquals(unnamed, Set("ArchiveDescriptionInvalid"))
        mistyped <- errors(archive(_, """{"Name":"Export","Authors":"A. Researcher","Keywords":[1]}"""))
        _ = assertEquals(mistyped, Set("ArchiveDescriptionInvalid"))
        malformed <- errors(archive(_, brokenJson))
        _ = assertEquals(malformed, Set("ArchiveDescriptionInvalid"))
        undeclared <- errors(at => IO.blocking(write(at, "variants/cohort.vcf.gz", "placeholder variants\n")))
        _ = assert(undeclared("UndeclaredDatasetKind"), undeclared.toString)
        both <- errors(at => described(at) *> IO.blocking(write(at, ArchiveInterpreter.Descriptor, archiveDescription)))
        _ = assertEquals(both, Set("AmbiguousDatasetKind"))
      yield ()
    }
  }

  test("a published EEG study is accepted as it stands") {
    fixture { (s, _) =>
      val study = Path.of(getClass.getResource("/multimodal-conformance/eeg-matchingpennies/dataset").toURI)
      val header = "sub-05/eeg/sub-05_task-matchingpennies_eeg.vhdr"
      for
        preview <- checked(repo(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](study)))
        _ = assertEquals(preview.candidate.assessment.errors, Vector.empty)
        receipt <- checked(repo(s).accept(s.context, preview.candidate.id, key("published-eeg")))
        release <- checked(s.catalog.release(s.context, receipt.release))
        summary = release.interpretation.levels.map(_.summary.map((group, level, files) => (group, level) -> files).toMap).getOrElse(fail("no levels"))
        _ = assertEquals(release.manifest.files.size, 54)
        _ = assertEquals(summary.get("eeg" -> CoverageLevel.Described), Some(35))
        _ = assertEquals(summary.get("sourcedata" -> CoverageLevel.Preserved), Some(7))
        _ = assertEquals(summary.get("code" -> CoverageLevel.Preserved), Some(2))
        _ = assertEquals(summary.keys.filter(_._1 == "eeg").toSet, Set("eeg" -> CoverageLevel.Described))
        // Seven participants, each with one recording stored as three BrainVision files.
        recordings <- checked(s.catalog.select(s.context, Vector(release.ref), ScientificQuery(artifacts = ArtifactPredicate.Suffix("eeg"))))
        _ = assertEquals(recordings.hits.map(_.participant).distinct.size, 7)
        _ = assertEquals(recordings.hits.size, 21)
        // The task-level sidecar at the root reaches every recording.
        metadata <- checked(repo(s).explain(s.context, release.ref, p(header)))
        _ = assertEquals(metadata.fields("TaskName").origin, p("task-matchingpennies_eeg.json"))
        _ = assert(metadata.fields.contains("SamplingFrequency"), metadata.fields.keys.toString)
      yield ()
    }
  }

  test("real-world irregularities are read, reported or kept, and never abort an import") {
    fixture { (s, root) =>
      val longNumber = "89.47720185999999"
      def imported(change: Path => Unit): IO[Preview] =
        for
          at <- fresh(root)
          _ <- described(at)
          _ <- IO.blocking(change(at))
          preview <- checked(repo(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](at)))
        yield preview
      for
        // A number with more digits than a double holds is recorded exactly as written, in a described sidecar
        // and in an assessed one. 9007199254740993 is the first integer a double cannot hold.
        exact <- imported { at =>
          write(at, eegSidecar, s"""{"TaskName":"rest","SamplingFrequency":$longNumber}""")
          write(at, SyntheticFixture.sharedSidecar, s"""{"TaskName":"rest","RepetitionTime":1.5${"0" * 150},"Counter":9007199254740993}""")
        }
        _ = assert(exact.candidate.assessment.admissible, exact.candidate.assessment.errors.toString)
        _ = assertEquals(exact.candidate.interpretation.byPath(p(eegHeader)).fields("SamplingFrequency").value, MetadataValue.Number(BigDecimal(longNumber)))
        _ = assertEquals(exact.candidate.interpretation.byPath(p(bold)).fields("Counter").value, MetadataValue.Number(BigDecimal("9007199254740993")))
        _ = assertEquals(exact.candidate.interpretation.byPath(p(bold)).fields("RepetitionTime").value, MetadataValue.Number(BigDecimal("1.5")))
        // A number too long to record is a finding against its file.
        endless <- imported(write(_, eegSidecar, s"""{"TaskName":"rest","SamplingFrequency":0.${"1" * 200}}"""))
        _ = assert(endless.candidate.assessment.errors.exists(f => f.code == "InvalidJson" && f.path.contains(p(eegSidecar))))
        // A byte-order mark and trailing blank lines, as spreadsheet exports leave them, are ignored.
        exported <- imported(write(_, "participants.tsv", "\uFEFFparticipant_id\tage\nsub-01\t25\nsub-02\t31\n\n"))
        _ = assert(exported.candidate.assessment.admissible, exported.candidate.assessment.errors.toString)
        _ = assertEquals(exported.candidate.interpretation.participants.map(_.participant), Vector("sub-01", "sub-02"))
        // A table that cannot be read is a finding that names the row at fault.
        broken <- imported(write(_, "participants.tsv", "participant_id\tage\nsub-01\t25\nsub-01\t31\n"))
        _ = assertEquals(broken.candidate.assessment.errors.map(f => f.code -> f.message), Vector("ParticipantTable" -> "participant sub-01 appears more than once"))
        // Stimulus files may have any name.
        named <- imported(at => { write(at, "stimuli/stim_107_CRF-1_01.png", "placeholder image\n"); write(at, "stimuli/notes.txt", "free text\n") })
        _ = assert(named.candidate.assessment.admissible, named.candidate.assessment.errors.toString)
        _ = assert(!named.candidate.assessment.findings.exists(_.path.exists(_.value.startsWith("stimuli/"))))
      yield ()
    }
  }

final class CoverageLevelsSuite extends CatalogCoverageSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-coverage-test-")))(TestFiles.remove)
      .use(root => ReferenceSystem.create(root.resolve("storage")).flatMap(s => run(s, root))).unsafeRunSync()
