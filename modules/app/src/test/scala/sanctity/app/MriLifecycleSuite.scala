package sanctity.app

import cats.effect.IO
import sanctity.application.{Checked, IdSource, ScientificChanges, ScientificViews}
import sanctity.bids.{BidsChangeCompiler, MriViewCompiler}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** Real external bytes in a deliberately assembled fixture; no scanner/registration qualification. */
abstract class CatalogMriSuite extends CatalogAnalysisInputSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val bold = "sub-01/ses-task/func/sub-01_ses-task_task-rest_run-1_bold.nii.gz"
  private val other = bold.replace("run-1", "run-2")
  private val phase = "sub-01/ses-cal/fmap/sub-01_ses-cal_acq-func_phasediff.nii.gz"
  private val mag1 = phase.replace("phasediff", "magnitude1")
  private val mag2 = phase.replace("phasediff", "magnitude2")
  private val sidecar = phase.stripSuffix(".nii.gz") + ".json"
  private val mask = DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime", "EchoTime1", "EchoTime2",
    "B0FieldIdentifier", "B0FieldSource", "IntendedFor"), Some(Set("onset", "duration")))
  private def phaseJson(echo: Double = 0.00765): String =
    s"""{"EchoTime1":0.00519,"EchoTime2":$echo,"B0FieldIdentifier":"gre","IntendedFor":["bids::$bold","bids::$other"]}"""
  private def write(root: Path, name: String, bytes: Array[Byte]): Unit =
    val target = root.resolve(name)
    Files.createDirectories(target.getParent)
    val _ = Files.write(target, bytes)
  private def text(root: Path, name: String, value: String): Unit = write(root, name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private def install(root: Path, second: Boolean = true): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
    val source = root.resolve("source")
    text(source, "dataset_description.json", """{"Name":"Assembled MRI lifecycle fixture","BIDSVersion":"1.11.1"}""")
    text(source, "task-rest_bold.json", """{"TaskName":"rest","RepetitionTime":2000,"B0FieldSource":"gre"}""")
    text(source, "task-rest_events.tsv", "onset\tduration\tprivate_note\n0\t0.5\thidden\n")
    Vector(bold, other).foreach(n => write(source, n, MriFixtures.bytes("example4d.nii.gz")))
    write(source, phase, MriFixtures.bytes("ds000117/" + MriFixtures.phase))
    write(source, mag1, MriFixtures.bytes("ds000117/" + MriFixtures.first))
    if second then write(source, mag2, MriFixtures.bytes("ds000117/" + MriFixtures.second))
    text(source, sidecar, phaseJson())
  }
  private def imported(s: RepositorySystem, root: Path): IO[Preview] =
    checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
  private def accepted(s: RepositorySystem, root: Path): IO[Release] =
    for
      preview <- imported(s, root)
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("mri-initial")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def prepareView(s: RepositorySystem, source: Release, profile: DisclosureProfile = mask): IO[Either[Problem, Preview]] =
    for
      selected <- checked(s.catalog.select(s.context, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
        ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("run", "1"))))))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      view <- new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, IdSource.random[IO])
        .prepare(s.context, source.ref, selected.id, destination.id, profile)
    yield view
  private def images(r: Release): Map[RelPath, Entry.File] = r.manifest.files.filter(f => f.path.value.endsWith(".nii.gz")).map(f => f.path -> f).toMap
  private def headerChecks(a: Assessment): Vector[ContentCheck] = a.checks.filter(_.rule.startsWith(bids4s.MriHeaderConsistency.Version))
  private def changes(s: RepositorySystem) = new ScientificChanges[IO](s.catalog, s.store, s.repository, new BidsChangeCompiler)

  test("real image family survives correction, cross-session subset, export and re-import with historical evidence") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        original <- accepted(s, root)
        closure <- Checked.lift[IO, RelationshipClosure](original.interpretation.relationshipClosure(Vector(p(bold))))
        _ = assert(Set(p(phase), p(mag1), p(mag2), p("task-rest_events.tsv")).subsetOf(closure.artifacts.toSet))
        _ = assert(!closure.artifacts.contains(p(other)))
        _ = assertEquals(headerChecks(original.assessment).size, 5)
        _ = assert(headerChecks(original.assessment).forall(_.inputs.exists(_.role == "image")))
        correction <- checked(s.repository.correctMetadata(s.context, original.ref, p(sidecar), phaseJson(0.008), "Correct the recorded second echo time"))
        _ = assert(correction.candidate.assessment.admissible, correction.candidate.assessment.errors.toString)
        _ = assert(Set(p(bold), p(other), p(phase)).subsetOf(correction.dependencyImpact.before.toSet))
        _ = assert(Set(p(bold), p(other), p(phase)).subsetOf(correction.dependencyImpact.after.toSet))
        receipt <- checked(s.repository.accept(s.context, correction.candidate.id, key("mri-corrected")))
        corrected <- checked(s.catalog.release(s.context, receipt.release))
        historical <- checked(s.catalog.release(s.context, original.ref))
        _ = assertEquals(historical.assessment.id, original.assessment.id)
        _ = assertEquals(historical.interpretation.id, original.interpretation.id)
        _ = assertEquals(images(corrected), images(original))
        _ = assertEquals(historical.interpretation.byPath(p(phase)).fields("EchoTime2").value, MetadataValue.Number(BigDecimal("0.00765")))
        proposal <- checked(prepareView(s, corrected))
        _ = assert(proposal.candidate.assessment.admissible, proposal.candidate.assessment.errors.toString)
        published <- checked(s.repository.accept(s.context, proposal.candidate.id, key("mri-view")))
        subset <- checked(s.catalog.release(s.context, published.release))
        _ = assertEquals(images(subset).keySet, Set(bold, phase, mag1, mag2).map(p))
        _ = images(subset).foreach((path, file) => assertEquals(file.blob, images(original)(path).blob))
        _ = assert(subset.activity.projection.get.receipt.get.matches(subset.manifest))
        _ = assertEquals(subset.activity.projection.get.receipt.get.sourceInterpretation, corrected.interpretation.id)
        _ <- checked(s.repository.exportFull(s.context, original.ref, new DirectoryExport[IO](root.resolve("old-mri"))))
        _ <- checked(s.repository.exportFull(s.context, subset.ref, new DirectoryExport[IO](root.resolve("mri-view"))))
        _ <- IO.blocking {
          assert(Files.readString(root.resolve("old-mri").resolve(sidecar)).contains("0.00765"))
          val json = Files.readString(root.resolve("mri-view").resolve(sidecar))
          assert(json.contains("0.008"))
          assert(json.contains("bids::" + bold))
          assert(!json.contains(other))
          assert(!Files.readString(root.resolve("mri-view").resolve(bold.stripSuffix("_bold.nii.gz") + "_events.tsv")).contains("private_note"))
        }
        destination <- s.repository.create(s.context, s.dataset.custodian)
        again <- checked(s.repository.importNative(s.context, destination.id, new NativeDirectorySource[IO](root.resolve("mri-view"))))
        _ = assert(again.candidate.assessment.admissible, again.candidate.assessment.errors.toString)
        reaccepted <- checked(s.repository.accept(s.context, again.candidate.id, key("mri-reimport")))
        roundtrip <- checked(s.catalog.release(s.context, reaccepted.release))
        _ = assertEquals(roundtrip.interpretation.artifacts, subset.interpretation.artifacts)
        _ = assertEquals(roundtrip.interpretation.relationships.get.relationships, subset.interpretation.relationships.get.relationships)
        _ = assertEquals(roundtrip.assessment.checks.map(c => (c.target, c.rule, c.inputs, c.facts, c.findings)),
          subset.assessment.checks.map(c => (c.target, c.rule, c.inputs, c.facts, c.findings)))
      yield ()
    }
  }

  test("missing required magnitude blocks ordinary acceptance and preserves the head") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        original <- accepted(s, root)
        edits = Vector(FileEdit.Remove(p(mag1)))
        plan <- checked(changes(s).plan(s.context, original.ref, edits))
        preview <- checked(changes(s).prepare(s.context, original.ref, edits, plan.id, "Inspect missing required magnitude"))
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "PhasediffMissingMagnitude1"))
        _ = assert(Set(p(phase), p(bold), p(other)).subsetOf(preview.dependencyImpact.before.toSet))
        blocked <- s.repository.accept(s.context, preview.candidate.id, key("mri-missing"))
        _ = assert(blocked.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, Some(original.ref.release))
      yield ()
    }
  }

  test("optional magnitude2 can be absent and a subset still preserves the complete required family") {
    fixture { (s, root) =>
      for
        _ <- install(root, second = false)
        original <- accepted(s, root)
        family = original.interpretation.relationships.get.relationships.find(_.kind == "bids.phasediff-magnitudes").get
        _ = assertEquals(family.to, Vector(p(mag1)))
        preview <- checked(prepareView(s, original))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        _ = assert(preview.candidate.manifest.byPath.contains(p(mag1)))
        _ = assert(!preview.candidate.manifest.byPath.contains(p(mag2)))
      yield ()
    }
  }

  test("ambiguous magnitude representations are rejected without selecting one arbitrarily") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking {
          val input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(MriFixtures.bytes("ds000117/" + MriFixtures.first)))
          try write(root.resolve("source"), mag1.stripSuffix(".gz"), input.readAllBytes()) finally input.close()
        }
        preview <- imported(s, root)
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "PhasediffAmbiguousMember"))
        denied <- s.repository.accept(s.context, preview.candidate.id, key("mri-ambiguous"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
      yield ()
    }
  }

  test("echo corrections and disclosure masks cannot discard required scientific meaning") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        original <- accepted(s, root)
        invalid <- checked(s.repository.correctMetadata(s.context, original.ref, p(sidecar), phaseJson(0.004), "Incorrect echo order"))
        _ = assert(invalid.candidate.assessment.errors.exists(_.code == "PhasediffEchoTimes"))
        denied <- s.repository.accept(s.context, invalid.candidate.id, key("mri-echo"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        incomplete <- prepareView(s, original, mask.copy(metadataFields = mask.metadataFields - "EchoTime1"))
        _ = assert(incomplete.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
      yield ()
    }
  }

  test("malformed real image geometry produces bound evidence and cannot advance the head") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking {
          val stream = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(MriFixtures.bytes("ds000117/" + MriFixtures.phase)))
          val bytes = try stream.readAllBytes() finally stream.close()
          java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(80, 0f)
          write(root.resolve("source"), phase, DwiTestFiles.gzip(bytes))
        }
        preview <- imported(s, root)
        check = headerChecks(preview.candidate.assessment).find(_.target == p(phase)).get
        _ = assert(check.findings.exists(_.code == "MriVoxelSize"))
        _ = assertEquals(check.inputs.find(_.role == "image").get.blob,
          preview.candidate.manifest.byPath(p(phase)).asInstanceOf[Entry.File].blob)
        denied <- s.repository.accept(s.context, preview.candidate.id, key("mri-geometry"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, None)
      yield ()
    }
  }

  test("metadata timing corrections preserve image observations and historical assessment") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        original <- accepted(s, root)
        preview <- checked(s.repository.correctMetadata(s.context, original.ref, p("task-rest_bold.json"),
          """{"TaskName":"rest","RepetitionTime":1.5,"B0FieldSource":"gre"}""", "Authored correction differs from the immutable image header"))
        _ = assert(preview.candidate.assessment.admissible)
        _ = assertEquals(headerChecks(preview.candidate.assessment).filter(_.findings.exists(_.code == "MriRepetitionTimeMismatch")).size, 2)
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("mri-tr")))
        current <- checked(s.catalog.release(s.context, receipt.release))
        old <- checked(s.catalog.release(s.context, original.ref))
        _ = assertEquals(images(current), images(original))
        _ = assertEquals(old.assessment.id, original.assessment.id)
        _ = assertEquals(headerChecks(current.assessment).find(_.target == p(bold)).get.facts("temporalSpacingSeconds"), MetadataValue.Number(2000))
      yield ()
    }
  }


  test("coordinated family renames retain lineage while a partial rename cannot be accepted") {
    fixture { (s, root) =>
      def moved(name: String) = name.replace("acq-func", "acq-cal")
      for
        _ <- install(root)
        original <- accepted(s, root)
        partial = Vector(phase, sidecar).map(n => FileEdit.Move(p(n), p(moved(n))))
        incompletePlan <- checked(changes(s).plan(s.context, original.ref, partial))
        incomplete <- checked(changes(s).prepare(s.context, original.ref, partial, incompletePlan.id, "Partial family rename"))
        _ = assert(incomplete.candidate.assessment.errors.exists(_.code == "PhasediffMissingMagnitude1"))
        denied <- s.repository.accept(s.context, incomplete.candidate.id, key("mri-partial-rename"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        edits = Vector(phase, mag1, mag2, sidecar).map(n => FileEdit.Move(p(n), p(moved(n))))
        plan <- checked(changes(s).plan(s.context, original.ref, edits))
        proposal <- checked(changes(s).prepare(s.context, original.ref, edits, plan.id, "Rename the complete fieldmap family"))
        _ = assert(proposal.candidate.assessment.admissible, proposal.candidate.assessment.errors.toString)
        receipt <- checked(s.repository.accept(s.context, proposal.candidate.id, key("mri-family-rename")))
        release <- checked(s.catalog.release(s.context, receipt.release))
        _ = Vector(phase, mag1, mag2).foreach { n =>
          assertEquals(release.manifest.byPath(p(moved(n))), images(original)(p(n)).copy(path = p(moved(n))))
        }
        closure <- Checked.lift[IO, RelationshipClosure](release.interpretation.relationshipClosure(Vector(p(bold))))
        _ = assert(Vector(phase, mag1, mag2).forall(n => closure.artifacts.contains(p(moved(n)))))
        _ = assert(Vector(phase, mag1, mag2).forall(n => !closure.artifacts.contains(p(n))))
        subset <- checked(prepareView(s, release))
        _ = assert(subset.candidate.assessment.admissible)
        old <- checked(s.catalog.release(s.context, original.ref))
        _ = assertEquals(images(old), images(original))
      yield ()
    }
  }
