package sanctity.postgres

import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment}
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.{Decoder, Json, Printer}
import io.circe.syntax.*
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Current policy is read only under the installation guard. No admission or decision cache.
  * Full-state loading is an engineering baseline; query/scale qualification remains H04.
  */
private[postgres] object PolicySql:
  import Records.{lift, uuid}
  import JsonCodecs.given
  import OrganizationCodecs.given

  private def rows[A: Decoder](table: String): ConnectionIO[Vector[A]] =
    Fragment.const(s"SELECT payload::text FROM organization_$table").query[String].to[Vector]
      .flatMap(_.traverse(p => lift(JsonCodecs.decode[A](p))))

  def grants: ConnectionIO[Vector[PolicyGrant]] =
    sql"""SELECT id,issuer,recipient,dataset_id,release_id,actions,created_at,expires_at,
      basis,basis_payload::text,generation,revoked_at IS NOT NULL FROM access_grant"""
      .query[(UUID,Option[UUID],UUID,UUID,Option[UUID],List[String],Timestamp,Option[Timestamp],String,String,Long,Boolean)]
      .to[Vector].flatMap(_.traverse { (id,issuer,recipient,dataset,release,actions,created,expiry,basis,payload,generation,revoked) => lift(for
        as <- actions.traverse(a => Permission.values.find(_.toString == a).toRight(Problem.Integrity("unknown grant permission")))
        b <- JsonCodecs.decode[GrantBasis](payload)
        _ <- Either.cond(basis == (b match { case GrantBasis.Independent => "Independent"; case _ => "Derived" }), (), Problem.Integrity("grant basis mismatch"))
        gen <- OrganizationGeneration.from(generation)
      yield PolicyGrant(Id[GrantTag](id),issuer.map(Id[PrincipalTag].apply),Id[PrincipalTag](recipient),
        DataScope(Id[DatasetTag](dataset),release.map(Id[ReleaseTag].apply)),as.toSet,created.toInstant,
        expiry.map(_.toInstant),b,gen,revoked)) })

  def load: ConnectionIO[State] = for
    people <- sql"SELECT id,label,active,generation,created_at,authority_eligibility::text FROM principal"
      .query[(UUID,String,Boolean,Long,Timestamp,String)].to[Vector].flatMap(_.traverse { (id,label,active,gen,at,eligibility) => lift(for
        name <- OrganizationName.from(label)
        generation <- OrganizationGeneration.from(gen)
        e <- JsonCodecs.decode[Eligibility](eligibility)
      yield OrganizationPrincipal(Id[PrincipalTag](id),name,active,generation,at.toInstant) -> e) })
    releases <- sql"SELECT dataset_id,id,authority_lifecycle FROM release".query[(UUID,UUID,String)].to[Vector]
    data <- sql"SELECT id,head,authority_lifecycle FROM dataset".query[(UUID,Option[UUID],String)].to[Vector]
    labs <- rows[Lab]("lab")
    projects <- rows[Project]("project")
    members <- rows[LabMembership]("membership")
    roles <- rows[ProjectParticipation]("participation")
    placements <- rows[DatasetPlacement]("placement")
    stewards <- rows[DatasetStewardship]("stewardship")
    links <- rows[ProjectDatasetLink]("link")
    shares <- grants
  yield State(people.map((p,_) => p.id -> p).toMap, people.map((p,e) => p.id -> e).toMap,
    data.map { (id,head,status) =>
      val rs = releases.filter(_._1 == id).map((_,r,l) => Id[ReleaseTag](r) -> Lifecycle.valueOf(l)).toMap
      val d = Id[DatasetTag](id)
      d -> DatasetState(d,rs.keySet,Lifecycle.valueOf(status),rs,head.map(Id[ReleaseTag].apply))
    }.toMap, labs.map(x => x.id -> x).toMap,projects.map(x => x.id -> x).toMap,
    members.map(x => x.id -> x).toMap,roles.map(x => x.id -> x).toMap,placements.map(x => x.dataset -> x).toMap,
    stewards.map(x => x.id -> x).toMap,shares.map(x => x.id -> x).toMap,links.map(x => x.id -> x).toMap)

  def permits(ctx: AccessContext, request: Request): ConnectionIO[Boolean] = for
    _ <- CredentialSql.check(ctx)
    state <- load
    now <- OrganizationSql.now
  yield OrganizationAuthority.permits(state, PolicyAdmission(ctx.principal,true,None), request, now)

  def check(ctx: AccessContext, request: Request): ConnectionIO[Unit] =
    permits(ctx,request).flatMap(p => lift(Either.cond(p, (), Problem.Denied)))

  /** One immutable local history for every supported policy mutation. PolicyBridge
    * prepares these exact bytes and finalizes the committed event synchronously.
    * Callers compute complete intent before effects. The exclusive guard spans all steps.
    * The signed transport binds these local JSON bytes without rewriting their identity.
    */
  def commit[A](actor: PrincipalId, scope: String, key: String, kind: String, changes: Json,
      request: Option[String] = None, receipt: Option[(Long,Instant,String)] = None,
      extra: Json = Json.obj())(effects: ConnectionIO[A]): ConnectionIO[A] = for
    enabled <- OrganizationSql.guard(true)
    result <- if !enabled then effects else for
      state <- sql"SELECT installation,writer_epoch,revision,applied_hash,durability_mode FROM organization_policy WHERE singleton"
        .query[(UUID,Long,Long,String,String)].unique
      (installation,epoch,previousRevision,previous,mode) = state
      _ <- Records.require(Set("local-engineering","independent")(mode), "independent policy durability adapter required")
      _ <- Records.require(previousRevision < Long.MaxValue, "policy revision exhausted")
      at <- receipt.fold(OrganizationSql.now)(r => r._2.pure[ConnectionIO])
      revision = receipt.fold(previousRevision + 1)(_._1)
      event <- sql"SELECT gen_random_uuid()".query[UUID].unique
      databaseActor <- sql"SELECT current_user".query[String].unique
      command = request.flatMap(io.circe.jawn.parse(_).toOption).getOrElse(
        Json.obj("kind" -> Json.fromString(kind),"scope" -> Json.fromString(scope),"key" -> Json.fromString(key),"changes" -> changes))
      commandDigest = Digest.bytes(command.printWith(Printer.noSpaces.copy(sortKeys=true)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      intent = Json.obj("version" -> Json.fromString("policy-local-v2"), "installation" -> Json.fromString(installation.toString),
        "epoch" -> Json.fromString(epoch.toString), "event" -> Json.fromString(event.toString),
        "revision" -> Json.fromString(revision.toString), "previousRevision" -> Json.fromString(previousRevision.toString),
        "previousHash" -> Json.fromString(previous), "actor" -> actor.asJson, "databaseActor" -> Json.fromString(databaseActor),
        "scope" -> Json.fromString(scope), "key" -> Json.fromString(key), "kind" -> Json.fromString(kind),
        "reason" -> Json.fromString(kind), "approvalReferences" -> Json.arr(), "intentDigest" -> Json.fromString(commandDigest.value),
        "at" -> at.asJson, "request" -> request.flatMap(io.circe.jawn.parse(_).toOption).getOrElse(Json.Null),
        "changes" -> changes, "extra" -> extra)
      payload = intent.printWith(Printer.noSpaces.copy(sortKeys=true))
      digest = Digest.bytes(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      applied = Digest.framed("sanctity.policy-local.v2", Vector(previous,event.toString,revision.toString,digest.value))
      receiptText = receipt.fold(Json.obj("version" -> Json.fromInt(1), "value" -> Json.obj(
        "sequence" -> Json.fromString(revision.toString), "event" -> Json.fromString(event.toString),
        "actor" -> actor.asJson, "key" -> Json.fromString(key), "at" -> at.asJson,
        "durability" -> Json.fromString("LocalOnly"))).noSpaces)(_._3)
      // PREPARE boundary: all exact intent bytes exist before effects. Local engineering
      // mode has no witness certificate and cannot acknowledge independent durability.
      certificate <- doobie.free.connection.raw(c => PolicyBridge.prepare(c,payload,applied.value))
      durability = if certificate.isDefined then "Pending" else "LocalOnly"
      pendingReceipt = if certificate.isEmpty then receiptText else io.circe.jawn.parse(receiptText).toOption.get
        .mapObject(o => o.add("value",o("value").get.mapObject(_.add("durability",Json.fromString("Pending"))))).noSpaces
      value <- effects
      _ <- sql"""INSERT INTO organization_event(id,revision,installation,writer_epoch,previous_hash,payload_digest,applied_hash,
        actor,scope,request_key,request,receipt,payload,recorded_at,durability,prepare_certificate)
        VALUES($event,$revision,$installation,$epoch,$previous,${digest.value},${applied.value},
          ${uuid(actor)},$scope,$key,$request::jsonb,$pendingReceipt::jsonb,$payload::jsonb,${Timestamp.from(at)},$durability,$certificate::jsonb)""".update.run
      _ <- sql"UPDATE organization_policy SET revision=$revision,applied_hash=${applied.value} WHERE singleton".update.run
    yield value
  yield result

  /** Operator actions retain the configured installation identity and actual DB login.
    * This is an operator capability, never an inference that a token holder is an admin.
    */
  def operator: ConnectionIO[PrincipalId] =
    sql"SELECT bootstrap_operator FROM organization_policy WHERE singleton".query[UUID].unique.map(Id[PrincipalTag].apply)

  def requireOperator: ConnectionIO[Unit] =
    sql"""SELECT r.rolsuper OR EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname=current_schema() AND n.nspowner=r.oid)
      FROM pg_roles r WHERE r.rolname=current_user""".query[Boolean].unique
      .flatMap(ok => lift(Either.cond(ok, (), Problem.Denied)))
