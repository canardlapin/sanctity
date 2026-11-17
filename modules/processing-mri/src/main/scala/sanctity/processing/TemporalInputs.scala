package sanctity.processing

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.*
import sanctity.bids.{BidsInterpreter, MriInputPlanner}
import sanctity.kernel.*

/** Opt-in scientific profile: historical/native header-only interpretations remain unchanged. */
final class PayloadBidsInterpreter[F[_]: Async](store: BlobStore[F], qualifyGeometry: Boolean = false, qualifyCalibration: Boolean = false) extends SemanticInterpreter[F]:
  private val base = new BidsInterpreter[F](store)
  private val reader = new NiftiPayload[F](store)
  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    for
      original <- base.prepare(manifest)
      (i, a) = original
      observed <- i.facts.filter(f => (f.suffix == "bold" || (qualifyCalibration && Set("sbref", "epi")(f.suffix))) && Set("nii", "nii.gz")(f.extension)).traverse { f =>
        val image = manifest.files.find(_.path == f.path).get
        reader.inspect(image).map(result => (image, result))
      }
      checks <- observed.traverse { (image, result) =>
        Checked.lift[F, ContentCheck](ContentCheck.from(NiftiPayload.Rule, image.path, Vector(CheckInput("image", image.path, image.blob)),
          result.fold(_ => Map("complete" -> MetadataValue.Flag(false)), _.facts),
          result.left.toOption.map(p => Finding("NiftiPayload", Severity.Error, Some(image.path), p.message)).toVector))
      }
      geometry <- if !qualifyGeometry && !qualifyCalibration then Async[F].pure(Vector.empty[ContentCheck]) else
        observed.traverse { (image, result) =>
          val qualified = result.flatMap(o => NiftiGeometry.inspect(o.header))
          Checked.lift[F, ContentCheck](ContentCheck.from(NiftiGeometry.Rule, image.path, Vector(CheckInput("image", image.path, image.blob)),
            qualified.fold(_ => Map("qualified" -> MetadataValue.Flag(false)), _.facts),
            qualified.left.toOption.map(p => Finding("NiftiGeometry", Severity.Error, Some(image.path), p.message)).toVector))
        }
      families <- if !qualifyGeometry && !qualifyCalibration then Async[F].pure(Vector.empty[ContentCheck]) else
        NiftiGeometry.families(i).traverse { members =>
          val qualified = members.traverse(p => observed.find(_._1.path == p).toRight(Problem.Integrity("missing echo payload"))
            .flatMap(_._2).flatMap(o => NiftiGeometry.inspect(o.header))).flatMap(NiftiGeometry.agreement)
          Checked.lift[F, ContentCheck](ContentCheck.from(NiftiGeometry.FamilyRule, members.head,
            members.zipWithIndex.map((p, index) => CheckInput(s"echo-$index", p, manifest.files.find(_.path == p).get.blob)),
            Map("qualified" -> MetadataValue.Flag(qualified.isRight), "cornerToleranceMm" -> NiftiPayload.number(NiftiGeometry.CornerToleranceMm)) ++
              qualified.toOption.map(n => "maximumCornerDistanceMm" -> NiftiPayload.number(n)),
            qualified.left.toOption.map(p => Finding("MultiEchoGeometry", Severity.Error, Some(members.head), p.message)).toVector))
        }
      calibration <- if !qualifyCalibration then Async[F].pure(Vector.empty[ContentCheck]) else
        Checked.lift[F, Vector[ContentCheck]](CalibrationInputs.geometry(manifest, i, observed.map((f, r) => f.path -> r).toMap))
      version = if qualifyCalibration then "3" else if qualifyGeometry then "2" else "1"
      interpreted <- Checked.lift[F, Interpretation](Interpretation.from(manifest.id,
        i.profile.copy(name = "native-payload-v" + version, coverage = i.profile.coverage + "; full bounded scalar BOLD payload v1" +
          (if qualifyGeometry || qualifyCalibration then "; coded geometry and echo agreement v1" else "") +
          (if qualifyCalibration then "; SBRef/EPI payload and same-grid calibration evidence v1" else "")),
        i.artifacts, i.facts, i.participants, i.queryVersion, i.relationships, i.declaredBidsVersion, i.observations))
    yield interpreted -> Assessment.from(interpreted, "native-payload-admission-v" + version, a.findings, a.checks ++ checks ++ geometry ++ families ++ calibration)

