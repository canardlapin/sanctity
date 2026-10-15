package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.free.connection as FC
import doobie.implicits.*
import io.circe.{Json, Printer}
import sanctity.policy.*
import java.sql.Connection
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Closed, explicit maintenance enrollment. This cannot enroll a restored independent
  * catalog, release a recovery barrier, or reopen organization recovery.
  */
final class PostgresPolicy[F[_]: Async] private[postgres](db: PostgresDatabase[F]):
  import PolicyWire.*
  import PolicyBridge.{rows, update}
  private val xa = db.transactor
  private val sorted = Printer.noSpaces.copy(sortKeys=true)
  private def json(value: String): Json = io.circe.jawn.parse(value).fold(_ => throw PolicyFailure("catalog-json"),identity)
  private def lock(c: Connection): Boolean =
    val closed = rows(c,"SELECT recovery_required FROM repository_state WHERE singleton FOR UPDATE")(_.getBoolean(1)).head
    rows(c,"SELECT singleton FROM organization_policy WHERE singleton FOR UPDATE")(_.getBoolean(1))
    closed

  /** Explicit columns exclude bearer digests and lease capabilities. All rows are
    * included, including inactive principals, revoked credentials/grants and tombstones.
    */
  private val inventory = Vector(
    "schema_migration" -> "SELECT * FROM schema_migration",
    "organization_policy" -> "SELECT initialized,installation,bootstrap_operator,migration_digest,migration_report,revision,writer_epoch,applied_hash FROM organization_policy",
    "principal" -> "SELECT * FROM principal",
    "dataset" -> "SELECT id,custodian,curator,head,authority_lifecycle FROM dataset",
    "release" -> "SELECT id,dataset_id,accepted_by,accepted_at,authority_lifecycle FROM release",
    "access_grant" -> "SELECT * FROM access_grant",
    "api_credential" -> "SELECT id,principal_id,label,issued_at,expires_at,revoked_at FROM api_credential",
    "credential_audit" -> "SELECT * FROM credential_audit",
    "policy_audit" -> "SELECT * FROM policy_audit",
    "import_operation" -> "SELECT id,dataset_id,owner,lease_token IS NOT NULL AS leased,lease_until,receipt IS NOT NULL AS completed FROM import_operation",
    "outbox_delivery" -> "SELECT sequence,target,lease_token IS NOT NULL AS leased,lease_until,delivered_at,attempts FROM outbox_delivery",
    "processing_run" -> "SELECT id,destination,owner,payload FROM processing_run",
    "organization_event" -> "SELECT * FROM organization_event",
    "policy_retired_runtime" -> "SELECT * FROM policy_retired_runtime"
  ) ++ Vector("lab","project","membership","participation","placement","stewardship","link")
    .map(t => "organization_"+t -> s"SELECT payload FROM organization_$t")

  private def checkpoint(c: Connection): Array[Byte] =
    val supported = rows(c,"""SELECT NOT EXISTS(SELECT 1 FROM principal WHERE authority_eligibility <> '{"version":1,"value":{"Local":{}}}'::jsonb)
      AND NOT EXISTS(SELECT 1 FROM dataset WHERE authority_lifecycle<>'Available')
      AND NOT EXISTS(SELECT 1 FROM release WHERE authority_lifecycle<>'Available')""")(_.getBoolean(1)).head
    check(supported,"unsupported-authority-producer")
    val state = rows(c,"SELECT installation::text,writer_epoch::text,revision::text,applied_hash,initialized FROM organization_policy")(
      r => (r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5))).head
    check(state._5,"organization-bootstrap-required")
    val history = rows(c,"SELECT id::text,revision,previous_hash,payload_digest,applied_hash,payload::text FROM organization_event ORDER BY revision")(
      r => (r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),json(r.getString(6))))
    check(history.nonEmpty && history.head._2 == 0,"history-genesis")
    var previous = zero
    history.zipWithIndex.foreach { case ((id,revision,before,payloadDigest,applied,payload),index) =>
      val version = text(payload,"version")
      check(Set("organization-local-v1","policy-local-v2")(version),"unsupported-history-version")
      if version == "policy-local-v2" then
        check(PolicyCapabilities.kinds(text(payload,"kind")) || (index == 0 && text(payload,"kind") == "OrganizationGenesis"),
          "unsupported-historical-producer")
      val bytes = payload.printWith(sorted).getBytes(StandardCharsets.UTF_8)
      check(revision == index.toLong && before == previous && digest(bytes) == payloadDigest,"history-continuity")
      val domain = if version == "policy-local-v2" then "sanctity.policy-local.v2" else "sanctity.organization-local.v1"
      check(sanctity.kernel.Digest.framed(domain,Vector(before,id,revision.toString,payloadDigest)).value == applied,"history-applied-hash")
      previous = applied
    }
    check(history.last._2.toString == state._3 && previous == state._4,"history-current-head")
    val tables = inventory.map { (name,query) =>
      val data = rows(c,s"SELECT to_jsonb(t)::text FROM ($query) t ORDER BY to_jsonb(t)::text")(r => json(r.getString(1)))
      name -> Json.arr(data*)
    }
    val bytes = obj("version" -> str("policy-checkpoint-v1"),"capabilities" -> PolicyCapabilities.inventory,
      "installation" -> str(state._1),"epoch" -> str(state._2),"revision" -> str(state._3),
      "appliedHash" -> str(state._4),"tables" -> Json.obj(tables*)).printWith(sorted).getBytes(StandardCharsets.UTF_8)
    check(bytes.length <= maximumCheckpointBytes,"checkpoint-too-large")
    bytes

  /** The old runtime's SQL privileges stay revoked after opening. The documented
    * deployment step provisions a fresh ordinary login for the witness-aware binary.
    * Privileged operators remain explicit trusted operators, never runtime identities.
    */
  private def retireRuntime(c: Connection): Unit =
    val schema = rows(c,"SELECT current_schema()")(_.getString(1)).head
    val roles = rows(c,"""SELECT r.rolname FROM pg_roles r WHERE r.rolcanlogin AND NOT r.rolsuper
      AND r.rolname<>current_user AND r.oid<>(SELECT nspowner FROM pg_namespace WHERE nspname=current_schema())
      AND EXISTS(SELECT 1 FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
        WHERE n.nspname=current_schema() AND t.relkind IN ('r','p','v','m','f') AND
        (has_table_privilege(r.oid,t.oid,'SELECT,INSERT,UPDATE,DELETE') OR has_any_column_privilege(r.oid,t.oid,'SELECT,INSERT,UPDATE')))""")(_.getString(1))
    def quoted(s: String): String = "\"" + s.replace("\"","\"\"") + "\""
    roles.foreach { role =>
      update(c,s"REVOKE ALL ON ALL TABLES IN SCHEMA ${quoted(schema)} FROM ${quoted(role)}")
      update(c,s"REVOKE ALL ON ALL SEQUENCES IN SCHEMA ${quoted(schema)} FROM ${quoted(role)}")
      update(c,s"REVOKE ALL ON SCHEMA ${quoted(schema)} FROM ${quoted(role)}")
      update(c,"INSERT INTO policy_retired_runtime(role_name) VALUES(?) ON CONFLICT DO NOTHING",Seq(role))
    }
    // An inherited or PUBLIC grant must be fixed explicitly before enrollment.
    check(!rows(c,"""SELECT EXISTS(SELECT 1 FROM pg_roles r WHERE r.rolcanlogin AND NOT r.rolsuper
      AND r.rolname<>current_user AND r.oid<>(SELECT nspowner FROM pg_namespace WHERE nspname=current_schema())
      AND has_table_privilege(r.oid,'organization_policy','SELECT'))""")(_.getBoolean(1)).head,"runtime-fence-incomplete")

  def enroll(writer: PolicyConnection, recovery: PolicyWitnessClient): F[Json] =
    db.requireOperator *> db.checkCompatibility(requireOpen=false) *> (for
      command <- FC.raw { c =>
        val closed = lock(c)
        val mode = rows(c,"SELECT durability_mode FROM organization_policy")(_.getString(1)).head
        val existing = rows(c,"SELECT command::text FROM policy_enrollment")(_.getString(1)).headOption.map(json)
        existing match
          case Some(command) =>
            check(mode == "enrolling" && closed,"enrollment-already-complete-or-recovery-closed")
            check(text(command,"installation") == writer.installation && text(command,"writer") == writer.writer,
              "enrollment-trust-binding")
            check(digest(checkpoint(c)) == text(command,"checkpointDigest"),"enrollment-state-changed")
            command
          case None =>
            check(!closed && mode == "local-engineering","fresh-live-enrollment-required")
            retireRuntime(c)
            val bytes = checkpoint(c)
            val state = rows(c,"SELECT installation::text,writer_epoch::text,revision::text,applied_hash FROM organization_policy")(
              r => (r.getString(1),r.getString(2),r.getString(3),r.getString(4))).head
            check(state._1 == writer.installation,"installation")
            val command = obj("version" -> str(PolicyJournal.enrollmentVersion),"capabilities" -> str(PolicyCapabilities.digest),
              "installation" -> str(state._1),"operation" -> str(UUID.randomUUID().toString),"epoch" -> str(state._2),
              "revision" -> str(state._3),"appliedHash" -> str(state._4),"writer" -> str(writer.writer),
              "checkpoint" -> str(base64(bytes)),"checkpointDigest" -> str(digest(bytes)))
            PolicyJournal.validateEnrollment(command)
            update(c,"INSERT INTO policy_enrollment(command) VALUES(?::jsonb)",Seq(command.noSpaces))
            update(c,"UPDATE organization_policy SET durability_mode='enrolling' WHERE singleton")
            update(c,"UPDATE repository_state SET recovery_required=true WHERE singleton")
            command
      }.transact(xa)
      // The closed fence and exact checkpoint are durable before contacting the witness.
      response <- Async[F].blocking {
        val current = field(recovery.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current")
        recovery.call(PolicyWitnessClient.request("ENROLL",command,natural(text(current,"sequence")),text(current,"journalHash")))
      }
      certificate <- Async[F].blocking {
        val certificate = field(response,"record")
        val record = recovery.verifyRecord(certificate)
        check(text(record,"kind") == "ENROLL" && field(record,"command") == command,"enrollment-certificate")
        certificate
      }
      _ <- FC.raw { c =>
        check(lock(c),"enrollment-fence-required")
        check(rows(c,"SELECT durability_mode FROM organization_policy")(_.getString(1)).head == "enrolling","enrollment-mode")
        check(digest(checkpoint(c)) == text(command,"checkpointDigest"),"enrollment-state-changed")
        val current = field(writer.client.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current")
        check(text(current,"installation") == writer.installation && text(current,"writer") == writer.writer &&
          text(current,"capabilities") == PolicyCapabilities.digest && text(current,"epoch") == text(command,"epoch") &&
          text(current,"revision") == text(command,"revision") && text(current,"appliedHash") == text(command,"appliedHash") &&
          field(current,"pending").isNull && field(current,"barrier").isNull,"enrollment-currentness")
        update(c,"UPDATE policy_enrollment SET certificate=?::jsonb WHERE singleton AND certificate IS NULL",Seq(certificate.noSpaces))
        update(c,"UPDATE organization_policy SET durability_mode='independent' WHERE singleton")
        update(c,"UPDATE repository_state SET recovery_required=false WHERE singleton")
      }.transact(xa)
    yield obj("status" -> str("enrolled"),"installation" -> str(writer.installation),"checkpointDigest" -> field(command,"checkpointDigest")))

  /** Readiness has no names, credentials, target IDs, checkpoint bodies or event payloads. */
  def readiness: F[Json] =
    (OperationsSql.active *> OrganizationSql.guard(false) *> FC.raw { c =>
      val row = rows(c,"SELECT installation::text,writer_epoch::text,revision::text,applied_hash,durability_mode FROM organization_policy")(
        r => (r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))).head
      val pending = rows(c,"SELECT EXISTS(SELECT 1 FROM organization_event e LEFT JOIN policy_acknowledgement a ON a.event_id=e.id " +
        "WHERE e.durability='Pending' AND a.event_id IS NULL)")(_.getBoolean(1)).head
      obj("installation" -> str(row._1),"epoch" -> str(row._2),"revision" -> str(row._3),"appliedHash" -> str(row._4),
        "mode" -> str(row._5),"pending" -> Json.fromBoolean(pending),"capabilities" -> str(PolicyCapabilities.digest))
    }).transact(xa)

  def resolve: F[Json] = db.requireOperator *>
    (OperationsSql.active *> OrganizationSql.guard(true)).transact(xa) *> readiness
