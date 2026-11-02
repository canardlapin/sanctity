package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.free.connection as FC
import doobie.implicits.*
import io.circe.Json
import sanctity.policy.*
import sanctity.kernel.*
import java.nio.file.{Files,Path}
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Explicit operator recovery. No phase derives authority from a restored row.
  * Durable session transitions precede remote effects; opening follows verified
  * release and admission of a new, independently configured writer identity.
  */
final class OrganizationRecovery[F[_]: Async](db: PostgresDatabase[F], config: DatabaseConfig,
    recovery: PolicyConnection, writer: PolicyConnection):
  import PolicyWire.{obj,str,text,field,fields,check,id,natural}
  import PolicyBridge.{rows,update}
  import JsonCodecs.given
  private val F = Async[F]
  private val sorted = io.circe.Printer.noSpaces.copy(sortKeys=true)
  private def json(source: String): Json = io.circe.jawn.parse(source).fold(_ => throw PolicyFailure("recovery-json"),identity)
  private def digest(value: Json): String = PolicyWire.digest(value.printWith(sorted).getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private def transaction[A](body: Connection => A): F[A] = FC.raw(body).transact(db.transactor)
  private def uuid(operation: String, purpose: String): String = UUID.nameUUIDFromBytes(("sanctity-recovery-v1:"+purpose+":"+operation)
    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString
  private final case class Session(phase: String, plan: Json, verification: Option[Json], command: Option[Json], certificate: Option[Json])
  private def session(c: Connection, operation: String, lock: Boolean = false): Session =
    id(operation)
    val found = rows(c,"SELECT phase,plan::text,verification::text,release_command::text,release_certificate::text FROM organization_recovery_session WHERE operation=?::uuid"+
      (if lock then " FOR UPDATE" else ""),Seq(operation))(r => Session(r.getString(1),json(r.getString(2)),
        Option(r.getString(3)).map(json),Option(r.getString(4)).map(json),Option(r.getString(5)).map(json)))
    check(found.size == 1,"recovery-session-required")
    val s = found.head
    check(text(s.plan,"recoveryIdentity") == recovery.writer && text(s.plan,"writerIdentity") == writer.writer &&
      text(s.plan,"installation") == recovery.installation && recovery.installation == writer.installation &&
      recovery.writer != writer.writer,"recovery-session-trust")
    s
  private def closed(c: Connection): Unit =
    check(rows(c,"SELECT recovery_required FROM repository_state WHERE singleton FOR UPDATE")(_.getBoolean(1)).head,"recovery-gate-open")
    rows(c,"SELECT singleton FROM organization_policy WHERE singleton FOR UPDATE")(_.getBoolean(1))
    ()
  private def binding(s: Session, storage: Path): Unit =
    check(text(s.plan,"storageIdentity") == PolicyWire.digest(storage.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"recovery-storage-binding")
  private def compatible(manifest: Json): F[Vector[(Int,String,String)]] = db.migrations.map { expected =>
    val source = field(manifest,"migrations").asArray.get
    check(Set(13,14)(source.size) && source.toVector == expected.take(source.size).map((n,h,_) => obj("version" -> Json.fromInt(n),"checksum" -> str(h))),"recovery-schema-compatibility")
    check(text(manifest,"schema") == config.schema && field(manifest,"capabilities") ==
      PolicyCapabilities.description(text(field(manifest,"policyHead"),"capabilities")),"recovery-capability-compatibility")
    expected
  }

  /** Caller verifies the source artifact against the supported packaged predecessor
    * or the exact running artifact before this method can be invoked.
    */
  def plan(bundle: Path, storage: Path, operation: String, operator: String): F[Json] = for
    manifest <- F.blocking(BackupBundle.verify(bundle))
    _ <- compatible(manifest)
    value <- F.blocking {
      id(operation);id(operator)
      check(bundle.toRealPath() == bundle && storage.isAbsolute && storage.normalize() == storage &&
        storage.getParent.toRealPath() == storage.getParent && !storage.startsWith(bundle) && !bundle.startsWith(storage),"recovery-target-path")
      val link = json(Files.readString(bundle.resolve("policy-link.json")))
      fields(link,Set("version","enrollment","head","authority"))
      check(text(link,"version") == "backup-policy-link-v1" && field(link,"head") == field(manifest,"policyHead"),"recovery-backup-link")
      val record = recovery.client.verifyRecord(field(link,"enrollment"))
      // Both independently loaded clients must authenticate the same enrollment.
      check(writer.client.verifyRecord(field(link,"enrollment")) == record && text(record,"kind") == "ENROLL" &&
        text(manifest,"installation") == recovery.installation && recovery.installation == writer.installation &&
        recovery.writer != writer.writer && writer.writer != text(field(manifest,"policyHead"),"writer"),"recovery-independent-trust")
      obj("version" -> str("organization-recovery-plan-v1"),"operation" -> str(operation),"operator" -> str(operator),
        "installation" -> str(recovery.installation),"backupDigest" -> str(BackupBundle.manifestDigest(manifest)),
        "manifest" -> manifest,"enrollmentCertificate" -> field(link,"enrollment"),
        "storageIdentity" -> str(PolicyWire.digest(storage.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        "recoveryIdentity" -> str(recovery.writer),"writerIdentity" -> str(writer.writer))
    }
  yield value

  def preview(bundle: Path, storage: Path, operation: String, operator: String): F[Json] = for
    p <- plan(bundle,storage,operation,operator)
    verified <- F.blocking(RecoveryJournal.read(recovery,field(p,"enrollmentCertificate"),field(field(p,"manifest"),"policyHead"),None))
    _ <- F.delay(check(!verified.writerHistory(writer.writer),"recovery-writer-already-used"))
  yield obj("status" -> str("preview"),"operation" -> str(operation),"backup" -> field(p,"backupDigest"),
    "planDigest" -> str(digest(p)),"currentPolicy" -> verified.summary,"access" -> str("not opened"))

  def restore(bundle: Path, storage: Path, operation: String, operator: String,
      pgRestore: String = "pg_restore", psql: String = "psql"): F[Unit] = for
    p <- plan(bundle,storage,operation,operator)
    migrations <- compatible(field(p,"manifest"))
    exists <- transaction { c =>
      check(rows(c,"SELECT rolsuper OR has_database_privilege(current_database(),'CREATE') FROM pg_roles WHERE rolname=current_user")(_.getBoolean(1)).head,"recovery-operator")
      rows(c,"SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)",Seq(config.schema))(_.getBoolean(1)).head
    }
    _ <- if exists then db.requireOperator *> db.checkCompatibility(requireOpen=false) *> transaction { c =>
      val s = session(c,operation)
      check(s.plan == p,"recovery-plan-conflict")
    } else F.blocking {
      RecoveryBundleRestore.storage(field(p,"manifest"),bundle,storage,operation)
      RecoveryBundleRestore.catalog(config,bundle,field(p,"manifest"),p,migrations,pgRestore,psql)
    }
    _ <- db.requireOperator *> db.checkCompatibility(requireOpen=false)
    _ <- transaction(c => binding(session(c,operation),storage))
  yield ()

  private def barrier(operation: String): F[Json] = for
    command <- transaction { c =>
      closed(c)
      val s = session(c,operation,true)
      check(Set("Restored","Verified")(s.phase),"recovery-verify-phase")
      s.verification.flatMap(v => v.asObject.flatMap(_("barrier"))) match
        case Some(existing) => existing
        case None =>
          update(c,"SET LOCAL idle_in_transaction_session_timeout='360s'")
          val observed = RecoveryJournal.read(recovery,field(s.plan,"enrollmentCertificate"),
            field(field(s.plan,"manifest"),"policyHead"),None)
          check(!observed.writerHistory(writer.writer),"recovery-writer-already-used")
          val head = observed.current
          check(field(head,"barrier").isNull && field(head,"pending").isNull,"recovery-existing-or-pending-barrier")
          val command = obj("operation" -> str(uuid(operation,"barrier")),"installation" -> str(recovery.installation),
            "epoch" -> str(PolicyWire.increment(natural(text(head,"epoch"))).toString),"reason" -> str("verified organization backup recovery"),
            "operator" -> str(recovery.writer),"at" -> str(Instant.now().toString),"challenge" -> str(UUID.randomUUID().toString))
          update(c,"UPDATE organization_recovery_session SET verification=?::jsonb WHERE operation=?::uuid",
            Seq(obj("barrier" -> command).noSpaces,operation))
          command
    }
    _ <- F.blocking {
      val head = RecoveryJournal.current(recovery)
      check(field(head,"pending").isNull && (field(head,"barrier").isNull || field(head,"barrier") == command),"recovery-barrier-conflict")
      val response = recovery.client.call(PolicyWitnessClient.request("RECOVERY_BARRIER",command,natural(text(head,"sequence")),text(head,"journalHash")))
      val record = recovery.client.verifyRecord(field(response,"record"))
      check(text(record,"kind") == "RECOVERY_BARRIER" && field(record,"command") == command,"recovery-barrier-certificate")
    }
    // A fresh signed response starts a new monotonic drain, including on retry.
    _ <- F.sleep(5.seconds)
  yield command

  def verify(operation: String, storage: Path): F[Json] =
    db.requireOperator *> db.checkCompatibility(requireOpen=false) *> transaction(c => session(c,operation)).flatMap { s =>
      F.blocking(binding(s,storage)) *> (if Set("Applied","Released","Opened")(s.phase) then status(operation) else verifyClosed(operation,storage))
    }

  private def verifyClosed(operation: String, storage: Path): F[Json] = for
    _ <- db.requireOperator *> db.checkCompatibility(requireOpen=false)
    s <- transaction(c => session(c,operation))
    _ <- F.blocking {binding(s,storage);RecoveryBundleRestore.verifyStorage(field(s.plan,"manifest"),storage)}
    command <- barrier(operation)
    verified <- F.blocking(RecoveryJournal.read(recovery,field(s.plan,"enrollmentCertificate"),field(field(s.plan,"manifest"),"policyHead"),Some(command)))
    _ <- transaction { c =>
      closed(c);val current = session(c,operation,true)
      check(Set("Restored","Verified")(current.phase),"recovery-verify-phase")
      verifyCatalogInventory(c,field(s.plan,"manifest"))
      val storedEnrollment = rows(c,"SELECT command::text,certificate::text FROM policy_enrollment")(r => json(r.getString(1)) -> json(r.getString(2))).head
      check(storedEnrollment == (verified.enrollment -> field(s.plan,"enrollmentCertificate")),"recovery-restored-enrollment")
      val value = obj("barrier" -> command,"summary" -> verified.summary)
      update(c,"UPDATE organization_recovery_session SET phase='Verified',verification=?::jsonb WHERE operation=?::uuid",Seq(value.noSpaces,operation))
    }
  yield obj("status" -> str("verified-closed"),"operation" -> str(operation),"backup" -> field(s.plan,"backupDigest"),"policy" -> verified.summary)

  def status(operation: String): F[Json] = db.requireOperator *> db.checkCompatibility(requireOpen=false) *> transaction { c =>
    val s = session(c,operation)
    val closed = rows(c,"SELECT recovery_required FROM repository_state WHERE singleton")(_.getBoolean(1)).head
    val witness = scala.util.Try(RecoveryJournal.current(recovery)).toOption
    val local = rows(c,"SELECT writer_epoch::text,revision::text,applied_hash FROM organization_policy")(r =>
      (r.getString(1),r.getString(2),r.getString(3))).head
    val ready = !closed && witness.exists(h => field(h,"barrier").isNull && field(h,"pending").isNull &&
      field(h,"writer").asString.contains(writer.writer) && text(h,"epoch") == local._1 &&
      text(h,"revision") == local._2 && text(h,"appliedHash") == local._3)
    obj("operation" -> str(operation),"phase" -> str(s.phase),"closed" -> Json.fromBoolean(closed),
      "witnessAvailable" -> Json.fromBoolean(witness.nonEmpty),"admissionReady" -> Json.fromBoolean(ready),
      "backup" -> field(s.plan,"backupDigest"),"verification" -> s.verification.getOrElse(Json.Null))
  }

  private def verifyCatalogInventory(c: Connection, manifest: Json): Unit =
    check(rows(c,"SELECT initialized AND durability_mode='independent' AND installation::text=? FROM organization_policy",Seq(recovery.installation))(_.getBoolean(1)).head,"recovery-organization-catalog")
    check(!rows(c,"""SELECT EXISTS(SELECT 1 FROM principal WHERE authority_eligibility<>'{"version":1,"value":{"Local":{}}}'::jsonb)
      OR EXISTS(SELECT 1 FROM dataset WHERE authority_lifecycle<>'Available') OR EXISTS(SELECT 1 FROM release WHERE authority_lifecycle<>'Available')""")(_.getBoolean(1)).head,"recovery-unsupported-enabled-capability")
    val expected = field(manifest,"policyHead")
    val state = rows(c,"SELECT writer_epoch::text,revision::text,applied_hash FROM organization_policy")(r =>
      (r.getString(1),r.getString(2),r.getString(3))).head
    check(state == ((text(expected,"epoch"),text(expected,"revision"),text(expected,"appliedHash"))) &&
      PolicyBridge.capabilities(c) == text(expected,"capabilities"),"recovery-restored-policy-point")
    check(!rows(c,"SELECT EXISTS(SELECT 1 FROM organization_event e LEFT JOIN policy_acknowledgement a ON a.event_id=e.id WHERE e.durability='Pending' AND a.event_id IS NULL)")(_.getBoolean(1)).head,"recovery-restored-unacknowledged")
    val blobs = rows(c,"SELECT digest,size FROM blob")(r => r.getString(1) -> r.getLong(2)) ++
      rows(c,"SELECT payload::text FROM acquired_file")(r => JsonCodecs.decode[Entry](r.getString(1)).fold(
        _ => throw PolicyFailure("recovery-acquired-schema"),identity)).map {
          case Entry.File(_,_,b) => b.digest.value -> b.size
          case _ => throw PolicyFailure("recovery-acquired-schema")
      }
    val actual = blobs.distinct.map((h,n) => obj("sha256" -> str(h),"size" -> Json.fromLong(n))).toSet
    check(actual == field(manifest,"objects").asArray.get.toSet,"recovery-catalog-object-inventory")

  private def scientificDigest(c: Connection): String =
    val excluded = Set("principal","api_credential","credential_audit","access_grant","policy_audit","policy_acknowledgement","policy_retired_runtime")
    val tables = rows(c,"SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename")(_.getString(1))
      .filterNot(t => t.startsWith("organization_") || excluded(t))
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    tables.foreach { table =>
      check(table.matches("[a-z][a-z0-9_]+"),"recovery-table")
      val value = table match
        case "import_operation" | "outbox_delivery" => "to_jsonb(t)-'lease_token'-'lease_until'"
        case "processing_run" => "(to_jsonb(t)-'payload') || jsonb_build_object('payload',(payload-'value') || jsonb_build_object('value',(payload->'value')-'generation'-'status'-'detail'))"
        case _ => "to_jsonb(t)"
      hash.update((table+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val statement = c.prepareStatement(s"SELECT ($value)::text FROM $table t ORDER BY ($value)::text")
      statement.setFetchSize(256)
      try
        val result = statement.executeQuery()
        try while result.next() do hash.update((result.getString(1)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
        finally result.close()
      finally statement.close()
    }
    hash.digest().map(b => f"${b & 255}%02x").mkString

  private def eventReceipt(event: Json, payload: Json): Json =
    import OrganizationCodecs.given
    val request = field(payload,"request")
    val identifier = text(event,"operation")
    if request.isNull then obj("version" -> Json.fromInt(1),"value" -> obj(
      "sequence" -> field(event,"revision"),"event" -> str(identifier),"actor" -> field(payload,"actor"),
      "key" -> field(payload,"key"),"at" -> field(payload,"at"),"durability" -> str("Pending")))
    else
      val affected = field(payload,"changes").asArray.get.map { change =>
        val kind = text(change,"kind");val value = field(change,"after")
        val name = kind.head.toUpper.toString + kind.tail
        val key = text(value,if kind == "placement" then "dataset" else "id")
        obj(name -> obj("id" -> str(key))).as[OrganizationRef].fold(_ => throw PolicyFailure("recovery-receipt-reference"),identity)
      }.toSet
      json(JsonCodecs.encode(OrganizationReceipt(natural(text(event,"revision")),Id.parse[PrincipalTag](text(payload,"actor")).toOption.get,
        IdempotencyKey.from(text(payload,"key")).toOption.get,affected,Instant.parse(text(payload,"at")),PolicyDurability.Pending)))

  private def sameReceipt(left: Json, right: Json): Boolean =
    // OrganizationReceipt encodes a Set; its array order is not part of authority.
    def ordered(value: Json): Json = value.mapObject { outer =>
      outer("value").fold(outer) { body =>
        outer.add("value",body.mapObject { inner =>
          inner("affected").flatMap(_.asArray).fold(inner)(refs =>
            inner.add("affected",Json.fromValues(refs.sortBy(_.printWith(sorted)))))
        })
      }
    }
    ordered(left) == ordered(right)

  private def persist(c: Connection, operation: String, verified: RecoveryJournal.Verified, manifest: Json, at: String): Json =
    val projection = verified.projection
    val originalHistory = field(field(projection.checkpoint,"tables"),"organization_event").asArray.get.map(r => text(r,"id") -> r).toMap
    val checkpointRevision = natural(text(projection.checkpoint,"revision"))
    val backupRevision = natural(text(field(manifest,"policyHead"),"revision"))
    val capturedCommits = projection.commits.filter((event,_) => natural(text(event,"revision")) <= backupRevision).map((e,_) => text(e,"operation") -> e).toMap
    val observedHistory = rows(c,"SELECT to_jsonb(e)::text FROM organization_event e")(r => json(r.getString(1)))
    check(observedHistory.map(j => text(j,"id")).toSet == originalHistory.keySet ++ capturedCommits.keySet,"recovery-restored-history-inventory")
    val prepareCertificates = verified.records.map(certificate => recovery.client.verifyRecord(certificate) -> certificate)
      .collect { case (record,certificate) if text(record,"kind") == "PREPARE" => text(field(record,"command"),"operation") -> certificate }.toMap
    observedHistory.foreach { row =>
      val identifier = text(row,"id")
      if originalHistory.contains(identifier) then
        val original = originalHistory(identifier)
        check(row.mapObject(_.remove("recorded_at")) == original.mapObject(_.remove("recorded_at")) &&
          Instant.parse(text(row,"recorded_at")) == Instant.parse(text(original,"recorded_at")),"recovery-original-history-changed")
      else
        val event = capturedCommits(identifier)
        val payload = json(new String(PolicyWire.unbase64(text(event,"payload")),java.nio.charset.StandardCharsets.UTF_8))
        check(natural(text(event,"revision")) > checkpointRevision && field(row,"payload") == payload &&
          field(row,"revision").asNumber.flatMap(_.toLong).contains(natural(text(event,"revision"))) &&
          field(row,"writer_epoch").asNumber.flatMap(_.toLong).contains(natural(text(event,"epoch"))) &&
          text(row,"installation") == text(event,"installation") && text(row,"previous_hash") == text(event,"previousHash") &&
          text(row,"payload_digest") == text(event,"payloadDigest") && text(row,"applied_hash") == text(event,"appliedHash") &&
          text(row,"actor") == text(payload,"actor") && text(row,"scope") == text(payload,"scope") &&
          text(row,"request_key") == text(payload,"key") && field(row,"request") == field(payload,"request") &&
          sameReceipt(field(row,"receipt"),eventReceipt(event,payload)) && text(row,"durability") == "Pending" &&
          field(row,"prepare_certificate") == prepareCertificates(identifier) && field(row,"commit_certificate").isNull &&
          Instant.parse(text(row,"recorded_at")) == Instant.parse(text(payload,"at")),"recovery-committed-history-changed")
    }
    val existingPeople = rows(c,"SELECT id::text FROM principal")(_.getString(1)).toSet
    check(existingPeople.subsetOf(projection.people.keySet),"recovery-uncovered-principal")
    val existingData = rows(c,"SELECT id::text,custodian::text,curator::text FROM dataset")(r =>
      r.getString(1) -> obj("id" -> str(r.getString(1)),"custodian" -> str(r.getString(2)),"attributedCurator" -> str(r.getString(3)))).toMap
    check(existingData.forall((k,v) => projection.datasets.get(k).contains(v)),"recovery-scientific-attribution")
    val releases = rows(c,"SELECT id::text,dataset_id::text FROM release")(r => r.getString(1) -> r.getString(2)).toMap
    val existingGrants = rows(c,"SELECT id::text FROM access_grant")(_.getString(1)).toSet
    check(existingGrants.subsetOf(projection.grants.keySet),"recovery-uncovered-grant")
    val existingCredentials = rows(c,"SELECT id::text FROM api_credential")(_.getString(1)).toSet
    check(existingCredentials.subsetOf(projection.credentials.keySet),"recovery-uncovered-credential")
    def scalar(value: Json): String = value.asString.orNull
    projection.people.values.foreach { p =>
      update(c,"""INSERT INTO principal(id,label,active,generation,created_at,authority_eligibility)
        VALUES(?::uuid,?,?::boolean,?::bigint,?::timestamptz,'{"version":1,"value":{"Local":{}}}'::jsonb)
        ON CONFLICT(id) DO UPDATE SET label=EXCLUDED.label,active=EXCLUDED.active,generation=EXCLUDED.generation,
          created_at=EXCLUDED.created_at,authority_eligibility=EXCLUDED.authority_eligibility""",
        Seq(text(p,"id"),text(p,"label"),field(p,"active").noSpaces,field(p,"generation").noSpaces,text(p,"createdAt")))
    }
    val kinds = Vector("lab","project","membership","participation","placement","stewardship","link","move")
    kinds.reverse.foreach(kind => update(c,"DELETE FROM organization_"+kind))
    kinds.foreach { kind =>
      projection.records(kind).values.filter { value =>
        !Set("placement","stewardship","link","move")(kind) || existingData.contains(text(value,"dataset"))
      }.foreach { value =>
        val encoded = obj("version" -> Json.fromInt(1),"value" -> value)
        update(c,"INSERT INTO organization_"+kind+"(payload) VALUES(?::jsonb)",Seq(encoded.noSpaces))
      }
    }
    projection.grants.values.foreach { g =>
      val scope = field(g,"scope");val data = text(scope,"dataset");val release = field(scope,"release").asString
      if existingData.contains(data) && release.forall(r => releases.get(r).contains(data)) then
        val basis = field(g,"basis")
        val basisName = if basis.asObject.get.contains("Independent") then "Independent" else "Derived"
        val encodedBasis = obj("version" -> Json.fromInt(1),"value" -> basis)
        val revoked = projection.revokedAt.get(text(g,"id")).fold[String](null)(scalar)
        val actions = field(g,"actions").asArray.get.map(_.asString.get).sorted.mkString("{",",","}")
        update(c,"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions,revoked_at,issuer,expires_at,basis,basis_payload,generation,created_at)
          VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::text[],?::timestamptz,?::uuid,?::timestamptz,?,?::jsonb,?::bigint,?::timestamptz)
          ON CONFLICT(id) DO UPDATE SET dataset_id=EXCLUDED.dataset_id,recipient=EXCLUDED.recipient,release_id=EXCLUDED.release_id,
          actions=EXCLUDED.actions,revoked_at=EXCLUDED.revoked_at,issuer=EXCLUDED.issuer,expires_at=EXCLUDED.expires_at,
          basis=EXCLUDED.basis,basis_payload=EXCLUDED.basis_payload,generation=EXCLUDED.generation,created_at=EXCLUDED.created_at""",
          Seq(text(g,"id"),data,text(g,"recipient"),release.orNull,actions,revoked,scalar(field(g,"issuer")),
            scalar(field(g,"expiresAt")),basisName,encodedBasis.noSpaces,field(g,"generation").noSpaces,text(g,"createdAt")))
    }
    val missingData = projection.datasets.keySet -- existingData.keySet
    val missingReleases = projection.grants.values.flatMap(g => field(field(g,"scope"),"release").asString).toSet -- releases.keySet
    def tombstone(kind: String, target: String, policy: Json): Unit =
      update(c,"""INSERT INTO organization_recovery_tombstone(kind,target,policy,recovery_operation) VALUES(?,?::uuid,?::jsonb,?::uuid)
        ON CONFLICT(kind,target) DO UPDATE SET policy=EXCLUDED.policy,recovery_operation=EXCLUDED.recovery_operation""",
        Seq(kind,target,policy.noSpaces,operation))
    missingData.foreach { target =>
      val related = Vector("placement","stewardship","link","move").map { kind =>
        kind -> Json.arr(projection.records(kind).values.filter(v => text(v,"dataset") == target).toVector*)
      }
      val grants = projection.grants.values.filter(g => text(field(g,"scope"),"dataset") == target).toVector
      tombstone("dataset",target,obj("dataset" -> projection.datasets(target),"records" -> Json.obj(related*),
        "grants" -> Json.arr(grants*),"head" -> verified.current))
    }
    missingReleases.foreach { target =>
      val grants = projection.grants.values.filter(g => field(field(g,"scope"),"release").asString.contains(target)).toVector
      check(grants.map(g => text(field(g,"scope"),"dataset")).distinct.size == 1,"recovery-release-scope")
      tombstone("release",target,obj("grants" -> Json.arr(grants*),"head" -> verified.current))
    }
    val credentialCount = rows(c,"SELECT count(*) FROM api_credential WHERE revoked_at IS NULL")(_.getLong(1)).head
    projection.credentials.values.foreach { token =>
      val identifier = text(token,"id")
      val retiredDigest = Digest.framed("sanctity.recovery-retired-credential.v1",Vector(recovery.installation,identifier)).value
      val revoked = field(token,"revokedAt").asString.getOrElse(at)
      update(c,"""INSERT INTO api_credential(id,principal_id,token_digest,label,issued_at,expires_at,revoked_at)
        VALUES(?::uuid,?::uuid,?,?,?::timestamptz,?::timestamptz,?::timestamptz)
        ON CONFLICT(id) DO UPDATE SET revoked_at=EXCLUDED.revoked_at,principal_id=EXCLUDED.principal_id,
          token_digest=EXCLUDED.token_digest,label=EXCLUDED.label,issued_at=EXCLUDED.issued_at,expires_at=EXCLUDED.expires_at""",
        Seq(identifier,text(token,"principal"),retiredDigest,text(token,"label"),text(token,"issuedAt"),text(token,"expiresAt"),revoked))
      update(c,"INSERT INTO credential_audit(credential_id,action,recorded_at) VALUES(?::uuid,'RecoveryInvalidated',?::timestamptz)",Seq(identifier,at))
    }
    update(c,"UPDATE import_operation SET lease_token=NULL,lease_until=NULL")
    update(c,"UPDATE outbox_delivery SET lease_token=NULL,lease_until=NULL")
    val activeRuns = rows(c,"SELECT id::text,payload::text FROM processing_run")(r => r.getString(1) ->
      JsonCodecs.decode[ProcessingRun](r.getString(2)).fold(_ => throw PolicyFailure("recovery-run-schema"),identity))
      .filter((_,r) => Set(RunStatus.Planned,RunStatus.Preparing,RunStatus.Executing)(r.status))
    activeRuns.foreach { (identifier,run) =>
      val failed = run.advance(RunUpdate(RunStatus.Failed,run.execution,detail=Some("Execution invalidated by repository recovery")))
        .fold(_ => throw PolicyFailure("recovery-run-transition"),identity)
      update(c,"UPDATE processing_run SET payload=?::jsonb WHERE id=?::uuid",Seq(JsonCodecs.encode(failed),identifier))
    }
    // Preserve original events. Missing post-backup events are reconstructed from
    // the exact independently committed payload and its two signed certificates.
    val prepared = verified.records.map(certificate => recovery.client.verifyRecord(certificate) -> certificate)
      .collect { case (record,certificate) if text(record,"kind") == "PREPARE" => text(field(record,"command"),"operation") -> certificate }.toMap
    projection.commits.foreach { (event,certificate) =>
      val payload = json(new String(PolicyWire.unbase64(text(event,"payload")),java.nio.charset.StandardCharsets.UTF_8))
      val identifier = text(event,"operation")
      val existing = rows(c,"SELECT payload::text,prepare_certificate::text FROM organization_event WHERE id=?::uuid",Seq(identifier))(
        r => json(r.getString(1)) -> json(r.getString(2)))
      val prepare = prepared.getOrElse(identifier,throw PolicyFailure("recovery-prepare-missing"))
      if existing.nonEmpty then check(existing == Vector(payload -> prepare),"recovery-existing-event")
      else
        val request = field(payload,"request")
        val receipt = eventReceipt(event,payload)
        update(c,"""INSERT INTO organization_event(id,revision,installation,writer_epoch,previous_hash,payload_digest,applied_hash,
          actor,scope,request_key,request,receipt,payload,recorded_at,durability,prepare_certificate)
          VALUES(?::uuid,?::bigint,?::uuid,?::bigint,?,?,?,?::uuid,?,?,?::jsonb,?::jsonb,?::jsonb,?::timestamptz,'Pending',?::jsonb)""",
          Seq(identifier,text(event,"revision"),text(event,"installation"),text(event,"epoch"),text(event,"previousHash"),text(event,"payloadDigest"),
            text(event,"appliedHash"),text(payload,"actor"),text(payload,"scope"),text(payload,"key"),if request.isNull then null else request.noSpaces,
            receipt.noSpaces,payload.noSpaces,text(payload,"at"),prepare.noSpaces))
      update(c,"INSERT INTO policy_acknowledgement(event_id,certificate) VALUES(?::uuid,?::jsonb) ON CONFLICT(event_id) DO NOTHING",Seq(identifier,certificate.noSpaces))
      val stored = rows(c,"SELECT certificate::text FROM policy_acknowledgement WHERE event_id=?::uuid",Seq(identifier))(r => json(r.getString(1))).head
      check(stored == certificate,"recovery-existing-acknowledgement")
    }
    update(c,"UPDATE organization_policy SET writer_epoch=?::bigint,revision=?::bigint,applied_hash=? WHERE singleton",
      Seq(text(verified.current,"epoch"),text(verified.current,"revision"),text(verified.current,"appliedHash")))
    db.policy.retireRuntime(c)
    obj("restoredCredentialsInvalidated" -> Json.fromLong(credentialCount),"allCredentialIdentitiesRetired" -> Json.fromInt(projection.credentials.size),
      "activeRunsFailed" -> Json.fromInt(activeRuns.size),"missingDatasets" -> Json.fromInt(missingData.size),"missingReleases" -> Json.fromInt(missingReleases.size),
      "allImportAndDeliveryLeasesInvalidated" -> Json.True)

  def reconcile(operation: String, storage: Path): F[Json] = reconcileObserved(operation,storage,_ => F.unit)

  /** Process-boundary hooks are used only by the separate qualification entrypoint. */
  private[postgres] def reconcileObserved(operation: String, storage: Path, afterPhase: String => F[Unit]): F[Json] = for
    _ <- db.requireOperator *> db.checkCompatibility(requireOpen=false)
    initial <- transaction(c => session(c,operation))
    _ <- F.blocking(binding(initial,storage))
    _ <- if initial.phase == "Opened" then F.unit else for
      _ <- F.blocking(RecoveryBundleRestore.verifyStorage(field(initial.plan,"manifest"),storage))
      _ <- if initial.phase != "Verified" then F.unit else for
        command <- F.fromOption(initial.verification.flatMap(_.asObject.flatMap(_("barrier"))),PolicyFailure("recovery-verify-required"))
        verified <- F.blocking(RecoveryJournal.read(recovery,field(initial.plan,"enrollmentCertificate"),
          field(field(initial.plan,"manifest"),"policyHead"),Some(command)))
        _ <- afterPhase("before-apply")
        _ <- transaction { c =>
          closed(c);val s = session(c,operation,true)
          check(s.phase == "Verified" && field(s.verification.get,"summary") == verified.summary,"recovery-verification-changed")
          verifyCatalogInventory(c,field(s.plan,"manifest"))
          val before = scientificDigest(c)
          val at = rows(c,"SELECT clock_timestamp()")(_.getTimestamp(1).toInstant.toString).head
          val actor = rows(c,"SELECT current_user")(_.getString(1)).head
          val outcomes = persist(c,operation,verified,field(s.plan,"manifest"),at)
          val after = scientificDigest(c)
          check(before == after,"recovery-scientific-history-changed")
          val receipt = obj("version" -> str("organization-recovery-receipt-v1"),"operation" -> str(operation),
            "backupDigest" -> field(s.plan,"backupDigest"),"operator" -> field(s.plan,"operator"),"databaseActor" -> str(actor),
            "at" -> str(at),"barrier" -> command,"summary" -> verified.summary,"outcomes" -> outcomes,
            "scientificDigestBefore" -> str(before),"scientificDigestAfter" -> str(after))
          val release = obj("operation" -> str(uuid(operation,"release")),"installation" -> str(recovery.installation),
            "barrier" -> field(command,"operation"),"epoch" -> field(verified.current,"epoch"),
            "revision" -> field(verified.current,"revision"),"appliedHash" -> field(verified.current,"appliedHash"),
            "writer" -> str(writer.writer),"receiptDigest" -> str(digest(receipt)),"operator" -> str(recovery.writer),"at" -> str(at))
          update(c,"UPDATE organization_recovery_session SET phase='Applied',verification=?::jsonb,release_command=?::jsonb WHERE operation=?::uuid",
            Seq(receipt.noSpaces,release.noSpaces,operation))
        }
        _ <- afterPhase("after-apply")
      yield ()
      applied <- transaction(c => session(c,operation))
      _ <- if applied.phase != "Applied" then F.unit else for
        command <- F.fromOption(applied.command,PolicyFailure("recovery-release-command"))
        _ <- afterPhase("before-release")
        certificate <- F.blocking {
          val head = RecoveryJournal.current(recovery)
          val response = recovery.client.call(PolicyWitnessClient.request("RECOVERY_RELEASE",command,natural(text(head,"sequence")),text(head,"journalHash")))
          val certificate = field(response,"record")
          val record = recovery.client.verifyRecord(certificate)
          check(text(record,"kind") == "RECOVERY_RELEASE" && field(record,"command") == command,"recovery-release-certificate")
          certificate
        }
        _ <- afterPhase("after-release-before-receipt")
        _ <- transaction { c =>
          closed(c);val s = session(c,operation,true)
          check(s.phase == "Applied" && s.command.contains(command),"recovery-release-session")
          update(c,"UPDATE organization_recovery_session SET phase='Released',release_certificate=?::jsonb WHERE operation=?::uuid",
            Seq(certificate.noSpaces,operation))
        }
        _ <- afterPhase("after-release-receipt")
      yield ()
      released <- transaction(c => session(c,operation))
      _ <- if released.phase == "Opened" then F.unit else for
        _ <- F.delay(check(released.phase == "Released","recovery-reconcile-requires-verified-session"))
        _ <- afterPhase("before-open")
        _ <- transaction { c =>
          update(c,"SET LOCAL transaction_timeout='5s'")
          update(c,"SET LOCAL statement_timeout='5s'")
          closed(c);val s = session(c,operation,true)
          check(s.phase == "Released","recovery-open-session")
          val command = s.command.get; val certificate = s.certificate.get
          val record = writer.client.verifyRecord(certificate)
          check(text(record,"kind") == "RECOVERY_RELEASE" && field(record,"command") == command &&
            text(command,"receiptDigest") == digest(s.verification.get),"recovery-open-receipt")
          val head = RecoveryJournal.current(writer)
          val local = rows(c,"SELECT writer_epoch::text,revision::text,applied_hash FROM organization_policy")(r =>
            (r.getString(1),r.getString(2),r.getString(3))).head
          check(field(head,"barrier").isNull && field(head,"pending").isNull && text(head,"writer") == writer.writer &&
            text(head,"epoch") == local._1 && text(head,"revision") == local._2 && text(head,"appliedHash") == local._3 &&
            text(head,"sequence") == text(record,"sequence") && text(head,"journalHash") == PolicyWire.digest(record),"recovery-new-writer-head")
          update(c,"UPDATE organization_recovery_session SET phase='Opened',opened_at=clock_timestamp() WHERE operation=?::uuid",Seq(operation))
          update(c,"UPDATE repository_state SET recovery_required=FALSE WHERE singleton")
        }
        _ <- afterPhase("after-open")
      yield ()
    yield ()
    result <- status(operation)
  yield result
