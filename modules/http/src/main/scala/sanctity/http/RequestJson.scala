package sanctity.http

import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import sanctity.kernel.*
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Strict, bounded wire grammar. Unknown fields are rejected rather than silently changing a request. */
private[http] object RequestJson:
  val maxBytes: Int = 4 * 1024 * 1024
  private val invalid = Problem.Unsupported("invalid request JSON")
  def parse(bytes: Array[Byte]): Either[Problem, Json] =
    for
      _ <- Either.cond(bytes.length <= maxBytes, (), Problem.LimitExceeded("request exceeds 4 MiB"))
      text <- Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toEither.leftMap(_ => invalid)
      json <- new JawnParser(Some(maxBytes), allowDuplicateKeys = false).parse(text).leftMap(_ => invalid)
      // Bound before any recursive application decoding.
      _ <- Either.cond(bounded(json), (), Problem.LimitExceeded("JSON nesting or node limit exceeded"))
    yield json

  private def bounded(json: Json): Boolean =
    var pending = List(json -> 0)
    var count = 0
    while pending.nonEmpty && count <= 20000 do
      val (j, depth) = pending.head
      pending = pending.tail
      count += 1
      if depth > 32 then return false
      val children = j.asArray.toVector.flatten ++ j.asObject.toVector.flatMap(_.values)
      pending = children.iterator.map(_ -> (depth + 1)).toList ::: pending
    pending.isEmpty && count <= 20000

  def fields(j: Json, allowed: Set[String]): Either[Problem, Unit] =
    Either.cond(j.asObject.exists(_.keys.forall(allowed)), (), invalid)
  def get[A: Decoder](j: Json, name: String): Either[Problem, A] = j.hcursor.get[A](name).leftMap(_ => invalid)
  def id[A](j: Json, name: String): Either[Problem, Id[A]] = get[String](j, name).flatMap(Id.parse[A])
  /** Expected generations keyed "kind:id", e.g. {"lab:UUID": 3, "project:UUID": 1}; every value a positive generation. */
  def expected(j: Json): Either[Problem, Map[OrganizationRef, OrganizationGeneration]] =
    j.hcursor.downField("expected").focus.flatMap(_.asObject).toRight(invalid).flatMap(_.toVector.traverse { (key, value) =>
      for
        n <- value.asNumber.flatMap(_.toLong).toRight(invalid)
        generation <- OrganizationGeneration.from(n).leftMap(_ => invalid)
        ref <- key.split(":", 2) match
          case Array("principal", v) => Id.parse[PrincipalTag](v).map(OrganizationRef.Principal(_))
          case Array("lab", v) => Id.parse[LabTag](v).map(OrganizationRef.Lab(_))
          case Array("project", v) => Id.parse[ProjectTag](v).map(OrganizationRef.Project(_))
          case Array("membership", v) => Id.parse[LabMembershipTag](v).map(OrganizationRef.Membership(_))
          case Array("participation", v) => Id.parse[ProjectParticipationTag](v).map(OrganizationRef.Participation(_))
          case Array("placement", v) => Id.parse[DatasetTag](v).map(OrganizationRef.Placement(_))
          case Array("stewardship", v) => Id.parse[DatasetStewardshipTag](v).map(OrganizationRef.Stewardship(_))
          case Array("link", v) => Id.parse[ProjectDatasetLinkTag](v).map(OrganizationRef.Link(_))
          case Array("move", v) => Id.parse[MoveRequestTag](v).map(OrganizationRef.Move(_))
          case _ => Left(invalid)
      yield ref -> generation
    }.map(_.toMap))
  def name(j: Json): Either[Problem, OrganizationName] = get[String](j, "name").flatMap(OrganizationName.from(_).leftMap(_ => invalid))
  def description(j: Json): Either[Problem, OrganizationDescription] =
    get[Option[String]](j, "description").flatMap(d => OrganizationDescription.from(d.getOrElse("")).leftMap(_ => invalid))
  def expiry(j: Json): Either[Problem, Option[java.time.Instant]] =
    get[Option[String]](j, "expiresAt").flatMap(_.traverse(t => Try(java.time.Instant.parse(t)).toEither.leftMap(_ => invalid)))
  def ref(j: Json): Either[Problem, ReleaseRef] =
    for
      _ <- fields(j, Set("dataset", "release"))
      d <- id[DatasetTag](j, "dataset")
      r <- id[ReleaseTag](j, "release")
    yield ReleaseRef(d, r)

  def artifact(j: Json): Either[Problem, ArtifactPredicate] =
    get[String](j, "op").flatMap {
      case "all" => fields(j, Set("op", "conditions")) *> get[Vector[Json]](j, "conditions").flatMap(_.traverse(artifact)).map(ArtifactPredicate.All.apply)
      case "entity" => namedText(j).map(ArtifactPredicate.Entity.apply)
      case "suffix" => fields(j, Set("op", "value")) *> get[String](j, "value").map(ArtifactPredicate.Suffix.apply)
      case "metadataEquals" => namedText(j).map(ArtifactPredicate.MetadataEquals.apply)
      case "metadataBelow" => namedNumber(j).map(ArtifactPredicate.MetadataBelow.apply)
      case _ => Left(invalid)
    }
  def participant(j: Json): Either[Problem, ParticipantPredicate] =
    get[String](j, "op").flatMap {
      case "all" => fields(j, Set("op", "conditions")) *> get[Vector[Json]](j, "conditions").flatMap(_.traverse(participant)).map(ParticipantPredicate.All.apply)
      case "fieldEquals" => namedText(j).map(ParticipantPredicate.FieldEquals.apply)
      case "fieldAtLeast" => namedNumber(j).map(ParticipantPredicate.FieldAtLeast.apply)
      case "hasArtifact" => fields(j, Set("op", "condition")) *> get[Json](j, "condition").flatMap(artifact).map(ParticipantPredicate.HasArtifact.apply)
      case "hasSession" => fields(j, Set("op", "conditions")) *> get[Vector[Json]](j, "conditions").flatMap(_.traverse(artifact)).map(ParticipantPredicate.HasSession.apply)
      case _ => Left(invalid)
    }
  private def namedText(j: Json): Either[Problem, (String, String)] =
    fields(j, Set("op", "name", "value")) *> (get[String](j, "name"), get[String](j, "value")).tupled
  private def namedNumber(j: Json): Either[Problem, (String, BigDecimal)] =
    for
      _ <- fields(j, Set("op", "name", "value"))
      n <- get[String](j, "name")
      lexical <- get[Json](j, "value").map(_.noSpaces)
      v <- ScientificNumber.parse(lexical).toRight(invalid)
    yield (n, v)

  def query(j: Json): Either[Problem, (Vector[ReleaseRef], ScientificQuery)] =
    for
      _ <- fields(j, Set("releases", "participants", "artifacts"))
      refs <- get[Vector[Json]](j, "releases").flatMap(_.traverse(ref))
      p <- j.hcursor.downField("participants").focus.fold[Either[Problem, ParticipantPredicate]](Right(ParticipantPredicate.All(Vector.empty)))(participant)
      a <- j.hcursor.downField("artifacts").focus.fold[Either[Problem, ArtifactPredicate]](Right(ArtifactPredicate.All(Vector.empty)))(artifact)
      q = ScientificQuery(p, a)
      _ <- q.validate
    yield refs -> q
