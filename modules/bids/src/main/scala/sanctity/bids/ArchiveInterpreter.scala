package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{BlobStore, Checked, SemanticInterpreter}
import sanctity.kernel.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** A collection kept as it is: bytes, digests and paths, with one descriptive document at its root.
  *
  * The only file that is read is `archive_description.json`. Every other file is recorded as preserved, and nothing
  * is claimed about its meaning. Use this for data that has no BIDS specification.
  */
final class ArchiveInterpreter[F[_]: Async](store: BlobStore[F]) extends SemanticInterpreter[F]:
  import ArchiveInterpreter.*
  private val parser = new JawnParser(maxValueSize = Some(DescriptorLimit.toInt), allowDuplicateKeys = false)
  private val profile = SemanticProfile(Profile, BuildIdentity.bids4s, BuildIdentity.sanctity, Statement)

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    val descriptor = manifest.files.find(_.path.value == Descriptor)
    for
      read <- descriptor.traverse { file =>
        if file.blob.size > DescriptorLimit then
          Async[F].pure(Left(s"$Descriptor exceeds ${DescriptorLimit / 1024} KiB"): Either[String, Map[String, MetadataValue]])
        else store.read(file.blob).compile.to(Array).map(decode)
      }
      findings = (descriptor, read) match
        case (Some(file), Some(Right(fields))) => validate(file.path, fields)
        case (Some(file), Some(Left(message))) => Vector(Finding("ArchiveDescriptionInvalid", Severity.Error, Some(file.path), message))
        case _ => Vector(Finding("MissingArchiveDescription", Severity.Error, None, s"root $Descriptor is required"))
      artifacts = descriptor.toVector.flatMap(file => read.flatMap(_.toOption).map { fields =>
        ArtifactMetadata(file.path, fields.map((name, value) => name -> EffectiveField(value, file.path, Vector(Contribution(file.path, value)))),
          Vector(file.path), None)
      })
      levels <- Checked.lift[F, CoverageLevels](CoverageLevels.from(manifest.files.map { file =>
        if descriptor.contains(file) then FileLevel(file.path, "dataset", CoverageLevel.Assessed)
        else FileLevel(file.path, "archive", CoverageLevel.Preserved)
      }))
      interpretation <- Checked.lift[F, Interpretation](Interpretation.from(manifest.id, profile, artifacts, levels = Some(levels)))
    yield interpretation -> Assessment.from(interpretation, Policy, findings)

  private def decode(bytes: Array[Byte]): Either[String, Map[String, MetadataValue]] =
    for
      text <- Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toEither.leftMap(_ => "must be UTF-8 text")
      json <- parser.parse(text).leftMap(_.message)
      fields <- json.asObject.toRight("must be a JSON object")
      values <- fields.toVector.traverse((name, value) => convert(value, 0).map(name -> _))
    yield values.toMap

  private def convert(json: Json, depth: Int): Either[String, MetadataValue] =
    if depth > 32 then Left("nesting deeper than 32 levels is not supported")
    else json.fold(
      Right(MetadataValue.Null),
      flag => Right(MetadataValue.Flag(flag)),
      number => number.toBigDecimal.filter(n => n.precision <= 128 && math.abs(n.scale.toLong) <= 308)
        .toRight("a number cannot be represented exactly").map(MetadataValue.Number.apply),
      text => Right(MetadataValue.Text(text)),
      values => values.traverse(convert(_, depth + 1)).map(MetadataValue.Array.apply),
      fields => fields.toVector.traverse((name, value) => convert(value, depth + 1).map(name -> _)).map(v => MetadataValue.Record(v.toMap)))

  private def validate(path: RelPath, fields: Map[String, MetadataValue]): Vector[Finding] =
    def invalid(field: String, rule: String) = Finding("ArchiveDescriptionInvalid", Severity.Error, Some(path), s"$field $rule")
    def text(value: MetadataValue): Boolean = value match
      case MetadataValue.Text(s) => s.trim.nonEmpty && s.length <= 4096
      case _ => false
    val name = fields.get("Name") match
      case Some(value) if text(value) => Vector.empty
      case Some(_) => Vector(invalid("Name", "must be non-empty text of at most 4096 characters"))
      case None => Vector(invalid("Name", "is required"))
    val texts = TextFields.toVector.sorted.flatMap(field => fields.get(field).filterNot(text).map(_ => invalid(field, "must be non-empty text")))
    val lists = ListFields.toVector.sorted.flatMap(field => fields.get(field).flatMap {
      case MetadataValue.Array(values) if values.size <= 1000 && values.forall(text) => None
      case _ => Some(invalid(field, "must be a list of at most 1000 non-empty text values"))
    })
    name ++ texts ++ lists

object ArchiveInterpreter:
  val Descriptor: String = "archive_description.json"
  val Profile: String = "sanctity-archive-v1"
  val Policy: String = "sanctity-archive-admission-v1"
  val DescriptorLimit: Long = 1024L * 1024
  /** Field names follow `dataset_description.json`; their meaning matches the DataCite title, description, rights,
    * identifier, creator, subject, funding reference and related identifier.
    */
  val TextFields: Set[String] = Set("Description", "License", "DatasetDOI")
  val ListFields: Set[String] = Set("Authors", "Keywords", "DataTypes", "Funding", "ReferencesAndLinks")
  val Statement: String =
    "Archive collection. Bytes, digests and paths are preserved exactly, and the root archive_description.json is read " +
      "and checked. No other file is opened and nothing is claimed about its meaning."
