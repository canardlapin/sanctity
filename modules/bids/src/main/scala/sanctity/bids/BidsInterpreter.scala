package sanctity.bids

import bids4s.{BidsContext, BidsCompanionResolver, BidsCompanionKind, BidsCompanionOutcome, BidsFieldmapResolver, BidsMetadataResolution, BidsIssue, BidsIssueSeverity, BidsManifest, BidsMetadataResolver, BidsMetadataValidation, BidsPath, JsonValue}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{BlobStore, Checked, SemanticInterpreter}
import sanctity.kernel.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Bounded reference profile. This is not an external BIDS conformance validator. */
final class BidsInterpreter[F[_]: Async](store: BlobStore[F], context: BidsContext = BidsContext.Default) extends SemanticInterpreter[F]:
  private val jsonLimit = 4L * 1024 * 1024
  private val totalLimit = 32L * 1024 * 1024
  private val parser = new JawnParser(maxValueSize = Some(4 * 1024 * 1024), allowDuplicateKeys = false)
  val profile: SemanticProfile = SemanticProfile(
    "native-reference-v11", BuildIdentity.bids4s, BuildIdentity.sanctity,
    "Context-selected bids4s names and JSON inheritance; basic description/timing checks. Strict participant TSV and scoped query facts. Explicit NIfTI B0 fieldmap groups and IntendedFor links. Inherited DWI gradients and BOLD event companions. DWI gradient parsing, scalar NIfTI-1/2 header counts and versioned norm checks. Strict selected BOLD event tables, explicit regular/variable timing context and contextual warnings. Phase-difference and phase1/phase2 magnitude families with exact echo-time origins. Bounded MRI dimensions, units, coded transform structure and TR/header comparison; discrepancies remain observations. Multi-echo BOLD families, exact functional SBRef companions and local event stimulus references. Every-BOLD volume and slice timing with declared NIfTI slice-axis evidence. No HED validation, anatomical alignment, gradient orientation, gzip payload or full BIDS conformance validation.",
    Some(BidsInterpreter.describe(context))
  )

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    val jsonFiles = manifest.files.filter(f => f.path.fileName.endsWith(".json") && !f.path.value.startsWith("stimuli/"))
    for
      _ <- Checked.lift[F, Unit](Either.cond(
        jsonFiles.forall(_.blob.size <= jsonLimit) && jsonFiles.map(_.blob.size).sum <= totalLimit,
        (), Problem.LimitExceeded("reference profile allows 4 MiB per JSON document and 32 MiB total JSON")
      ))
      decoded <- jsonFiles.traverse { file =>
        store.read(file.blob).compile.to(Array).map { bytes =>
          val parsed = for
            text <- Try(StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes)).toString).toEither.leftMap(_.getMessage)
            json <- parser.parse(text).leftMap(_.message)
            value <- convert(json)
            obj <- value match
              case obj: JsonValue.Obj => Right(obj)
              case _ => Left("metadata must be a JSON object")
            written <- json.asObject.toRight("metadata must be a JSON object").flatMap(_.toVector.traverse((k, v) => authored(v).map(k -> _)))
          yield obj -> written.toMap
          file.path -> parsed
        }
      }
      table <- manifest.files.find(_.path.value == "participants.tsv").traverse { file =>
        Checked.lift[F, Unit](Either.cond(file.blob.size <= jsonLimit, (), Problem.LimitExceeded("participant table exceeds 4 MiB"))) *>
          store.read(file.blob).compile.to(Array).map(bytes => file.path -> ParticipantTable.parse(bytes))
      }
      // A table the profile cannot read is reported against the file; it does not abort the preparation.
      participantFindings = table.toVector.collect { case (path, Left(reason)) => Finding("ParticipantTable", Severity.Error, Some(path), reason) }
      participants = table.flatMap(_._2.toOption).map(ParticipantTable.facts).getOrElse(Vector.empty)
      exact = decoded.collect { case (path, Right((_, written))) => path -> written }.toMap
      result <- Checked.lift[F, (Interpretation, Assessment)](interpret(manifest, decoded.map((path, r) => path -> r.map(_._1)), exact, participants))
      (initial, assessment) = result
      enriched <- new StimulusBindings[F](store).enrich(manifest, initial)
      (interpretation, stimulusFindings) = enriched
      inspected <- new MriHeaderChecks[F](store).inspect(manifest, interpretation)
      (headerChecks, headers) = inspected
      dwiChecks <- new DwiContentChecks[F](store).assess(manifest, interpretation, headers)
      eventChecks <- new EventContentChecks[F](store).assess(manifest, interpretation, headers)
      echoChecks <- new MultiEchoChecks[F](store).assess(manifest, interpretation, headers)
      sbrefChecks <- Checked.lift[F, Vector[ContentCheck]](SbrefBindings.assess(manifest, interpretation, headers))
    yield interpretation -> Assessment.from(interpretation, assessment.policy, assessment.findings ++ stimulusFindings ++ participantFindings, dwiChecks ++ eventChecks ++ headerChecks ++ echoChecks ++ sbrefChecks)

  private def interpret(manifest: Manifest, decoded: Vector[(RelPath, Either[String, JsonValue.Obj])],
      exact: Map[RelPath, Map[String, MetadataValue]], participants: Vector[ParticipantFacts]): Either[Problem, (Interpretation, Assessment)] =
    val checked = BidsManifest.fromRelativePathsChecked(manifest.files.map(_.path.value), context = context)
    val sidecars = decoded.collect { case (path, Right(json)) => BidsPath(path.value) -> json }.toMap
    val resolver = BidsMetadataResolver(checked.value, sidecars)
    val findings = Vector.newBuilder[Finding]
    checked.issues.foreach(issue => findings += finding(issue))
    decoded.foreach {
      case (path, Left(message)) => findings += Finding("InvalidJson", Severity.Error, Some(path), message)
      case (path, Right(json)) if path.fileName == "dataset_description.json" =>
        BidsMetadataValidation.datasetDescription(BidsPath(path.value), json).foreach(issue => findings += finding(issue))
      case _ => ()
    }
    if !manifest.files.exists(_.path.value == "dataset_description.json") then
      findings += Finding("MissingDescription", Severity.Error, None, "root dataset_description.json is required")

    manifest.files.filter(file => file.path.fileName == "dataset_description.json" && file.path.value != "dataset_description.json").foreach { file =>
      findings += Finding("UnsupportedComposition", Severity.Error, Some(file.path), "reference profile supports one raw dataset; nested datasets require a composition profile")
    }
    sidecars.get(BidsPath("dataset_description.json")).flatMap(_.fields.get("DatasetType")).foreach {
      case JsonValue.Str("raw") => ()
      case _ => findings += Finding("UnsupportedProfile", Severity.Error, None, "reference profile supports raw datasets")
    }

    val targets = manifest.files.filter { file =>
      !file.path.value.startsWith("stimuli/") && !file.path.fileName.endsWith(".json") &&
        context.parse(file.path.fileName).orElse(context.parseGeneric(file.path.fileName)).isRight
    }
    val resolutions = Vector.newBuilder[BidsMetadataResolution]
    val artifacts = targets.flatMap { file =>
      resolver.resolve(BidsPath(file.path.value)) match
        case Left(error) =>
          findings += Finding("MetadataResolution", Severity.Error, Some(file.path), error.message)
          None
        case Right(resolved) =>
          resolutions += resolved
          BidsMetadataValidation.resolvedSidecar(BidsPath(file.path.value), resolved.metadata).foreach(issue => findings += finding(issue))
          // A field's value is recorded as its source wrote it. The library's double is used only to compute with.
          def written(source: RelPath, name: String, computed: JsonValue): MetadataValue =
            val approximate = metadata(computed)
            exact.get(source).flatMap(_.get(name)).filter(v => BidsInterpreter.computed(v) == approximate).getOrElse(approximate)
          val fields = resolved.metadata.fields.map { (name, value) =>
            val origin = relative(resolved.origins(name))
            name -> EffectiveField(written(origin, name, value), origin,
              resolved.history(name).map(c => Contribution(relative(c.source), written(relative(c.source), name, c.value))))
          }
          Some(ArtifactMetadata(file.path, fields, resolved.sources.map(s => relative(s.path)), resolved.datasetRoot.map(relative)))
    }
    def artifactFact(path: RelPath): Option[ArtifactFacts] =
      context.parsePath(BidsPath(path.value)).toOption.flatMap { name =>
        val entities = name.entities.values.map((key, value) => key.short -> value).toMap
        entities.get("sub").map { subject =>
          ArtifactFacts(path, s"sub-$subject", entities.get("ses").map("ses-" + _), name.kind, name.extension, entities)
        }
      }
    val facts = artifacts.flatMap(a => artifactFact(a.path))
    val resolved = resolutions.result().map(m => m.target -> m).toMap
    val families = bids4s.BidsPhaseDifferenceResolver.fromResolved(checked.value, resolved)
    families.issues.foreach(i => findings += Finding("Phasediff" + i.code.toString, Severity.Error, Some(relative(i.path)), i.message))
    val familyLinks = families.groups.map { g =>
      ScientificRelationship(relative(g.phase), "bids.phasediff-magnitudes", g.magnitudes.map(relative), None,
        g.magnitudes.map(p => RelationshipEvidence.CompanionSelection(relative(g.phase), relative(p),
          bids4s.BidsPhaseDifferenceResolver.Version, g.datasetRoot.map(relative), g.entities, Vector(relative(p)))))
    }
    val echoes = bids4s.BidsMultiEchoResolver.fromResolved(checked.value, resolved)
    echoes.issues.foreach(i => findings += Finding("MultiEcho" + i.code.toString, Severity.Error, Some(relative(i.path)), i.message))
    val echoLinks = echoes.groups.flatMap { g =>
      g.members.map { member =>
        ScientificRelationship(relative(member), "bids.multi-echo-bold", g.members.filterNot(_ == member).map(relative), None,
          g.members.filterNot(_ == member).map(p => RelationshipEvidence.CompanionSelection(relative(member), relative(p),
            bids4s.BidsMultiEchoResolver.Version, g.datasetRoot.map(relative), g.entities, Vector(relative(p)))) ++
            g.evidence.map(e => RelationshipEvidence(relative(e.artifact), relative(e.document), e.field)))
      }
    }
    val (resolvedSbrefs, sbrefFindings) = SbrefBindings.resolve(targets.flatMap(f => artifactFact(f.path)))
    val resolvedArtifacts = artifacts.map(_.path).toSet
    val sbrefLinks = resolvedSbrefs.filter(r => (r.from +: r.to).forall(resolvedArtifacts))
    if sbrefLinks.size != resolvedSbrefs.size then
      findings += Finding("SbrefMetadata", Severity.Error, None, "Applicable SBRef or BOLD metadata could not be resolved")
    sbrefFindings.foreach(findings += _)
    val fieldmaps = BidsFieldmapResolver.fromResolved(checked.value, resolved)
    val phasePairs = bids4s.BidsPhasePairResolver.fromResolved(checked.value, resolved)
    phasePairs.issues.foreach(i => findings += Finding("PhasePair" + i.code.toString, Severity.Error, Some(relative(i.path)), i.message))
    val pairLinks = phasePairs.groups.flatMap { g =>
      g.members.map { member =>
        ScientificRelationship(relative(member), "bids.phase-pair-family", g.members.filterNot(_ == member).map(relative), None,
          g.members.filterNot(_ == member).map(p => RelationshipEvidence.CompanionSelection(relative(member), relative(p),
            bids4s.BidsPhasePairResolver.Version, g.datasetRoot.map(relative), g.entities, Vector(relative(p)))) ++
            g.evidence.map(e => RelationshipEvidence(relative(e.artifact), relative(e.document), e.field)))
      }
    }
    fieldmaps.issues.foreach { issue =>
      findings += Finding("Fieldmap" + issue.code.toString, if issue.isError then Severity.Error else Severity.Warning,
        Some(relative(issue.path)), issue.field.fold(issue.message)(f => s"$f: ${issue.message}"))
    }
    val links = fieldmaps.associations.map { a =>
      ScientificRelationship(relative(a.target), a.mechanism match
        case bids4s.BidsFieldmapMechanism.B0FieldSource => "bids.b0-field-source"
        case bids4s.BidsFieldmapMechanism.IntendedFor => "bids.intended-for",
        a.sources.map(relative), a.identifier,
        a.evidence.map(e => RelationshipEvidence(relative(e.artifact), relative(e.document), e.field)))
    }
    val companionResolver = BidsCompanionResolver(checked.value)
    val resolvedPaths = artifacts.map(_.path).toSet
    val companionLinks = artifacts.flatMap { artifact =>
      context.parseGeneric(artifact.path.fileName).toOption.toVector
        .filter(n => Set("nii", "nii.gz")(n.extension)).flatMap { name =>
          val kinds = name.kind match
            case "dwi" => Vector(BidsCompanionKind.Bval, BidsCompanionKind.Bvec)
            case "bold" => Vector(BidsCompanionKind.Events)
            case _ => Vector.empty
          val selected = kinds.flatMap { kind =>
            companionResolver.resolve(BidsPath(artifact.path.value), kind) match
              case Left(error) =>
                findings += Finding("CompanionResolution", Severity.Error, Some(artifact.path), error.message)
                None
              case Right(resolution) => resolution.outcome match
                case BidsCompanionOutcome.Selected(candidate) =>
                  if resolution.applicable.forall(c => resolvedPaths(relative(c.path))) then
                    Some(candidate -> RelationshipEvidence.CompanionSelection(artifact.path, relative(candidate.path),
                      BidsCompanionResolver.Version + ":" + kind.toString, resolution.datasetRoot.map(relative),
                      candidate.matchedEntities, resolution.applicable.map(c => relative(c.path))))
                  else
                    findings += Finding("CompanionMetadata", Severity.Error, Some(artifact.path),
                      "an applicable companion has unresolved metadata")
                    None
                case BidsCompanionOutcome.Missing =>
                  if kind != BidsCompanionKind.Events || !name.entities.get(bids4s.EntityKey.Task).contains("rest") then
                    findings += Finding("CompanionMissing" + kind.toString,
                      if kind == BidsCompanionKind.Events then Severity.Warning else Severity.Error,
                      Some(artifact.path), s"no applicable ${kind.suffix}.${kind.extension} companion")
                  None
                case BidsCompanionOutcome.Ambiguous(paths) =>
                  findings += Finding("CompanionAmbiguous", Severity.Error, Some(artifact.path),
                    s"${kind.toString}: multiple applicable files at one level: ${paths.map(_.value).mkString(", ")}")
                  None
                case other =>
                  findings += Finding("CompanionUnsupported", Severity.Error, Some(artifact.path), other.toString)
                  None
          }
          if selected.isEmpty || selected.size != kinds.size then Vector.empty
          else Vector(ScientificRelationship(artifact.path,
            if name.kind == "dwi" then "bids.dwi-gradients" else "bids.task-events",
            selected.map((c, _) => relative(c.path)), None, selected.map(_._2)))
        }
    }
    for
      graph <- RelationshipGraph.from(BidsInterpreter.RelationshipVersion, links ++ companionLinks ++ familyLinks ++ pairLinks ++ echoLinks ++ sbrefLinks, artifacts, evidenceVersion = 2)
      interpretation <- Interpretation.from(manifest.id, profile, artifacts, facts, participants, Some("scientific-query-v1"), Some(graph),
        sidecars.get(BidsPath("dataset_description.json")).flatMap(_.fields.get("BIDSVersion")).collect { case JsonValue.Str(v) => v })
    yield interpretation -> Assessment.from(interpretation, "reference-admission-v10", findings.result())

  // All paths here originate in the already checked repository manifest.
  private def relative(path: BidsPath): RelPath =
    RelPath.from(path.value).fold(problem => throw sanctity.application.Failure(problem), identity)

  private def finding(issue: BidsIssue): Finding =
    Finding(issue.code.toString,
      if issue.severity == BidsIssueSeverity.Error then Severity.Error else Severity.Warning,
      issue.path.map(relative), issue.field.fold(issue.message)(field => s"$field: ${issue.message}"))

  private def convert(json: Json, depth: Int = 0): Either[String, JsonValue] =
    if depth > 128 then Left("reference metadata nesting limit is 128")
    else json.fold(
      Right(JsonValue.Null),
      value => Right(JsonValue.Bool(value)),
      number => number.toBigDecimal.filter(BidsInterpreter.recordable).toRight("number is too long to record exactly").flatMap { exact =>
        val approximate = exact.toDouble
        Either.cond(approximate.isFinite, JsonValue.Num(approximate), "number is outside the double-precision range")
      },
      value => Right(JsonValue.Str(value)),
      values => values.traverse(value => convert(value, depth + 1)).map(JsonValue.Arr.apply),
      fields => fields.toVector.traverse((key, value) => convert(value, depth + 1).map(key -> _)).map(v => JsonValue.Obj(v.toMap))
    )

  /** A sidecar value exactly as written: numbers keep every digit. */
  private def authored(json: Json, depth: Int = 0): Either[String, MetadataValue] =
    if depth > 128 then Left("reference metadata nesting limit is 128")
    else json.fold(
      Right(MetadataValue.Null),
      value => Right(MetadataValue.Flag(value)),
      number => number.toBigDecimal.filter(BidsInterpreter.recordable).toRight("number is too long to record exactly").map(MetadataValue.Number.apply),
      value => Right(MetadataValue.Text(value)),
      values => values.traverse(authored(_, depth + 1)).map(MetadataValue.Array.apply),
      fields => fields.toVector.traverse((key, value) => authored(value, depth + 1).map(key -> _)).map(v => MetadataValue.Record(v.toMap))
    )

  private def metadata(value: JsonValue): MetadataValue = value match
    case JsonValue.Obj(fields) => MetadataValue.Record(fields.view.mapValues(metadata).toMap)
    case JsonValue.Arr(values) => MetadataValue.Array(values.map(metadata))
    case JsonValue.Str(value) => MetadataValue.Text(value)
    case JsonValue.Num(value) => MetadataValue.Number(BigDecimal(value.toString))
    case JsonValue.Bool(value) => MetadataValue.Flag(value)
    case JsonValue.Null => MetadataValue.Null

