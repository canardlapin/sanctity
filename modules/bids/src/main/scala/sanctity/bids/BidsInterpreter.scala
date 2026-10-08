package sanctity.bids

import bids4s.{BidsFieldmapResolver, BidsMetadataResolution, BidsIssue, BidsIssueSeverity, BidsManifest, BidsMetadataResolver, BidsMetadataValidation, BidsName, BidsPath, JsonValue}
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
final class BidsInterpreter[F[_]: Async](store: BlobStore[F]) extends SemanticInterpreter[F]:
  private val jsonLimit = 4L * 1024 * 1024
  private val totalLimit = 32L * 1024 * 1024
  private val parser = new JawnParser(maxValueSize = Some(4 * 1024 * 1024), allowDuplicateKeys = false)
  val profile: SemanticProfile = SemanticProfile(
    "native-reference-v2", BuildIdentity.bids4s, BuildIdentity.sanctity,
    "Built-in bids4s names and JSON inheritance; basic description/timing checks. Strict participant TSV and scoped query facts. Explicit NIfTI B0 fieldmap groups and IntendedFor links. No image geometry, inferred companions, or full BIDS conformance validation."
  )

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    val jsonFiles = manifest.files.filter(_.path.fileName.endsWith(".json"))
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
          yield obj
          file.path -> parsed
        }
      }
      participants <- manifest.files.find(_.path.value == "participants.tsv").traverse { file =>
        Checked.lift[F, Unit](Either.cond(file.blob.size <= jsonLimit, (), Problem.LimitExceeded("participant table exceeds 4 MiB"))) *>
          store.read(file.blob).compile.to(Array).flatMap(bytes => Checked.lift[F, ParticipantTable.Table](ParticipantTable.parse(bytes)))
            .map(ParticipantTable.facts)
      }
      result <- Checked.lift[F, (Interpretation, Assessment)](interpret(manifest, decoded, participants.getOrElse(Vector.empty)))
    yield result

  private def interpret(manifest: Manifest, decoded: Vector[(RelPath, Either[String, JsonValue.Obj])], participants: Vector[ParticipantFacts]): Either[Problem, (Interpretation, Assessment)] =
    val checked = BidsManifest.fromRelativePathsChecked(manifest.files.map(_.path.value))
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
      !file.path.fileName.endsWith(".json") &&
        BidsName.parse(file.path.fileName).orElse(BidsName.parseGeneric(file.path.fileName)).isRight
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
          val fields = resolved.metadata.fields.map { (name, value) =>
            name -> EffectiveField(metadata(value), relative(resolved.origins(name)),
              resolved.history(name).map(c => Contribution(relative(c.source), metadata(c.value))))
          }
          Some(ArtifactMetadata(file.path, fields, resolved.sources.map(s => relative(s.path)), resolved.datasetRoot.map(relative)))
    }
    val facts = artifacts.flatMap { artifact =>
      BidsName.parse(artifact.path.fileName).toOption.flatMap { name =>
        val entities = name.entities.values.map((key, value) => key.short -> value).toMap
        entities.get("sub").map { subject =>
          ArtifactFacts(artifact.path, s"sub-$subject", entities.get("ses").map("ses-" + _), name.kind, name.extension, entities)
        }
      }
    }
    val fieldmaps = BidsFieldmapResolver.fromResolved(checked.value, resolutions.result().map(m => m.target -> m).toMap)
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
    for
      graph <- RelationshipGraph.from(BidsFieldmapResolver.Version, links, artifacts)
      interpretation <- Interpretation.from(manifest.id, profile, artifacts, facts, participants, Some("scientific-query-v1"), Some(graph))
    yield interpretation -> Assessment.from(interpretation, "reference-admission-v2", findings.result())

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
      number => number.toBigDecimal.toRight("unsupported JSON number").flatMap { exact =>
        val approximate = exact.toDouble
        Either.cond(approximate.isFinite && BigDecimal(approximate.toString) == exact,
          JsonValue.Num(approximate), "number cannot be represented faithfully by this bids4s profile")
      },
      value => Right(JsonValue.Str(value)),
      values => values.traverse(value => convert(value, depth + 1)).map(JsonValue.Arr.apply),
      fields => fields.toVector.traverse((key, value) => convert(value, depth + 1).map(key -> _)).map(v => JsonValue.Obj(v.toMap))
    )

  private def metadata(value: JsonValue): MetadataValue = value match
    case JsonValue.Obj(fields) => MetadataValue.Record(fields.view.mapValues(metadata).toMap)
    case JsonValue.Arr(values) => MetadataValue.Array(values.map(metadata))
    case JsonValue.Str(value) => MetadataValue.Text(value)
    case JsonValue.Num(value) => MetadataValue.Number(BigDecimal(value.toString))
    case JsonValue.Bool(value) => MetadataValue.Flag(value)
    case JsonValue.Null => MetadataValue.Null
