package sanctity.postgres

import cats.syntax.all.*
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.*
import sanctity.kernel.*

/** Local persistence encodings; not the independently signed ADR004 wire format. */
private[postgres] object OrganizationCodecs:
  import JsonCodecs.given
  given [A]: KeyEncoder[Id[A]] = KeyEncoder.instance(_.render)
  given [A]: KeyDecoder[Id[A]] = KeyDecoder.instance(Id.parse[A](_).toOption)
  given Encoder[OrganizationName] = Encoder.encodeString.contramap(_.value)
  given Decoder[OrganizationName] = Decoder.decodeString.emap(OrganizationName.from(_).leftMap(_.message))
  given Encoder[OrganizationDescription] = Encoder.encodeString.contramap(_.value)
  given Decoder[OrganizationDescription] = Decoder.decodeString.emap(OrganizationDescription.from(_).leftMap(_.message))
  given Encoder[OrganizationGeneration] = Encoder.encodeLong.contramap(_.value)
  given Decoder[OrganizationGeneration] = Decoder.decodeLong.emap(OrganizationGeneration.from(_).leftMap(_.message))
  given Encoder[LabRole] = Encoder.encodeString.contramap(_.toString)
  given Decoder[LabRole] = Decoder.decodeString.emap(s => LabRole.values.find(_.toString == s).toRight("invalid lab role"))
  given Encoder[OrganizationRole] = Encoder.encodeString.contramap(_.toString)
  given Decoder[OrganizationRole] = Decoder.decodeString.emap(s => OrganizationRole.values.find(_.toString == s).toRight("invalid project role"))
  given Encoder[OrganizationStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[OrganizationStatus] = Decoder.decodeString.emap(s => OrganizationStatus.values.find(_.toString == s).toRight("invalid status"))
  given Encoder[AppointmentStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[AppointmentStatus] = Decoder.decodeString.emap(s => AppointmentStatus.values.find(_.toString == s).toRight("invalid appointment status"))
  given Encoder[PlacementPolicy] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PlacementPolicy] = Decoder.decodeString.emap(s => PlacementPolicy.values.find(_.toString == s).toRight("invalid policy"))
  given Encoder[ParticipationBasis] = deriveEncoder
  given Decoder[ParticipationBasis] = deriveDecoder
  given Encoder[OrganizationPrincipal] = deriveEncoder
  given Decoder[OrganizationPrincipal] = deriveDecoder
  given Encoder[Lab] = deriveEncoder
  given Decoder[Lab] = deriveDecoder
  given Encoder[Project] = deriveEncoder
  given Decoder[Project] = deriveDecoder
  given Encoder[LabMembership] = deriveEncoder
  given Decoder[LabMembership] = deriveDecoder
  given Encoder[ProjectParticipation] = deriveEncoder
  given Decoder[ProjectParticipation] = deriveDecoder
  given Encoder[DatasetPlacement] = deriveEncoder
  given Decoder[DatasetPlacement] = deriveDecoder
  given Encoder[DatasetStewardship] = deriveEncoder
  given Decoder[DatasetStewardship] = deriveDecoder
  given Encoder[ProjectDatasetLink] = deriveEncoder
  given Decoder[ProjectDatasetLink] = deriveDecoder
  given Encoder[OrganizationRef] = deriveEncoder
  given Decoder[OrganizationRef] = deriveDecoder
  given Encoder[MoveRole] = Encoder.encodeString.contramap(_.toString)
  given Decoder[MoveRole] = Decoder.decodeString.emap(s => MoveRole.values.find(_.toString == s).toRight("invalid move role"))
  given Encoder[MoveStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[MoveStatus] = Decoder.decodeString.emap(s => MoveStatus.values.find(_.toString == s).toRight("invalid move status"))
  // Maps keyed by references or roles are stored as ordered pairs, like expected generations below.
  given Encoder[DatasetMoveRequest] = Encoder.forProduct12("id", "dataset", "source", "destination", "policy", "keepLink",
    "frozen", "approvals", "requestedBy", "status", "generation", "createdAt")(m => (m.id, m.dataset, m.source, m.destination,
    m.policy, m.keepLink, m.frozen.toVector.sortBy(_._1.toString), m.approvals.toVector.sortBy(_._1.toString), m.requestedBy,
    m.status, m.generation, m.createdAt))
  given Decoder[DatasetMoveRequest] = Decoder.forProduct12[DatasetMoveRequest, MoveRequestId, DatasetId, ProjectId, ProjectId,
    PlacementPolicy, Option[ProjectDatasetLinkId], Vector[(OrganizationRef, OrganizationGeneration)], Vector[(MoveRole, PrincipalId)],
    PrincipalId, MoveStatus, OrganizationGeneration, java.time.Instant]("id", "dataset", "source", "destination", "policy", "keepLink",
    "frozen", "approvals", "requestedBy", "status", "generation", "createdAt")((id, d, s, t, p, l, f, a, r, st, g, at) =>
    DatasetMoveRequest(id, d, s, t, p, l, f.toMap, a.toMap, r, st, g, at))
  given Encoder[OrganizationAccessImpact] = deriveEncoder
  given Decoder[OrganizationAccessImpact] = deriveDecoder
  given Encoder[OrganizationCommand] = deriveEncoder
  given Decoder[OrganizationCommand] = deriveDecoder
  // Expected-generation maps use an ordered array, avoiding an ad-hoc string key grammar.
  given Encoder[OrganizationMutation] = Encoder.forProduct3("key", "expected", "command")(m =>
    (m.key, m.expected.toVector.sortBy(_._1.toString), m.command))
  given Decoder[OrganizationMutation] = Decoder.instance { c => for
    key <- c.get[IdempotencyKey]("key")
    expected <- c.get[Vector[(OrganizationRef, OrganizationGeneration)]]("expected")
    _ <- Either.cond(expected.map(_._1).distinct.size == expected.size, (), io.circe.DecodingFailure("duplicate expected reference", c.history))
    command <- c.get[OrganizationCommand]("command")
  yield OrganizationMutation(key, expected.toMap, command) }
  given Encoder[PolicyDurability] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PolicyDurability] = Decoder.decodeString.emap(s => PolicyDurability.values.find(_.toString == s).toRight("unknown policy durability"))
  given Encoder[OrganizationReceipt] = deriveEncoder
  given Decoder[OrganizationReceipt] = Decoder.instance { c => for
    sequence <- c.get[Long]("sequence")
    actor <- c.get[PrincipalId]("actor")
    key <- c.get[IdempotencyKey]("key")
    affected <- c.get[Set[OrganizationRef]]("affected")
    at <- c.get[java.time.Instant]("at")
    // V008 receipts predate the explicit field and were exclusively LocalOnly.
    durability <- c.get[Option[PolicyDurability]]("durability")
  yield OrganizationReceipt(sequence,actor,key,affected,at,durability.getOrElse(PolicyDurability.LocalOnly)) }

  import OrganizationAuthority.*
  given Encoder[Permission] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Permission] = Decoder.decodeString.emap(s => Permission.values.find(_.toString == s).toRight("invalid permission"))
  given Encoder[Sponsorship] = deriveEncoder
  given Decoder[Sponsorship] = deriveDecoder
  given Encoder[Eligibility] = deriveEncoder
  given Decoder[Eligibility] = deriveDecoder
  given Encoder[Appointment] = deriveEncoder
  given Decoder[Appointment] = deriveDecoder
  given Encoder[GrantBasis] = deriveEncoder
  given Decoder[GrantBasis] = deriveDecoder
  given Encoder[DataScope] = deriveEncoder
  given Decoder[DataScope] = deriveDecoder
  given Encoder[PolicyGrant] = deriveEncoder
  given Decoder[PolicyGrant] = deriveDecoder