object BidsInterpreter:
  val RelationshipVersion: String = "bids-mri-relationships-v7"

  /** Bounds the digits kept for one number; far beyond anything a measurement carries. */
  private[bids] def recordable(number: BigDecimal): Boolean = number.precision <= 128 && math.abs(number.scale.toLong) <= 400

  /** The value the library computes with: every number as its nearest double, as `metadata` renders it. */
  private[bids] def computed(value: MetadataValue): MetadataValue = value match
    case MetadataValue.Number(n) => MetadataValue.Number(BigDecimal(n.toDouble.toString))
    case MetadataValue.Array(values) => MetadataValue.Array(values.map(computed))
    case MetadataValue.Record(fields) => MetadataValue.Record(fields.view.mapValues(computed).toMap)
    case other => other

  def describe(context: BidsContext): SemanticContext =
    SemanticContext.from(context.name, context.specificationVersion, context.coverage, context.registryTokens,
      context.ruleVersions ++ Map("repository-query" -> "scientific-query-v1",
        "bold-timing" -> BoldTimingChecks.Version, "events" -> bids4s.CheckedEvents.Version, "event-timing" -> bids4s.EventTiming.Version,
        "dwi" -> bids4s.DwiConsistency.Version, "nifti" -> bids4s.NiftiHeaderFacts.Version, "mri-header" -> bids4s.MriHeaderConsistency.Version,
        "phase-pair" -> bids4s.BidsPhasePairResolver.Version,
        "sbref" -> SbrefBindings.Version, "multi-echo" -> bids4s.BidsMultiEchoResolver.Version, "stimuli" -> bids4s.BidsStimulusResolver.Version))
      .fold(p => throw sanctity.application.Failure(p), identity)
