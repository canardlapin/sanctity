package sanctity.postgres

import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.generic.semiauto.*
import io.circe.jawn.JawnParser
import io.circe.syntax.*
import sanctity.kernel.*

import java.time.Instant
import scala.util.Try

/** Explicit v1 persistence envelope. Checked constructors and content identities are verified on read. */
private[postgres] object JsonCodecs:
  given [A]: Encoder[Id[A]] = Encoder.encodeString.contramap(_.render)
  given [A]: Decoder[Id[A]] = Decoder.decodeString.emap(value => Id.parse[A](value).leftMap(_.message))
  given Encoder[RelPath] = Encoder.encodeString.contramap(_.value)
  given Decoder[RelPath] = Decoder.decodeString.emap(value => RelPath.from(value).leftMap(_.message))
  given Encoder[Digest] = Encoder.encodeString.contramap(_.value)
  given Decoder[Digest] = Decoder.decodeString.emap(value => Digest.from(value).leftMap(_.message))
  given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Instant] = Decoder.decodeString.emap(value => Try(Instant.parse(value)).toEither.leftMap(_ => "invalid instant"))
  given Encoder[ManifestId] = deriveEncoder
  given Decoder[ManifestId] = deriveDecoder
  given Encoder[InterpretationId] = deriveEncoder
  given Decoder[InterpretationId] = deriveDecoder
  given Encoder[AssessmentId] = deriveEncoder
  given Decoder[AssessmentId] = deriveDecoder
  given Encoder[BlobRef] = Encoder.instance(blob => Json.obj("digest" -> blob.digest.asJson, "size" -> blob.size.asJson))
  given Decoder[BlobRef] = Decoder.instance { cursor =>
    for
      digest <- cursor.get[Digest]("digest")
      size <- cursor.get[Long]("size")
      blob <- checked(cursor, BlobRef.from(digest, size))
    yield blob
  }
  given Encoder[Entry] = deriveEncoder
  given Decoder[Entry] = deriveDecoder
  given Encoder[MetadataValue] = deriveEncoder
  given Decoder[MetadataValue] = deriveDecoder
  given Encoder[Contribution] = deriveEncoder
  given Decoder[Contribution] = deriveDecoder
  given Encoder[EffectiveField] = deriveEncoder
  given Decoder[EffectiveField] = deriveDecoder
  given Encoder[ArtifactMetadata] = deriveEncoder
  given Decoder[ArtifactMetadata] = deriveDecoder
  given Encoder[RelationshipEvidence] = Encoder.instance {
    case RelationshipEvidence.MetadataField(a, d, f) =>
      Json.obj("artifact" -> a.asJson, "document" -> d.asJson, "field" -> f.asJson)
    case RelationshipEvidence.CompanionSelection(a, d, rule, root, entities, applicable) =>
      Json.obj("kind" -> "companion-selection".asJson, "artifact" -> a.asJson, "document" -> d.asJson,
        "rule" -> rule.asJson, "datasetRoot" -> root.asJson, "matchedEntities" -> entities.asJson, "applicable" -> applicable.asJson)
  }
  given Decoder[RelationshipEvidence] = Decoder.instance { c =>
    c.get[Option[String]]("kind").flatMap {
      case None => for
        a <- c.get[RelPath]("artifact")
        d <- c.get[RelPath]("document")
        f <- c.get[String]("field")
      yield RelationshipEvidence.MetadataField(a, d, f)
      case Some("companion-selection") => for
        a <- c.get[RelPath]("artifact")
        d <- c.get[RelPath]("document")
        rule <- c.get[String]("rule")
        root <- c.get[Option[RelPath]]("datasetRoot")
        entities <- c.get[Map[String, String]]("matchedEntities")
        applicable <- c.get[Vector[RelPath]]("applicable")
      yield RelationshipEvidence.CompanionSelection(a, d, rule, root, entities, applicable)
      case _ => Left(DecodingFailure("unsupported relationship evidence kind", c.history))
    }
  }
  given Encoder[ScientificRelationship] = deriveEncoder
  given Decoder[ScientificRelationship] = deriveDecoder
  given Encoder[ArtifactFacts] = deriveEncoder
  given Decoder[ArtifactFacts] = deriveDecoder
  given Encoder[ParticipantFacts] = deriveEncoder
  given Decoder[ParticipantFacts] = deriveDecoder
  given Encoder[ReleaseRef] = deriveEncoder
  given Decoder[ReleaseRef] = deriveDecoder
  given Encoder[ArtifactPredicate] = deriveEncoder
  given Decoder[ArtifactPredicate] = deriveDecoder
  given Encoder[ParticipantPredicate] = deriveEncoder
  given Decoder[ParticipantPredicate] = deriveDecoder
  given Encoder[ScientificQuery] = deriveEncoder
  given Decoder[ScientificQuery] = deriveDecoder
  given Encoder[SelectionHit] = deriveEncoder
  given Decoder[SelectionHit] = deriveDecoder
  given Encoder[SelectionId] = deriveEncoder
  given Decoder[SelectionId] = deriveDecoder
  given Encoder[FrozenSelection] = Encoder.instance(s => Json.obj(
    "id" -> s.id.asJson, "releases" -> s.releases.asJson, "query" -> s.query.asJson, "hits" -> s.hits.asJson))
  given Decoder[FrozenSelection] = Decoder.instance { c =>
    for
      id <- c.get[SelectionId]("id")
      refs <- c.get[Vector[ReleaseRef]]("releases")
      query <- c.get[ScientificQuery]("query")
      hits <- c.get[Vector[SelectionHit]]("hits")
      selection <- checked(c, FrozenSelection.from(refs, query, hits))
      _ <- verify(c, selection.id == id)
    yield selection
  }
  given Encoder[SemanticProfile] = deriveEncoder
  given Decoder[SemanticProfile] = deriveDecoder
  given Encoder[Severity] = deriveEncoder
  given Decoder[Severity] = deriveDecoder
  given Encoder[Finding] = deriveEncoder
  given Decoder[Finding] = deriveDecoder
  given Encoder[CheckInput] = deriveEncoder
  given Decoder[CheckInput] = deriveDecoder
  given Encoder[ContentCheck] = Encoder.instance(c => Json.obj(
    "rule" -> c.rule.asJson, "target" -> c.target.asJson, "inputs" -> c.inputs.asJson,
    "facts" -> c.facts.asJson, "findings" -> c.findings.asJson))
  given Decoder[ContentCheck] = Decoder.instance { c =>
    for
      rule <- c.get[String]("rule")
      target <- c.get[RelPath]("target")
      inputs <- c.get[Vector[CheckInput]]("inputs")
      facts <- c.get[Map[String, MetadataValue]]("facts")
      findings <- c.get[Vector[Finding]]("findings")
      check <- checked(c, ContentCheck.from(rule, target, inputs, facts, findings))
    yield check
  }
  given Encoder[sanctity.application.SourceEntry] = deriveEncoder
  given Decoder[sanctity.application.SourceEntry] = deriveDecoder
  given Encoder[IdempotencyKey] = Encoder.encodeString.contramap(_.value)
  given Decoder[IdempotencyKey] = Decoder.decodeString.emap(v => IdempotencyKey.from(v).leftMap(_.message))
  given Encoder[AcceptanceReceipt] = deriveEncoder
  given Decoder[AcceptanceReceipt] = deriveDecoder
  given Encoder[Action] = deriveEncoder
  given Decoder[Action] = deriveDecoder
  given Encoder[Grant] = deriveEncoder
  given Decoder[Grant] = deriveDecoder
  given Encoder[CustodyBinding] = deriveEncoder
  given Decoder[CustodyBinding] = deriveDecoder
  given Encoder[PolicySnapshot] = deriveEncoder
  given Decoder[PolicySnapshot] = deriveDecoder
  given Encoder[ActivityKind] = deriveEncoder
  given Decoder[ActivityKind] = deriveDecoder
  given Encoder[ProjectionEvidence] = deriveEncoder
  given Decoder[ProjectionEvidence] = deriveDecoder
  given Encoder[Activity] = deriveEncoder
  given Decoder[Activity] = Decoder.instance { c =>
    for
      kind <- c.get[ActivityKind]("kind")
      actor <- c.get[PrincipalId]("actor")
      reason <- c.get[String]("reason")
      source <- c.get[Option[String]]("source")
      at <- c.get[Instant]("at")
      projection <- c.get[Option[ProjectionEvidence]]("projection")
      evidence <- c.get[Option[BlobRef]]("evidence")
      retained <- c.get[Option[Vector[BlobRef]]]("retained").map(_.getOrElse(Vector.empty))
    yield Activity(kind, actor, reason, source, at, projection, evidence, retained)
  }

  given Encoder[Manifest] = Encoder.instance(m => Json.obj("id" -> m.id.asJson, "entries" -> m.entries.asJson))
  given Decoder[Manifest] = Decoder.instance { cursor =>
    for
      id <- cursor.get[ManifestId]("id")
      entries <- cursor.get[Vector[Entry]]("entries")
      manifest <- checked(cursor, Manifest.from(entries))
      _ <- verify(cursor, manifest.id == id)
    yield manifest
  }
  given Encoder[Interpretation] = Encoder.instance(i => Json.obj(
    "id" -> i.id.asJson, "content" -> i.content.asJson, "profile" -> i.profile.asJson, "artifacts" -> i.artifacts.asJson,
    "facts" -> i.facts.asJson, "participants" -> i.participants.asJson, "queryVersion" -> i.queryVersion.asJson,
    "relationships" -> i.relationships.fold(Json.Null)(g => Json.obj("version" -> g.version.asJson, "evidenceVersion" -> g.evidenceVersion.asJson, "links" -> g.relationships.asJson))
  ))
  given Decoder[Interpretation] = Decoder.instance { cursor =>
    for
      id <- cursor.get[InterpretationId]("id")
      content <- cursor.get[ManifestId]("content")
      profile <- cursor.get[SemanticProfile]("profile")
      artifacts <- cursor.get[Vector[ArtifactMetadata]]("artifacts")
      facts <- cursor.get[Option[Vector[ArtifactFacts]]]("facts").map(_.getOrElse(Vector.empty))
      participants <- cursor.get[Option[Vector[ParticipantFacts]]]("participants").map(_.getOrElse(Vector.empty))
      queryVersion <- cursor.get[Option[String]]("queryVersion")
      relationJson <- cursor.get[Option[Json]]("relationships")
      graph <- relationJson.traverse { j =>
        for
          version <- j.hcursor.get[String]("version")
          links <- j.hcursor.get[Vector[ScientificRelationship]]("links")
          encoding <- j.hcursor.get[Option[Int]]("evidenceVersion").map(_.getOrElse(1))
          g <- checked(cursor, RelationshipGraph.from(version, links, artifacts, encoding))
        yield g
      }
      interpretation <- checked(cursor, Interpretation.from(content, profile, artifacts, facts, participants, queryVersion, graph))
      _ <- verify(cursor, interpretation.id == id)
    yield interpretation
  }

  private final case class AssessmentRecord(id: AssessmentId, content: ManifestId, interpretation: InterpretationId,
      policy: String, findings: Vector[Finding], checks: Option[Vector[ContentCheck]])
  private given Encoder[AssessmentRecord] = deriveEncoder
  private given Decoder[AssessmentRecord] = deriveDecoder
  given Encoder[Assessment] = Encoder.instance(a => AssessmentRecord(a.id, a.content, a.interpretation, a.policy, a.findings, Option.when(a.checks.nonEmpty)(a.checks)).asJson)

  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)

  def encode[A: Encoder](value: A): String = Json.obj("version" -> Json.fromInt(1), "value" -> value.asJson).noSpaces

  def decode[A: Decoder](text: String): Either[Problem, A] =
    parser.parse(text).leftMap(_ => Problem.Integrity("invalid persisted JSON")).flatMap { json =>
      val cursor = json.hcursor
      for
        version <- cursor.get[Int]("version").leftMap(_ => Problem.Integrity("missing persistence version"))
        _ <- Either.cond(version == 1, (), Problem.Unsupported(s"persistence version $version"))
        value <- cursor.get[A]("value").leftMap(_ => Problem.Integrity("persisted value failed validation or identity verification"))
      yield value
    }

  def assessment(text: String, interpretation: Interpretation): Either[Problem, Assessment] =
    decode[AssessmentRecord](text).flatMap { record =>
      val result = Assessment.from(interpretation, record.policy, record.findings, record.checks.getOrElse(Vector.empty))
      Either.cond(record.id == result.id && record.content == result.content && record.interpretation == interpretation.id,
        result, Problem.Integrity("assessment identity or evidence binding mismatch"))
    }

  private def checked[A](cursor: HCursor, value: Either[Problem, A]): Decoder.Result[A] =
    value.leftMap(p => DecodingFailure(p.message, cursor.history))
  private def verify(cursor: HCursor, condition: Boolean): Decoder.Result[Unit] =
    Either.cond(condition, (), DecodingFailure("identity mismatch", cursor.history))
