package sanctity.bids

import sanctity.application.InputPlanner
import sanctity.kernel.*

/** Bounded header-QC input contract. No automatic anatomical or calibration substitution. */
final class MriInputPlanner extends InputPlanner:
  def assess(manifest: Manifest, interpretation: Interpretation, assessment: Assessment,
      selected: Vector[RelPath], contract: InputContract): Either[Problem, InputReadiness] =
    val echoMembers = interpretation.relationships.toVector.flatMap(_.relationships)
      .filter(r => r.kind == "bids.multi-echo-bold" && selected.contains(r.from)).flatMap(_.to)
    val roots = (selected ++ echoMembers).distinct.sortBy(_.value)
    if roots.isEmpty || roots.size > 100 || !contract.valid then
      Left(Problem.LimitExceeded("input planning requires 1–100 selected artifacts and a valid contract"))
    else if interpretation.content != manifest.id || assessment.interpretation != interpretation.id then
      Left(Problem.Integrity("input planning evidence is not bound to this manifest"))
    else
      val checks = Vector.newBuilder[InputRequirement]
      def check(code: String, path: Option[RelPath], ok: Boolean, failure: RequirementStatus,
          evidence: Vector[RelPath], message: String): Unit =
        checks += InputRequirement(code, path, if ok then RequirementStatus.Satisfied else failure,
          evidence.distinct.sortBy(_.value), message)
      val known = contract.name == MriInputPlanner.Contract.name && contract.version == MriInputPlanner.Contract.version &&
        contract.parameters.forall {
          case ("anatomy", v) => Set("none", "same-session")(v)
          case ("events", v) => Set("optional", "required")(v)
          case ("fieldmaps", v) => Set("optional", "required")(v)
          case _ => false
        }
      check("contract", None, known, RequirementStatus.Unsupported, Vector.empty, "Supported contract: MRI header QC v3 with declared anatomy/events/complete-fieldmap-family requirements")
      check("interpretation", None, interpretation.profile.context.contains(BidsInterpreter.describe(bids4s.BidsContext.Default)),
        RequirementStatus.Unsupported, Vector.empty, "The input planner requires its qualified semantic context")
      check("admission", None, assessment.admissible, RequirementStatus.Disallowed, Vector.empty, "Input assessment has no admission errors")
      val facts = interpretation.facts.map(f => f.path -> f).toMap
      val selectedFacts = roots.flatMap(facts.get)
      val bold = selectedFacts.filter(_.suffix == "bold")
      check("bold-required", None, bold.nonEmpty, RequirementStatus.Missing, bold.map(_.path), "Select at least one BOLD image")
      roots.foreach { path =>
        val fact = facts.get(path)
        check("acquisition", Some(path), fact.exists(f => Set("nii", "nii.gz")(f.extension) && Set("bold", "T1w")(f.suffix) &&
          !f.entities.keySet.exists(Set("part", "chunk", "recording", "flip", "inv", "mt")) &&
          (!f.entities.contains("echo") || f.suffix == "bold")),
          RequirementStatus.Unsupported, Vector(path), "BOLD echo families and explicitly selected T1w images are supported")
      }
      bold.foreach { f =>
        val metadata = interpretation.byPath.get(f.path)
        val fields = metadata.fold(Map.empty[String, EffectiveField])(_.fields)
        val sources = metadata.toVector.flatMap(_.sources)
        check("regular-timing", Some(f.path), fields.get("RepetitionTime").exists(_.value match
          case MetadataValue.Number(n) => n > 0
          case _ => false) && !fields.contains("VolumeTiming"), RequirementStatus.Unsupported, sources,
          "This recipe requires positive RepetitionTime and regular timing")
        check("task", Some(f.path), fields.get("TaskName").exists(_.value match
          case MetadataValue.Text(s) => s.nonEmpty
          case _ => false), RequirementStatus.Missing, sources, "TaskName must be available to the consumer")
        val headers = assessment.checks.filter(c => c.target == f.path && c.rule.startsWith(bids4s.MriHeaderConsistency.Version + "+"))
        val validHeader = headers.size == 1 && headers.head.facts.get("dimensions").exists {
          case MetadataValue.Array(ds) => ds.size == 4 && ds.lastOption.exists {
            case MetadataValue.Number(n) => n > 1
            case _ => false
          }
          case _ => false
        } && headers.head.facts.get("temporalSpacingSeconds").exists {
          case MetadataValue.Number(n) => n > 0
          case _ => false
        }
        check("header-timing", Some(f.path), validHeader && !headers.exists(_.findings.exists(_.code == "MriRepetitionTimeMismatch")),
          RequirementStatus.Disallowed, Vector(f.path) ++ sources, "A multi-volume header with known timing must agree with authored RepetitionTime")
        if contract.parameters.get("anatomy").contains("same-session") then
          val anatomy = selectedFacts.filter(a => a.suffix == "T1w" && a.participant == f.participant && a.session == f.session)
          check("same-session-anatomy", Some(f.path), anatomy.size == 1,
            if anatomy.isEmpty then RequirementStatus.Missing else RequirementStatus.Ambiguous,
            anatomy.map(_.path), "Exactly one explicitly selected T1w from the same participant and session is required")
        val links = interpretation.relationships.toVector.flatMap(_.relationships).filter(_.from == f.path)
        Vector("events" -> Set("bids.task-events"), "fieldmaps" -> Set("bids.b0-field-source", "bids.intended-for")).foreach { (key, kinds) =>
          // Different declarations can name individual phases or the same complete B0 family.
          val groups = links.filter(r => kinds(r.kind)).map(r => interpretation.relationshipClosure(r.to)
            .map(_.artifacts.toSet).getOrElse(r.to.toSet)).distinct
          val required = contract.parameters.get(key).contains("required")
          check(key, Some(f.path), groups.size == 1 || (!required && groups.isEmpty),
            if groups.isEmpty then RequirementStatus.Missing else RequirementStatus.Ambiguous,
            groups.flatMap(_.toVector), s"$key: explicit unambiguous dependency group${if required then " required" else " when present"}")
        }
      }
      interpretation.relationshipClosure(roots) match
        case Left(problem) => check("dependency-closure", None, false, RequirementStatus.Unsupported, roots, problem.message)
        case Right(closure) =>
          check("dependency-closure", None, (closure.artifacts ++ closure.metadataDocuments).forall(manifest.byPath.contains),
            RequirementStatus.Missing, closure.artifacts ++ closure.metadataDocuments, "Every recorded dependency must exist in the pinned input")
      Right(InputReadiness(manifest.id, interpretation.id, assessment.id, contract, roots, checks.result()))

object MriInputPlanner:
  val Contract: InputContract = InputContract("sanctity.mri-header-qc", "3")