final class TemporalInputPlanner extends InputPlanner:
  def assess(manifest: Manifest, interpretation: Interpretation, assessment: Assessment,
      roots: Vector[RelPath], contract: InputContract): Either[Problem, InputReadiness] =
    new MriInputPlanner().assess(manifest, interpretation, assessment, roots, MriInputPlanner.Contract).map { baseline =>
      val v2 = contract == TemporalStatistics.MultiEchoContract || contract == TemporalStatistics.CalibratedContract
      val targets = if v2 then baseline.roots else roots.distinct.sortBy(_.value)
      val files = manifest.files.map(f => f.path -> f).toMap
      def bound(p: RelPath, rule: String, flag: String): Boolean =
        assessment.checks.count(c => c.target == p && c.rule == rule &&
          c.facts.get(flag).contains(MetadataValue.Flag(true)) && c.findings.isEmpty &&
          files.get(p).exists(f => c.inputs == Vector(CheckInput("image", p, f.blob)))) == 1
      val requirements = targets.flatMap { p =>
        Vector(InputRequirement("payload", Some(p), if bound(p, NiftiPayload.Rule, "complete") then RequirementStatus.Satisfied else RequirementStatus.Missing,
          Vector(p), "Full scalar payload and scaling assessment must be present for this exact image")) ++
          Option.when(v2)(InputRequirement("coded-geometry", Some(p), if bound(p, NiftiGeometry.Rule, "qualified") then RequirementStatus.Satisfied else RequirementStatus.Missing,
            Vector(p), "Exact image requires qualified coded geometry under the opt-in payload v2 profile"))
      }
      val families = if !v2 then Vector.empty else NiftiGeometry.families(interpretation).filter(_.exists(targets.contains)).map { members =>
        val inputs = members.zipWithIndex.flatMap((p, n) => files.get(p).map(f => CheckInput(s"echo-$n", p, f.blob))).sortBy(_.role)
        val complete = members.forall(targets.contains) && inputs.size == members.size && assessment.checks.count(c =>
          c.rule == NiftiGeometry.FamilyRule && c.target == members.head && c.inputs == inputs && c.findings.isEmpty &&
            c.facts.get("qualified").contains(MetadataValue.Flag(true))) == 1
        InputRequirement("echo-geometry", Some(members.head), if complete then RequirementStatus.Satisfied else RequirementStatus.Missing,
          members, "Every expanded echo requires exact family geometry agreement evidence")
      }
      val onlyBold = targets.forall(p => interpretation.facts.exists(f => f.path == p && f.suffix == "bold" &&
        (v2 || !f.entities.contains("echo"))))
      val recorded = targets.forall(p => !interpretation.facts.exists(f => f.path == p && f.entities.contains("echo")) ||
        NiftiGeometry.families(interpretation).exists(_.contains(p)))
      val outputs = targets.flatMap(p => TemporalStatistics.Statistics.map(TemporalStatistics.output(p, _)))
      val known = outputs.distinct.size == outputs.size && TemporalStatistics.supports(contract) && onlyBold && recorded &&
        targets.nonEmpty && targets.size <= TemporalStatistics.maxTargets(contract)
      val closure = interpretation.relationshipClosure(targets).toOption.toVector.flatMap(c => c.artifacts ++ c.metadataDocuments).toSet ++ targets
      val bytes = files.valuesIterator.filter(f => closure(f.path)).map(f => BigInt(f.blob.size)).sum
      val baselineRequirements = if contract == TemporalStatistics.CalibratedContract then
        baseline.requirements.filterNot(_.code == "fieldmaps") else baseline.requirements
      baseline.copy(contract = contract, roots = targets, requirements = baselineRequirements ++ requirements ++ families ++
        (if contract == TemporalStatistics.CalibratedContract then CalibrationInputs.requirements(manifest, interpretation, assessment, targets) else Vector.empty) ++ Vector(
        InputRequirement("temporal-recipe", None, if known then RequirementStatus.Satisfied else RequirementStatus.Unsupported,
          Vector.empty, "Temporal v1: 1–8 single-echo images; v2/v3: 1–16 expanded BOLD images with qualified geometry; v3 requires SBRef and a same-grid EPI pair; all volumes, population SD, float64"),
        InputRequirement("temporal-bytes", None, if bytes <= NiftiPayload.MaxBytes then RequirementStatus.Satisfied else RequirementStatus.Disallowed,
          targets, "Selected image/dependency bytes must fit the 8 GiB materialization budget; the producer checks the entire emitted bundle")))
    }
