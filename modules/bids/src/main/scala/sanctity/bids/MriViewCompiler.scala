package sanctity.bids

import cats.syntax.all.*
import io.circe.{Json, Printer}
import sanctity.application.ViewCompiler
import sanctity.kernel.*

/** Qualified structural closure only for T1w/T2w and single-echo resting BOLD.
  * Linked fieldmaps, events/physiology, file collections and other modalities fail closed.
  * Flattening effective JSON removes inherited private fields without mutating the source.
  */
final class MriViewCompiler extends ViewCompiler:
  private val unsupportedFields = Set("IntendedFor", "B0FieldSource", "B0FieldIdentifier", "Sources",
    "RawSources", "AssociatedEmptyRoom", "M0Scan", "AnatomicalLandmarkCoordinates")
  private val printer = Printer.spaces2.copy(sortKeys = true)

  def plan(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, ProjectionPlan] =
    val linkedTargets = source.interpretation.relationships.toVector.flatMap(_.relationships.map(_.from)).toSet
    for
      _ <- Either.cond(source.interpretation.relationships.exists(_.version == bids4s.BidsFieldmapResolver.Version), (),
        Problem.Unsupported("view requires an interpretation with the current explicit relationship profile"))
      _ <- Either.cond(selection.releases == Vector(source.ref) && selection.hits.nonEmpty, (),
        Problem.Unsupported("MRI view needs a nonempty selection from exactly one release"))
      _ <- Either.cond(profile.participantFields.forall(_.matches("[A-Za-z][A-Za-z0-9_]*")) &&
        !profile.participantFields.contains("participant_id"), (), Problem.Unsupported("invalid participant field mask"))
      targets <- selection.hits.traverse { hit =>
        for
          facts <- source.interpretation.facts.find(_.path == hit.path).toRight(Problem.Integrity("selection lacks scientific facts"))
          file <- source.manifest.byPath.get(hit.path).collect { case f: Entry.File => f }.toRight(Problem.Integrity("selection file missing"))
          metadata <- source.interpretation.byPath.get(hit.path).toRight(Problem.Integrity("selection metadata missing"))
          _ <- Either.cond(Set("nii", "nii.gz").contains(facts.extension) && Set("T1w", "T2w", "bold").contains(facts.suffix) &&
            !facts.entities.keySet.exists(Set("echo", "part", "chunk", "recording")) &&
            (facts.suffix != "bold" || facts.entities.get("task").contains("rest")), (),
            Problem.Unsupported("view profile supports T1w/T2w and single-echo resting-state BOLD NIfTI"))
          _ <- Either.cond(!linkedTargets(hit.path), (),
            Problem.Unsupported("view needs an explicit relationship closure and reference-rewriting profile"))
          _ <- Either.cond(!metadata.fields.keySet.exists(unsupportedFields), (),
            Problem.Unsupported("view requires an unsupported association or file dependency"))
          companions = source.interpretation.facts.filter(f => f.participant == facts.participant && f.session == facts.session)
          _ <- Either.cond(!companions.exists(f => Set("events", "physio", "stim", "sbref", "epi", "fieldmap", "phasediff", "phase1", "phase2").contains(f.suffix)), (),
            Problem.Unsupported("view contains acquisitions with unsupported companions; use a fuller closure profile"))
          required = if facts.suffix == "bold" then Set("TaskName", "RepetitionTime") else Set.empty[String]
          _ <- Either.cond(required.subsetOf(profile.metadataFields) && required.subsetOf(metadata.fields.keySet), (),
            Problem.Unsupported("disclosure mask must preserve required BOLD timing/task metadata"))
          jsonPath <- RelPath.from(file.path.value.stripSuffix("." + facts.extension) + ".json")
          json = Json.obj(metadata.fields.toVector.filter((name, _) => profile.metadataFields.contains(name))
            .map((name, field) => name -> encode(field.value))*)
        yield (file, jsonPath -> (printer.print(json) + "\n"))
      }
      subjects = selection.hits.map(_.participant).distinct.sorted
      columns = Vector("participant_id") ++ profile.participantFields.toVector.sorted
      participantRows = source.interpretation.participants.map(p => p.participant -> p.fields).toMap
      _ <- Either.cond(subjects.forall(s => profile.participantFields.isEmpty || participantRows.contains(s)), (),
        Problem.Unsupported("requested participant metadata is unavailable"))
      table = (columns +: subjects.map(s => Vector(s) ++ columns.tail.map(n => participantRows.get(s).flatMap(_.get(n)).getOrElse("n/a"))))
        .map(_.mkString("\t")).mkString("", "\n", "\n")
      participantPath <- RelPath.from("participants.tsv")
      descriptionPath <- RelPath.from("dataset_description.json")
      generated = targets.map(_._2) ++ Vector(participantPath -> table,
        descriptionPath -> (printer.print(Json.obj("Name" -> Json.fromString("Authorized scientific view"),
          "BIDSVersion" -> Json.fromString("1.10.0"), "DatasetType" -> Json.fromString("raw"))) + "\n"))
      evidence = ProjectionEvidence(source.ref, selection.id, profile.id, "sanctity-mri-view-v2", generated.map(_._1))
    yield ProjectionPlan(targets.map(_._1), generated, evidence)

  private def encode(value: MetadataValue): Json = value match
    case MetadataValue.Text(v) => Json.fromString(v)
    case MetadataValue.Number(v) => Json.fromBigDecimal(v)
    case MetadataValue.Flag(v) => Json.fromBoolean(v)
    case MetadataValue.Null => Json.Null
    case MetadataValue.Array(v) => Json.fromValues(v.map(encode))
    case MetadataValue.Record(v) => Json.obj(v.toVector.map((n, v) => n -> encode(v))*)
