package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.DatasetInterpreter
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.file.{Files, Path}

/** Stands in for an earlier build of the rules: the same reading under another build fingerprint, optionally altered
  * the way a real rule change would alter it.
  */
final class EarlierBuild(inner: SemanticInterpreter[IO],
    reading: Interpretation => Either[Problem, Interpretation] = Right(_),
    findings: Vector[Finding] => Vector[Finding] = identity) extends SemanticInterpreter[IO]:
  def prepare(manifest: Manifest): IO[(Interpretation, Assessment)] =
    inner.prepare(manifest).flatMap { (interpretation, assessment) =>
      Checked.lift[IO, Interpretation](reading(interpretation).flatMap(i => i.withProfile(i.profile.copy(interpreterIdentity = EarlierBuild.Fingerprint))))
        .map(i => i -> Assessment.from(i, assessment.policy, findings(assessment.findings), assessment.checks))
    }

object EarlierBuild:
  val Fingerprint: String = "sha256:" + "0" * 64
  /** A build from before coverage levels were recorded. */
  def withoutLevels(i: Interpretation): Either[Problem, Interpretation] =
    Interpretation.from(i.content, i.profile, i.artifacts, i.facts, i.participants, i.queryVersion, i.relationships, i.declaredBidsVersion, i.observations)

/** What happens to accepted releases when the rules that read them are rebuilt. Runs against both catalogs. */
abstract class CatalogUpgradeSuite extends FunSuite:
  import CoverageFixture.*
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit

  private val corrected = """{"TaskName":"rest","SamplingFrequency":1000,"EEGReference":"Cz","PowerLineFrequency":60}"""
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def repo(s: RepositorySystem, semantics: SemanticInterpreter[IO]): NativeRepository[IO] =
    new NativeRepository[IO](s.catalog, s.store, semantics, IdSource.random[IO])
  private def current(s: RepositorySystem): NativeRepository[IO] = repo(s, new DatasetInterpreter[IO](s.store))
  private def acceptedBy(s: RepositorySystem, root: Path, earlier: SemanticInterpreter[IO]): IO[Release] =
    val at = root.resolve("source")
    for
      _ <- IO.blocking(Files.exists(at)).flatMap(exists => if exists then TestFiles.remove(at) else IO.unit)
      _ <- described(at)
      preview <- checked(repo(s, earlier).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](at)))
      receipt <- checked(repo(s, earlier).accept(s.context, preview.candidate.id, key("accepted-by-earlier-build")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release

  test("after an upgrade that reads a release the same way, a correction needs no separate step") {
    fixture { (s, root) =>
      for
        old <- acceptedBy(s, root, new EarlierBuild(new DatasetInterpreter[IO](s.store)))
        _ = assertEquals(old.interpretation.profile.interpreterIdentity, EarlierBuild.Fingerprint)
        preview <- checked(current(s).correctMetadata(s.context, old.ref, p(eegSidecar), corrected, "Recorded at 1 kHz"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        _ = assertNotEquals(preview.candidate.interpretation.profile.interpreterIdentity, EarlierBuild.Fingerprint)
        _ = assert(preview.metadataChanges.exists(c => c.path == p(eegHeader) && c.fields.exists(_.name == "SamplingFrequency")))
        receipt <- checked(current(s).accept(s.context, preview.candidate.id, key("correct-after-upgrade")))
        head <- checked(s.catalog.release(s.context, receipt.release))
        _ = assertEquals(head.parent, Some(old.ref.release))
        _ = assertEquals(head.activity.kind, ActivityKind.MetadataCorrection)
        // The accepted release itself is untouched: it still names the build that read it.
        again <- checked(s.catalog.release(s.context, old.ref))
        _ = assertEquals(again.interpretation.id, old.interpretation.id)
      yield ()
    }
  }

  test("when new rules read a release differently, edits wait for a reviewed re-assessment") {
    fixture { (s, root) =>
      for
        old <- acceptedBy(s, root, new EarlierBuild(new DatasetInterpreter[IO](s.store), EarlierBuild.withoutLevels))
        _ = assertEquals(old.interpretation.levels, None)
        refused <- current(s).correctMetadata(s.context, old.ref, p(eegSidecar), corrected, "Recorded at 1 kHz")
        _ = assert(refused.swap.toOption.exists(_.isInstanceOf[Problem.ReassessmentRequired]), refused.toString)
        unchanged <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(unchanged.head, Some(old.ref.release))
        review <- checked(current(s).reinterpret(s.context, old.ref, "Record coverage levels under the current rules"))
        _ = assertEquals(review.contentChanges, Vector.empty)
        _ = assertEquals(review.candidate.manifest.id, old.manifest.id)
        _ = assert(review.candidate.interpretation.levels.nonEmpty)
        receipt <- checked(current(s).accept(s.context, review.candidate.id, key("re-assess")))
        fresh <- checked(s.catalog.release(s.context, receipt.release))
        _ = assertEquals(fresh.activity.kind, ActivityKind.Reinterpretation)
        _ = assertEquals(fresh.manifest.entries, old.manifest.entries)
        preview <- checked(current(s).correctMetadata(s.context, fresh.ref, p(eegSidecar), corrected, "Recorded at 1 kHz"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        again <- checked(s.catalog.release(s.context, old.ref))
        _ = assertEquals(again.interpretation.id -> again.interpretation.levels, old.interpretation.id -> None)
      yield ()
    }
  }

  test("a changed verdict on unchanged facts also requires re-assessment") {
    fixture { (s, root) =>
      val retired = Finding("RetiredRule", Severity.Warning, None, "reported by a rule that has since been withdrawn")
      for
        old <- acceptedBy(s, root, new EarlierBuild(new DatasetInterpreter[IO](s.store), findings = _ :+ retired))
        now <- new DatasetInterpreter[IO](s.store).prepare(old.manifest)
        // Same facts and levels; only the verdict differs.
        _ = assertEquals(now._1.levels, old.interpretation.levels)
        _ = assert(old.assessment.findings.contains(retired) && !now._2.findings.contains(retired))
        refused <- current(s).correctMetadata(s.context, old.ref, p(eegSidecar), corrected, "Recorded at 1 kHz")
        _ = assert(refused.swap.toOption.exists(_.isInstanceOf[Problem.ReassessmentRequired]), refused.toString)
      yield ()
    }
  }

final class UpgradeSuite extends CatalogUpgradeSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-upgrade-test-")))(TestFiles.remove)
      .use(root => ReferenceSystem.create(root.resolve("storage")).flatMap(s => run(s, root))).unsafeRunSync()
