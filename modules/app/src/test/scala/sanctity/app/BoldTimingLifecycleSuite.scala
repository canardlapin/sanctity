package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.{BoldTimingChecks, MriInputPlanner, MriViewCompiler}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** Identical timing lifecycle assertions for reference and PostgreSQL catalogs. */
trait BoldTimingCases:
  self: FunSuite =>
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](result: IO[Either[Problem, A]]): IO[A] = result.flatMap(Checked.lift[IO, A])
  private def p(s: String) = RelPath.from(s).toOption.get
  private def key(s: String) = IdempotencyKey.from(s).toOption.get
  private val image = p("sub-01/func/sub-01_task-rest_bold.nii.gz")
  private val sidecar = p("task-rest_bold.json")
  private val initialJson = """{"TaskName":"rest","RepetitionTime":2,"SliceTiming":[0,0.5,0,0.5],"SliceEncodingDirection":"k-"}"""
  private val mask = DisclosureProfile(Set.empty, BoldTimingChecks.Fields + "TaskName")
  private def install(root: Path, json: String = initialJson): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
    val source = root.resolve("source")
    Files.createDirectories(source.resolve(image.value).getParent)
    Files.write(source.resolve(image.value), MriFixtures.bytes("bold-timing/v1-be-axis3.nii.gz"))
    Files.writeString(source.resolve("dataset_description.json"), """{"Name":"Timing consistency","BIDSVersion":"1.11.1"}""")
    val _ = Files.writeString(source.resolve(sidecar.value), json)
  }
  private def preview(s: RepositorySystem, root: Path): IO[Preview] =
    checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
  private def accept(s: RepositorySystem, root: Path): IO[Release] = for
    before <- preview(s, root)
    _ = assert(before.candidate.assessment.admissible, before.candidate.assessment.errors.toString)
    receipt <- checked(s.repository.accept(s.context, before.candidate.id, key("timing-source")))
    release <- checked(s.repository.release(s.context, receipt.release))
  yield release
  private def timing(a: Assessment): ContentCheck = a.checks.find(c => c.rule == BoldTimingChecks.Rule && c.target == image).get
  private def assess(r: Release, a: Assessment): InputReadiness =
    new MriInputPlanner().assess(r.manifest, r.interpretation, a, Vector(image), MriInputPlanner.Contract).toOption.get

  Vector(
    "slice count without events" -> ("""{"TaskName":"rest","RepetitionTime":2,"SliceTiming":[0,0.5],"SliceEncodingDirection":"k"}""", "BoldTimingSliceCount"),
    "volume count without events" -> ("""{"TaskName":"rest","VolumeTiming":[0,2],"FrameAcquisitionDuration":1}""", "BoldTimingVolumeCount"),
    "variable timing without acquisition context" -> ("""{"TaskName":"rest","VolumeTiming":[0,2,5]}""", "BoldTimingTimingMetadata"),
    "conflicting slice axis" -> ("""{"TaskName":"rest","RepetitionTime":2,"SliceTiming":[0,0.5,1],"SliceEncodingDirection":"j"}""", "BoldTimingAxisConflict")
  ).foreach { (name, data) =>
    test("BOLD timing admission rejects " + name) {
      fixture { (s, root) => for
        _ <- install(root, data._1)
        prepared <- preview(s, root)
        _ = assert(!prepared.candidate.interpretation.relationships.get.relationships.exists(_.kind == "bids.task-events"))
        _ = assert(prepared.candidate.assessment.errors.exists(f => f.code == data._2 && f.path.contains(sidecar)), prepared.candidate.assessment.errors.toString)
        denied <- s.repository.accept(s.context, prepared.candidate.id, key("invalid-timing"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        dataset <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(dataset.head, None)
      yield () }
    }
  }

  test("BOLD timing persists origins and coherent voxel order with no event dependency") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- accept(s, root)
      check = timing(source.assessment)
      _ = assertEquals(check.facts("sliceTimesByVoxelIndex"), MetadataValue.Array(Vector(0.5, 0.0, 0.5, 0.0).map(n => MetadataValue.Number(BigDecimal(n)))))
      _ = assertEquals(check.inputs.map(_.path).toSet, Set(image, sidecar))
      _ = assert(check.facts("timingMetadata").toString.contains(sidecar.value))
      _ = assert(assess(source, source.assessment).ready)
      _ <- checked(s.repository.exportFull(s.context, source.ref, new DirectoryExport[IO](root.resolve("timing-source-export"))))
      _ <- IO.blocking {
        assertEquals(Files.readString(root.resolve("timing-source-export").resolve(sidecar.value)), initialJson)
        assertEquals(Digest.bytes(Files.readAllBytes(root.resolve("timing-source-export").resolve(image.value))), source.manifest.files.find(_.path == image).get.blob.digest)
      }
    yield () }
  }

  test("BOLD timing correction changes evidence while history and image bytes stay exact") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- accept(s, root)
      edit <- checked(s.repository.correctMetadata(s.context, source.ref, sidecar, initialJson.replace("k-", "k"), "Correct authored slice direction"))
      _ = assert(edit.candidate.assessment.admissible)
      receipt <- checked(s.repository.accept(s.context, edit.candidate.id, key("timing-direction")))
      after <- checked(s.repository.release(s.context, receipt.release))
      old <- checked(s.repository.release(s.context, source.ref))
      _ = assertEquals(old.assessment.id, source.assessment.id)
      _ = assertNotEquals(timing(after.assessment).facts("sliceTimesByVoxelIndex"), timing(old.assessment).facts("sliceTimesByVoxelIndex"))
      _ = assertNotEquals(timing(after.assessment).inputs.find(_.path == sidecar), timing(old.assessment).inputs.find(_.path == sidecar))
      _ = assertEquals(after.manifest.files.find(_.path == image), old.manifest.files.find(_.path == image))
      bad <- checked(s.repository.correctMetadata(s.context, after.ref, sidecar, initialJson.replace("k-", "j-"), "Conflicting direction"))
      rejected <- s.repository.accept(s.context, bad.candidate.id, key("timing-conflict"))
      _ = assert(rejected.isLeft)
      current <- checked(s.catalog.dataset(s.context, s.dataset.id))
      _ = assertEquals(current.head, Some(after.ref.release))
    yield () }
  }

  test("BOLD views preserve slice direction and reproduce timing on re-import") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- accept(s, root)
      selected <- checked(s.catalog.select(s.context, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.Suffix("bold"))))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      views = new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, IdSource.random[IO])
      refused <- views.prepare(s.context, source.ref, selected.id, destination.id, mask.copy(metadataFields = mask.metadataFields - "SliceEncodingDirection"))
      _ = assert(refused.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
      prepared <- checked(views.prepare(s.context, source.ref, selected.id, destination.id, mask))
      receipt <- checked(s.repository.accept(s.context, prepared.candidate.id, key("timing-view")))
      view <- checked(s.repository.release(s.context, receipt.release))
      _ = assertEquals(timing(view.assessment).facts - "timingMetadata", timing(source.assessment).facts - "timingMetadata")
      _ = assert(view.activity.projection.get.receipt.get.matches(view.manifest))
      _ <- checked(s.repository.exportFull(s.context, view.ref, new DirectoryExport[IO](root.resolve("timing-view"))))
      destination2 <- s.repository.create(s.context, s.dataset.custodian)
      imported <- checked(s.repository.importNative(s.context, destination2.id, new NativeDirectorySource[IO](root.resolve("timing-view"))))
      _ = assert(imported.candidate.assessment.admissible)
      _ = assertEquals(timing(imported.candidate.assessment).facts, timing(view.assessment).facts)
      _ = assertEquals(timing(imported.candidate.assessment).inputs, timing(view.assessment).inputs)
    yield () }
  }

  test("BOLD input readiness refuses missing or substituted timing inputs") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- accept(s, root)
      _ = assert(assess(source, source.assessment).ready)
      good = timing(source.assessment)
      rest = source.assessment.checks.filterNot(_.rule == BoldTimingChecks.Rule)
      forged = ContentCheck.from(good.rule, good.target, good.inputs.filter(_.role == "image"), good.facts, Vector.empty).toOption.get
      _ = Vector(rest, rest :+ forged).foreach { checks =>
        val a = Assessment.from(source.interpretation, source.assessment.policy, source.assessment.findings, checks)
        val ready = assess(source, a)
        assert(!ready.ready)
        assert(ready.requirements.exists(r => r.code == "sampling-metadata" && r.status == RequirementStatus.Missing))
      }
    yield () }
  }

  test("BOLD without optional slice timing remains ready; variable sampling retains its explicit end") {
    fixture { (s, root) => for
      _ <- install(root, """{"TaskName":"rest","RepetitionTime":2}""")
      source <- accept(s, root)
      _ = assert(assess(source, source.assessment).ready)
      edit <- checked(s.repository.correctMetadata(s.context, source.ref, sidecar,
        """{"TaskName":"rest","VolumeTiming":[5,7,10],"FrameAcquisitionDuration":1}""", "Record irregular volume starts"))
      receipt <- checked(s.repository.accept(s.context, edit.candidate.id, key("variable-timing")))
      variable <- checked(s.repository.release(s.context, receipt.release))
      _ = assertEquals(timing(variable.assessment).facts("comparisonEndSeconds"), MetadataValue.Number(6))
      _ = assertEquals(timing(variable.assessment).facts("volumeTimingOriginSeconds"), MetadataValue.Number(5))
      _ = assert(!assess(variable, variable.assessment).ready) // existing recipes require regular sampling
    yield () }
  }

final class BoldTimingLifecycleSuite extends FunSuite with BoldTimingCases:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-bold-timing-")))(TestFiles.remove).use { root =>
      SyntheticFixture.create(root.resolve("source")) *> ReferenceSystem.create(root.resolve("storage")).flatMap(run(_, root))
    }.unsafeRunSync()
