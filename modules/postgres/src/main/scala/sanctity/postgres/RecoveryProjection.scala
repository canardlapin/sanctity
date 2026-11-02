package sanctity.postgres

import io.circe.{Decoder, Encoder, Json, Printer}
import io.circe.syntax.*
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.*
import sanctity.policy.*
import java.time.Instant

/** Pure policy projection. Scientific rows are never replayed from authority events.
  * Input is an independently authenticated enrollment and contiguous journal.
  */
private[postgres] object RecoveryProjection:
  import PolicyWire.{obj,str,field,text,fields,check,id,natural}
  import OrganizationCodecs.given
  private val printer = Printer.noSpaces.copy(sortKeys=true)
  private def parse(value: String): Json = io.circe.jawn.parse(value).fold(_ => throw PolicyFailure("recovery-json"),identity)
  private def array(value: Json): Vector[Json] = value.asArray.getOrElse(throw PolicyFailure("recovery-array")).toVector
  private def instant(value: Json): Json = str(Instant.parse(value.asString.getOrElse(throw PolicyFailure("recovery-time"))).toString)
  private def number(value: Json): Long = value.asNumber.flatMap(_.toLong).filter(_ >= 0)
    .getOrElse(throw PolicyFailure("recovery-generation"))
  private def strict[A: Decoder: Encoder](value: Json): Json =
    val decoded = value.as[A].fold(_ => throw PolicyFailure("recovery-event-schema"),identity).asJson
    def shape(in: Json, out: Json): Unit =
      in.asObject.foreach { o =>
        check(out.asObject.exists(expected => o.keys.forall(expected.contains)),"recovery-unknown-field")
        o.toVector.foreach((k,v) => shape(v,field(out,k)))
      }
      in.asArray.foreach { xs =>
        val ys = array(out)
        check(xs.size == ys.size,"recovery-array-schema")
        xs.zip(ys).foreach((a,b) => shape(a,b))
      }
    shape(value,decoded)
    decoded
  private val kinds = Vector("principal","lab","project","membership","participation","placement","stewardship","link","move")
  private def normalize(kind: String, value: Json): Json = kind match
    case "principal" => strict[OrganizationPrincipal](value)
    case "lab" => strict[Lab](value)
    case "project" => strict[Project](value)
    case "membership" => strict[LabMembership](value)
    case "participation" => strict[ProjectParticipation](value)
    case "placement" => strict[DatasetPlacement](value)
    case "stewardship" => strict[DatasetStewardship](value)
    case "link" => strict[ProjectDatasetLink](value)
    case "move" => strict[DatasetMoveRequest](value)
    case "grant" =>
      val grant = strict[PolicyGrant](value)
      def ordered(v: Json): Json = Json.fromValues(array(v).sortBy(_.printWith(printer)))
      grant.mapObject { fields =>
        fields.add("actions",ordered(field(grant,"actions"))).add("basis",field(grant,"basis").mapObject { basis =>
          basis("Derived").fold(basis)(derived => basis.add("Derived",derived.mapObject(d =>
            d.add("requires",ordered(field(derived,"requires"))))))
        })
      }
    case _ => throw PolicyFailure("recovery-change-kind")
  private def key(kind: String, value: Json): String = id(text(value,if kind == "placement" then "dataset" else "id"))
  private def map(kind: String, values: Vector[Json]): Map[String,Json] =
    val entries = values.map(v => normalize(kind,v)).map(v => key(kind,v) -> v)
    check(entries.map(_._1).distinct.size == entries.size,"recovery-duplicate-record")
    entries.toMap
  private def credential(value: Json): Json =
    fields(value,Set("id","principal","label","issuedAt","expiresAt","revokedAt"))
    id(text(value,"id")); id(text(value,"principal"))
    check(text(value,"label").nonEmpty && text(value,"label").length <= 100,"recovery-credential-label")
    val issued = instant(field(value,"issuedAt")); val expires = instant(field(value,"expiresAt"))
    check(Instant.parse(expires.asString.get).isAfter(Instant.parse(issued.asString.get)),"recovery-credential-expiry")
    value.mapObject(_.add("issuedAt",issued).add("expiresAt",expires).add("revokedAt",
      if field(value,"revokedAt").isNull then Json.Null else instant(field(value,"revokedAt"))))

  final case class Projection(checkpoint: Json, records: Map[String,Map[String,Json]],
      credentials: Map[String,Json], datasets: Map[String,Json], revokedAt: Map[String,Json],
      commits: Vector[(Json,Json)]):
    def people: Map[String,Json] = records("principal")
    def grants: Map[String,Json] = records("grant")
    def body: Json = obj("records" -> Json.obj(records.toVector.map((kind,rows) => kind -> Json.obj(rows.toVector*))*),
      "credentials" -> Json.obj(credentials.toVector*),"datasets" -> Json.obj(datasets.toVector*),
      "revokedAt" -> Json.obj(revokedAt.toVector*))
    def digest: String = PolicyWire.digest(body.printWith(printer).getBytes(java.nio.charset.StandardCharsets.UTF_8))

  def initial(enrollment: Json): Projection =
    PolicyJournal.validateEnrollment(enrollment)
    val checkpoint = parse(new String(PolicyWire.unbase64(text(enrollment,"checkpoint")),java.nio.charset.StandardCharsets.UTF_8))
    fields(checkpoint,Set("version","capabilities","installation","epoch","revision","appliedHash","tables"))
    val profile = text(enrollment,"capabilities")
    check(field(checkpoint,"capabilities") == PolicyCapabilities.description(profile) &&
      text(checkpoint,"version") == (if profile == PolicyCapabilities.digest then "policy-checkpoint-v1" else "policy-checkpoint-v2"),"recovery-checkpoint-profile")
    for name <- Vector("installation","epoch","revision","appliedHash") do
      check(field(checkpoint,name) == field(enrollment,name),"recovery-checkpoint-head")
    val tables = field(checkpoint,"tables")
    val base = Set("schema_migration","organization_policy","principal","dataset","release","access_grant","api_credential",
      "credential_audit","policy_audit","import_operation","outbox_delivery","processing_run","organization_event","policy_retired_runtime") ++
      kinds.filterNot(k => k == "principal" || k == "move").map("organization_"+_)
    fields(tables,if profile == PolicyCapabilities.digest then base else base + "organization_move")
    def rows(name: String): Vector[Json] = array(field(tables,name))
    check(rows("organization_policy").size == 1,"recovery-checkpoint-state")
    val principal = rows("principal").map { p =>
      check(field(p,"authority_eligibility") == parse(JsonCodecs.encode(Eligibility.Local)),"recovery-unsupported-identity")
      obj("id" -> field(p,"id"),"label" -> field(p,"label"),"active" -> field(p,"active"),
        "generation" -> field(p,"generation"),"createdAt" -> instant(field(p,"created_at")))
    }
    val organization = kinds.filterNot(_ == "principal").map { kind =>
      kind -> (if kind == "move" && profile == PolicyCapabilities.digest then Map.empty[String,Json]
        else map(kind,rows("organization_"+kind).map(p => field(field(p,"payload"),"value"))))
    }.toMap.updated("principal",map("principal",principal))
    val grants = rows("access_grant").map { g => obj("id" -> field(g,"id"),"issuer" -> field(g,"issuer"),
      "recipient" -> field(g,"recipient"),"scope" -> obj("dataset" -> field(g,"dataset_id"),"release" -> field(g,"release_id")),
      "actions" -> field(g,"actions"),"createdAt" -> instant(field(g,"created_at")),
      "expiresAt" -> (if field(g,"expires_at").isNull then Json.Null else instant(field(g,"expires_at"))),
      "basis" -> field(field(g,"basis_payload"),"value"),"generation" -> field(g,"generation"),
      "revoked" -> Json.fromBoolean(!field(g,"revoked_at").isNull)) }
    val credentials = rows("api_credential").map { c => credential(obj("id" -> field(c,"id"),"principal" -> field(c,"principal_id"),
      "label" -> field(c,"label"),"issuedAt" -> field(c,"issued_at"),"expiresAt" -> field(c,"expires_at"),"revokedAt" -> field(c,"revoked_at"))) }
    check(credentials.map(c => text(c,"id")).distinct.size == credentials.size,"recovery-duplicate-credential")
    check((rows("dataset") ++ rows("release")).forall(p => text(p,"authority_lifecycle") == "Available"),"recovery-unsupported-lifecycle")
    val datasets = rows("dataset").map(d => id(text(d,"id")) -> obj("id" -> field(d,"id"),"custodian" -> field(d,"custodian"),"attributedCurator" -> field(d,"curator"))).toMap
    val revoked = rows("access_grant").filterNot(g => field(g,"revoked_at").isNull).map(g => text(g,"id") -> instant(field(g,"revoked_at"))).toMap
    val history = rows("organization_event").sortBy(h => number(field(h,"revision")))
    var previous = PolicyWire.zero
    history.zipWithIndex.foreach { (h,index) =>
      val payload = field(h,"payload"); val version = text(payload,"version")
      check(Set("organization-local-v1","policy-local-v2")(version),"recovery-history-version")
      val digest = PolicyWire.digest(payload.printWith(printer).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val domain = if version == "policy-local-v2" then "sanctity.policy-local.v2" else "sanctity.organization-local.v1"
      val applied = Digest.framed(domain,Vector(previous,text(h,"id"),index.toString,digest)).value
      check(number(field(h,"revision")) == index && text(h,"previous_hash") == previous &&
        text(h,"payload_digest") == digest && text(h,"applied_hash") == applied,"recovery-checkpoint-history")
      previous = applied
    }
    check(history.nonEmpty && history.size.toLong-1 == natural(text(checkpoint,"revision")) &&
      previous == text(checkpoint,"appliedHash"),"recovery-checkpoint-history-head")
    Projection(checkpoint,organization.updated("grant",map("grant",grants)),credentials.map(c => text(c,"id") -> c).toMap,
      datasets,revoked,Vector.empty)

  private val allowedChanges: Map[String,Set[String]] = Map(
    "CreateLab" -> Set("lab","membership"),"RenameLab" -> Set("lab"),"CreateProject" -> Set("project","participation","lab"),
    "RenameProject" -> Set("project"),"AddMember" -> Set("membership","lab"),"EndMember" -> Set("membership","lab","project"),
    "AddParticipant" -> Set("participation","project"),"EndParticipant" -> Set("participation","project"),
    "PlaceNewDataset" -> Set("placement","stewardship","project"),"AddSteward" -> Set("stewardship","placement"),"EndSteward" -> Set("stewardship","placement"),
    "SetInheritance" -> Set("placement"),"ArchiveProject" -> Set("project","lab"),"ArchiveLab" -> Set("lab"),
    "DisablePrincipal" -> Set("principal","lab","project","placement"),"RenameDataset" -> Set("placement"),"TransferSteward" -> Set("stewardship","placement"),
    "RequestMove" -> Set("move"),"ApproveMove" -> Set("move"),"CommitMove" -> Set("move","placement","link","project"),
    "WithdrawMove" -> Set("move"),"RecoverSteward" -> Set("stewardship","placement"))

  def committed(before: Projection, event: Json, certificate: Json): Projection =
    PolicyJournal.validateEvent(event)
    val payload = parse(new String(PolicyWire.unbase64(text(event,"payload")),java.nio.charset.StandardCharsets.UTF_8))
    val kind = text(event,"kind"); val changes = field(payload,"changes")
    var records = before.records; var credentials = before.credentials; var data = before.datasets; var revoked = before.revokedAt
    def put(kind: String, old: Json, next: Json): Unit =
      val value = normalize(kind,next); val identifier = key(kind,value)
      val current = records(kind).get(identifier)
      check(current == (if old.isNull then None else Some(normalize(kind,old))),"recovery-previous-generation")
      val generation = number(field(value,"generation"))
      check(generation == current.fold(0L)(p => PolicyWire.increment(number(field(p,"generation")))),"recovery-next-generation")
      records = records.updated(kind,records(kind).updated(identifier,value))
    if Set("GrantIssued","GrantRevoked","CredentialIssued","CredentialRevoked")(kind) then
      fields(field(payload,"extra"),Set.empty)
      check(field(payload,"request").isNull,"recovery-unexpected-request")
    kind match
      case "GrantIssued" =>
        fields(changes,Set("principalCreated","before","after"))
        check(field(changes,"before").isNull,"recovery-grant-issued-before")
        val person = field(changes,"principalCreated")
        if !person.isNull then put("principal",Json.Null,person)
        put("grant",Json.Null,field(changes,"after"))
        check(!field(field(changes,"after"),"revoked").asBoolean.getOrElse(true),"recovery-grant-issued-state")
      case "GrantRevoked" =>
        fields(changes,Set("before","after","revokedAt"))
        val old = normalize("grant",field(changes,"before"));val next = normalize("grant",field(changes,"after"))
        check(field(old,"revoked").asBoolean.contains(false) && field(next,"revoked").asBoolean.contains(true) &&
          old.mapObject(_.remove("generation").remove("revoked")) == next.mapObject(_.remove("generation").remove("revoked")),"recovery-grant-revocation")
        put("grant",old,next);revoked = revoked.updated(key("grant",next),instant(field(changes,"revokedAt")))
      case "CredentialIssued" =>
        fields(changes,Set("before","after"));check(field(changes,"before").isNull,"recovery-credential-before")
        val next = credential(field(changes,"after"));val identifier = text(next,"id")
        check(!credentials.contains(identifier) && field(next,"revokedAt").isNull,"recovery-credential-issued")
        credentials = credentials.updated(identifier,next)
      case "CredentialRevoked" =>
        fields(changes,Set("id","principal","before","revokedAt"));check(field(changes,"before").isNull,"recovery-credential-before")
        val identifier = id(text(changes,"id"));val old = credentials.getOrElse(identifier,throw PolicyFailure("recovery-credential-missing"))
        check(text(changes,"principal") == text(old,"principal") && field(old,"revokedAt").isNull,"recovery-credential-revoked")
        credentials = credentials.updated(identifier,old.mapObject(_.add("revokedAt",instant(field(changes,"revokedAt")))))
      case _ =>
        val allowed = allowedChanges.getOrElse(kind,throw PolicyFailure("recovery-event-kind"))
        val encodedRequest = field(payload,"request")
        fields(encodedRequest,Set("version","value"))
        strict[OrganizationMutation](field(encodedRequest,"value"))
        val request = JsonCodecs.decode[OrganizationMutation](encodedRequest.noSpaces)
          .fold(_ => throw PolicyFailure("recovery-command-schema"),identity)
        check(request.command.productPrefix == kind,"recovery-command-kind")
        request.expected.foreach { (ref,generation) =>
          val (category,identifier) = ref match
            case OrganizationRef.Principal(v) => "principal" -> v.render
            case OrganizationRef.Lab(v) => "lab" -> v.render
            case OrganizationRef.Project(v) => "project" -> v.render
            case OrganizationRef.Membership(v) => "membership" -> v.render
            case OrganizationRef.Participation(v) => "participation" -> v.render
            case OrganizationRef.Placement(v) => "placement" -> v.render
            case OrganizationRef.Stewardship(v) => "stewardship" -> v.render
            case OrganizationRef.Link(v) => "link" -> v.render
            case OrganizationRef.Move(v) => "move" -> v.render
          check(records(category).get(identifier).exists(v => number(field(v,"generation")) == generation.value),"recovery-expected-generation")
        }
        val delta = array(changes)
        check(delta.nonEmpty,"recovery-empty-command")
        val identifiers = delta.map { change =>
          fields(change,Set("kind","before","after"));val k = text(change,"kind")
          check(allowed(k),"recovery-command-change")
          val identifier = key(k,field(change,"after"))
          put(k,field(change,"before"),field(change,"after")); k -> identifier
        }
        check(identifiers.distinct.size == identifiers.size,"recovery-duplicate-change")
        val extra = field(payload,"extra")
        fields(extra,Set("createdDatasets","invalidations"))
        val invalidations = field(extra,"invalidations")
        if kind == "DisablePrincipal" then
          fields(invalidations,Set("principal","revokedAt","credentials","importLeases"))
          val person = id(text(invalidations,"principal"))
          check(request.command == OrganizationCommand.DisablePrincipal(Id.parse[PrincipalTag](person).toOption.get),"recovery-disabled-principal")
          val tokenIds = array(field(invalidations,"credentials")).map(v => id(v.asString.get)).toSet
          check(tokenIds == credentials.values.filter(c => text(c,"principal") == person && field(c,"revokedAt").isNull).map(c => text(c,"id")).toSet,
            "recovery-invalidation-inventory")
          val raw = text(invalidations,"revokedAt").replace(' ','T')
          val revokedTime = str(Instant.parse(if raw.matches(".*[+-][0-9]{2}$") then raw+":00" else raw).toString)
          check(revokedTime == instant(field(payload,"at")),"recovery-invalidation-time")
          tokenIds.foreach(k => credentials = credentials.updated(k,credentials(k).mapObject(_.add("revokedAt",revokedTime))))
          array(field(invalidations,"importLeases")).foreach { lease =>
            fields(lease,Set("id","lease","until")); id(text(lease,"id"));id(text(lease,"lease"));instant(field(lease,"until"))
          }
        else fields(invalidations,Set.empty)
        val created = array(field(extra,"createdDatasets"))
        check(created.size == (if kind == "PlaceNewDataset" then 1 else 0),"recovery-created-dataset")
        created.foreach { d =>
          fields(d,Set("id","custodian","attributedCurator"));d.asObject.get.values.foreach(v => id(v.asString.get))
          val identifier = text(d,"id")
          check(!data.contains(identifier),"recovery-existing-dataset")
          data = data.updated(identifier,d)
        }
    check(records("grant").values.forall(g => records("principal").contains(text(g,"recipient"))) &&
      credentials.values.forall(c => records("principal").contains(text(c,"principal"))),"recovery-principal-reference")
    before.copy(records=records,credentials=credentials,datasets=data,revokedAt=revoked,commits=before.commits :+ (event -> certificate))

  /** A prior successful recovery invalidated every then-existing credential. New
    * issuance after that journal record remains distinct and governed normally.
    */
  def released(before: Projection, at: Json): Projection =
    val time = instant(at)
    before.copy(credentials=before.credentials.view.mapValues(c =>
      if field(c,"revokedAt").isNull then c.mapObject(_.add("revokedAt",time)) else c).toMap)
