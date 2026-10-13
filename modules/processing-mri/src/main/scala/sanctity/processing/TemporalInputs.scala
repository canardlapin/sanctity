package sanctity.processing

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.*
import sanctity.bids.{BidsInterpreter, MriInputPlanner}
import sanctity.kernel.*

/** Opt-in scientific profile: historical/native header-only interpretations remain unchanged. */
final class PayloadBidsInterpreter[F[_]: Async](store: BlobStore[F]) extends SemanticInterpreter[F]:
  private val base = new BidsInterpreter[F](store)
  private val reader = new NiftiPayload[F](store)
  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    for
      original <- base.prepare(manifest)
      (i, a) = original
      checks <- i.facts.filter(f => f.suffix == "bold" && Set("nii", "nii.gz")(f.extension)).traverse { f =>
        val image = manifest.files.find(_.path == f.path).get
        reader.inspect(image).flatMap { result =>
          Checked.lift[F, ContentCheck](ContentCheck.from(NiftiPayload.Rule, f.path, Vector(CheckInput("image", f.path, image.blob)),
            result.fold(_ => Map("complete" -> MetadataValue.Flag(false)), _.facts),
            result.left.toOption.map(p => Finding("NiftiPayload", Severity.Error, Some(f.path), p.message)).toVector))
        }
      }
      interpreted <- Checked.lift[F, Interpretation](Interpretation.from(manifest.id,
        i.profile.copy(name = "native-payload-v1", coverage = i.profile.coverage + "; full bounded scalar BOLD payload v1"),
        i.artifacts, i.facts, i.participants, i.queryVersion, i.relationships, i.declaredBidsVersion, i.observations))
    yield interpreted -> Assessment.from(interpreted, "native-payload-admission-v1", a.findings, a.checks ++ checks)

final class TemporalInputPlanner extends InputPlanner:
  def assess(manifest: Manifest, interpretation: Interpretation, assessment: Assessment,
      roots: Vector[RelPath], contract: InputContract): Either[Problem, InputReadiness] =
    new MriInputPlanner().assess(manifest, interpretation, assessment, roots, MriInputPlanner.Contract).map { baseline =>
      val requirements = roots.distinct.sortBy(_.value).map { p =>
        val image = manifest.files.find(_.path == p)
        val checked = assessment.checks.exists(c => c.target == p && c.rule == NiftiPayload.Rule &&
          c.facts.get("complete").contains(MetadataValue.Flag(true)) && c.findings.isEmpty &&
          image.exists(f => c.inputs.contains(CheckInput("image", p, f.blob))))
        InputRequirement("payload", Some(p), if checked then RequirementStatus.Satisfied else RequirementStatus.Missing,
          Vector(p), "Full scalar payload and scaling assessment must be present for this exact image")
      }
      val onlyBold = roots.forall(p => interpretation.facts.exists(f => f.path == p && f.suffix == "bold" && !f.entities.contains("echo")))
      val outputs = roots.flatMap(p => TemporalStatistics.Statistics.map(TemporalStatistics.output(p, _)))
      val known = outputs.distinct.size == outputs.size && contract == TemporalStatistics.Contract && onlyBold && roots.size <= TemporalStatistics.MaxTargets
      baseline.copy(contract = contract, requirements = baseline.requirements ++ requirements :+
        InputRequirement("temporal-recipe", None, if known then RequirementStatus.Satisfied else RequirementStatus.Unsupported,
          Vector.empty, "Temporal statistics v1: 1–8 single-echo BOLD series, all volumes, population SD, float64 output"))
    }
