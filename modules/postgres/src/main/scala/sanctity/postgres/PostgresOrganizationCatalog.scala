package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment}
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.{Decoder, Encoder, Json, Printer}
import io.circe.syntax.*
import sanctity.application.{OrganizationMigration, ReferenceOrganizationCatalog}
import sanctity.kernel.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Trusted adapter. Public projections/administration remain F02.3/F03/F05.
  * LocalOnly receipts explicitly do not acknowledge independent policy durability.
  */
final class PostgresOrganizationCatalog[F[_]: Async] private[postgres](db: PostgresDatabase[F]):
  import Records.{lift, uuid}
  import JsonCodecs.given
  import OrganizationCodecs.given
  import ReferenceOrganizationCatalog.Snapshot
  private val xa = db.transactor
  private val parser = new io.circe.jawn.JawnParser(maxValueSize=None,allowDuplicateKeys=false)

  private[sanctity] def snapshot: F[Snapshot] =
    (OperationsSql.active *> OrganizationSql.guard(false) *> load).transact(xa)

  def inheritanceImpact(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, OrganizationAccessImpact]] = OperationsSql.run(xa) {
    for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(false)
      _ <- lift(Either.cond(enabled, (), Problem.Conflict("organization bootstrap required")))
      _ <- CredentialSql.check(ctx)
      s <- load
      _ <- lift(Either.cond(s.steward(dataset, ctx.principal), (), Problem.Denied))
      placement <- lift(s.placements.get(dataset).toRight(Problem.Denied))
      now <- OrganizationSql.now
    yield s.impact(placement.project, now)
  }

  def execute(ctx: AccessContext, request: OrganizationMutation): F[Either[Problem, OrganizationReceipt]] =
    db.requireOperator *> OperationsSql.run(xa)(executeIn(ctx, request))

  /** Scientific creation and initial ExplicitOnly stewardship share the policy transaction.
    * Stable request-derived dataset ID makes a response-loss retry resolve to the same record.
    */
  def createDataset(ctx: AccessContext, custodian: CustodianId, project: ProjectId,
      key: IdempotencyKey, expectedProject: OrganizationGeneration): F[Either[Problem, (Dataset, OrganizationReceipt)]] = db.requireOperator *> OperationsSql.run(xa) {
    val id = OrganizationMigration.id[DatasetTag]("new-dataset", ctx.principal.render, project.render, key.value)
    for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(true)
      _ <- lift(Either.cond(enabled, (), Problem.Conflict("organization bootstrap required")))
      _ <- CredentialSql.check(ctx)
      active <- sql"SELECT EXISTS(SELECT 1 FROM principal WHERE id=${uuid(ctx.principal)} AND active)".query[Boolean].unique
      _ <- lift(Either.cond(active, (), Problem.Denied))
      _ <- sql"""INSERT INTO dataset(id,custodian,curator) VALUES(${uuid(id)},${uuid(custodian)},${uuid(ctx.principal)})
        ON CONFLICT(id) DO NOTHING""".update.run
      row <- sql"SELECT custodian,curator,head FROM dataset WHERE id=${uuid(id)}".query[(UUID, UUID, Option[UUID])].unique
      _ <- lift(Either.cond(row._1 == uuid(custodian) && row._2 == uuid(ctx.principal), (), Problem.Conflict("creation key belongs to another intent")))
      request = OrganizationMutation(key, Map(OrganizationRef.Project(project) -> expectedProject),
        OrganizationCommand.PlaceNewDataset(id, project, OrganizationMigration.stewardship(id)))
      receipt <- executeIn(ctx, request)
    yield Dataset(id, custodian, ctx.principal, row._3.map(Id[ReleaseTag].apply)) -> receipt
  }

  private def executeIn(ctx: AccessContext, request: OrganizationMutation): ConnectionIO[OrganizationReceipt] = for
    _ <- OperationsSql.active
    enabled <- OrganizationSql.guard(true)
    _ <- lift(Either.cond(enabled, (), Problem.Conflict("organization bootstrap required")))
    _ <- CredentialSql.check(ctx)
    operator <- sql"SELECT bootstrap_operator FROM organization_policy WHERE singleton".query[UUID].unique
    before <- load
    now <- OrganizationSql.now
    outcome <- lift(ReferenceOrganizationCatalog.decide(before, Set(Id[PrincipalTag](operator)), ctx, request, now))
    (after, receipt) = outcome
    _ <- if after.sequence == before.sequence then ().pure[ConnectionIO] else for
      invalidations <- request.command match
        case OrganizationCommand.DisablePrincipal(person) =>
          sql"""SELECT jsonb_build_object('principal',${uuid(person)}::text,'revokedAt',${Timestamp.from(now)}::text,
            'credentials',COALESCE((SELECT jsonb_agg(id ORDER BY id) FROM api_credential
              WHERE principal_id=${uuid(person)} AND revoked_at IS NULL),'[]'::jsonb),
            'importLeases',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',id,'lease',lease_token,'until',lease_until) ORDER BY id)
              FROM import_operation WHERE owner=${uuid(person)} AND lease_token IS NOT NULL),'[]'::jsonb))::text"""
            .query[String].unique.flatMap(v => lift(parser.parse(v).leftMap(_ => Problem.Integrity("invalid invalidation inventory"))))
        case _ => Json.obj().pure[ConnectionIO]
      _ <- append(ctx.principal, ReferenceOrganizationCatalog.commandScope(before, request.command), request.key,
        Some(request), receipt, changes(before, after), invalidations) {
        persist(before, after) *> (request.command match
          case OrganizationCommand.DisablePrincipal(person) =>
            sql"""INSERT INTO credential_audit(credential_id,action)
              SELECT id,'Revoked' FROM api_credential WHERE principal_id=${uuid(person)} AND revoked_at IS NULL""".update.run *>
              sql"UPDATE api_credential SET revoked_at=${Timestamp.from(now)} WHERE principal_id=${uuid(person)} AND revoked_at IS NULL".update.run *>
              sql"UPDATE import_operation SET lease_token=NULL,lease_until=NULL WHERE owner=${uuid(person)}".update.run.void
          case _ => ().pure[ConnectionIO])
      }
    yield ()
    mode <- sql"SELECT durability_mode FROM organization_policy WHERE singleton".query[String].unique
  yield if mode == "independent" && after.sequence != before.sequence then receipt.copy(durability=PolicyDurability.Durable) else receipt

  /** Preview is operator-only, repeatable and contains hashes/mappings, never bearer secrets. */
  def preview(operator: PrincipalId): F[Json] = db.requireOperator *> (for
    _ <- sql"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ".update.run
    _ <- OperationsSql.active
    initialized <- OrganizationSql.guard(false)
    report <- if initialized then existingReport(operator) else migrationReport(operator)
  yield report).transact(xa)

  def bootstrap(operator: PrincipalId, expectedDigest: Digest): F[Json] = db.requireOperator *> (for
    _ <- OperationsSql.active
    initialized <- OrganizationSql.guard(true)
    result <- if initialized then existingReport(operator).flatMap(report =>
      lift(Either.cond(OrganizationSql.reportDigest(report) == expectedDigest, report, Problem.Conflict("bootstrap preview changed"))))
    else for
      // Old writers do not know the new row lock. The operator also stops/fences old binaries.
      _ <- Fragment.const("LOCK TABLE " + legacyTables.sorted.mkString(",") + " IN SHARE MODE").update.run
      report <- migrationReport(operator)
      _ <- lift(Either.cond(OrganizationSql.reportDigest(report) == expectedDigest, (), Problem.Conflict("bootstrap preview changed")))
      people <- legacyPrincipals(operator)
      _ <- people.traverse_(p => sql"INSERT INTO principal(id) VALUES($p) ON CONFLICT DO NOTHING".update.run)
      before <- load
      now <- OrganizationSql.now
      migrated <- lift(OrganizationMigration.transform(before.principals.values.toVector, before.knownDatasets.values.toVector, operator, now))
      _ <- persist(before, migrated)
      // Existing action/scope/revocation columns remain untouched; absence of an original issuer stays unknown.
      _ <- sql"""UPDATE access_grant g SET issuer=(SELECT a.actor FROM policy_audit a WHERE a.grant_id=g.id
        AND a.action='Granted' ORDER BY a.sequence LIMIT 1) WHERE g.issuer IS NULL""".update.run
      text = report.noSpaces
      _ <- sql"""UPDATE organization_policy SET initialized=TRUE,bootstrap_operator=${uuid(operator)},
        migration_digest=${expectedDigest.value},migration_report=$text::jsonb WHERE singleton""".update.run
      key <- lift(IdempotencyKey.from("organization-genesis-v1"))
      receipt = OrganizationReceipt(0, operator, key, migrated.references, now)
      grants <- sql"SELECT COALESCE(jsonb_agg(to_jsonb(g) ORDER BY id),'[]'::jsonb)::text FROM access_grant g".query[String].unique
      credentials <- sql"""SELECT COALESCE(jsonb_agg(jsonb_build_object('id',id,'principal',principal_id,
        'expiresAt',expires_at,'revokedAt',revoked_at) ORDER BY id),'[]'::jsonb)::text FROM api_credential""".query[String].unique
      genesis = Json.obj("migration" -> report, "principals" -> migrated.principals.values.toVector.sortBy(_.id.render).asJson,
        "datasets" -> migrated.knownDatasets.values.toVector.sortBy(_.id.render).map(d => Json.obj(
          "id" -> d.id.asJson,"custodian" -> d.custodian.asJson,"attributedCurator" -> d.curator.asJson)).asJson,
        "grants" -> parser.parse(grants).toOption.get,
        "credentials" -> parser.parse(credentials).toOption.get,
        "changes" -> changes(before, migrated))
      _ <- append(operator, "installation", key, None, receipt, genesis)(().pure[ConnectionIO])
    yield report
  yield result).transact(xa)

  private def existingReport(operator: PrincipalId): ConnectionIO[Json] = for
    row <- sql"SELECT bootstrap_operator,migration_report::text FROM organization_policy WHERE singleton".query[(UUID, String)].unique
    _ <- lift(Either.cond(row._1 == uuid(operator), (), Problem.Conflict("installation already bootstrapped by another operator")))
    json <- lift(parser.parse(row._2).leftMap(_ => Problem.Integrity("invalid migration report")))
  yield json

  private val legacyTables = Vector("principal", "dataset", "access_grant", "policy_audit", "api_credential", "credential_audit",
    "release", "candidate", "manifest", "interpretation", "assessment", "acceptance_receipt", "audit", "outbox",
    "import_operation", "acquired_file", "processing_run", "frozen_selection")

  private def legacyPrincipals(operator: PrincipalId): ConnectionIO[Vector[UUID]] =
    sql"""SELECT id FROM principal UNION SELECT curator FROM dataset UNION SELECT accepted_by FROM release
      UNION SELECT actor FROM audit UNION SELECT actor FROM policy_audit UNION SELECT recipient FROM access_grant
      UNION SELECT principal_id FROM api_credential UNION SELECT owner FROM import_operation
      UNION SELECT owner FROM frozen_selection UNION SELECT owner FROM processing_run UNION SELECT (activity #>> '{value,actor}')::uuid FROM candidate
      UNION SELECT ${uuid(operator)}""".query[UUID].to[Vector].map(_.sortBy(_.toString))

  private def migrationReport(operator: PrincipalId): ConnectionIO[Json] = for
    counts <- legacyTables.traverse { table =>
      Fragment.const(s"SELECT count(*),COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM $table t")
        .query[(Long, String)].unique.map { (count, json) =>
          table -> Json.obj("count" -> Json.fromLong(count), "sha256" -> Json.fromString(Digest.bytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).value))
        }
    }
    data <- datasets
    people <- legacyPrincipals(operator)
    occupied <- sql"SELECT EXISTS(SELECT 1 FROM organization_lab) OR EXISTS(SELECT 1 FROM organization_event)".query[Boolean].unique
    _ <- lift(Either.cond(!occupied, (), Problem.Integrity("partial organization bootstrap exists")))
  yield Json.obj("version" -> Json.fromInt(1), "operator" -> operator.asJson, "lab" -> OrganizationMigration.lab(operator).asJson,
    "principals" -> Json.arr(people.map(p => Json.fromString(p.toString))*), "sources" -> Json.obj(counts*),
    "datasets" -> Json.arr(data.sortBy(_.id.render).map(d => Json.obj("dataset" -> d.id.asJson, "curator" -> d.curator.asJson,
      "custodian" -> d.custodian.asJson, "project" -> OrganizationMigration.project(d).asJson,
      "stewardship" -> OrganizationMigration.stewardship(d.id).asJson, "policy" -> Json.fromString("ExplicitOnly")))*))

  private def datasets: ConnectionIO[Vector[Dataset]] = sql"SELECT id,custodian,curator,head FROM dataset"
    .query[(UUID, UUID, UUID, Option[UUID])].to[Vector].map(_.map((d,c,p,h) => Dataset(Id[DatasetTag](d), Id[CustodianTag](c), Id[PrincipalTag](p), h.map(Id[ReleaseTag].apply))))

  private def rows[A: Decoder](table: String): ConnectionIO[Vector[A]] =
    Fragment.const(s"SELECT payload::text FROM organization_$table").query[String].to[Vector]
      .flatMap(_.traverse(p => lift(JsonCodecs.decode[A](p))))

  private def load: ConnectionIO[Snapshot] = for
    people <- sql"SELECT id,label,active,generation,created_at FROM principal".query[(UUID,String,Boolean,Long,Timestamp)].to[Vector]
      .flatMap(_.traverse { (id,label,active,generation,at) => lift(for
        name <- OrganizationName.from(label)
        gen <- OrganizationGeneration.from(generation)
      yield OrganizationPrincipal(Id[PrincipalTag](id),name,active,gen,at.toInstant)) })
    data <- datasets
    labs <- rows[Lab]("lab")
    projects <- rows[Project]("project")
    members <- rows[LabMembership]("membership")
    participants <- rows[ProjectParticipation]("participation")
    placements <- rows[DatasetPlacement]("placement")
    stewards <- rows[DatasetStewardship]("stewardship")
    links <- rows[ProjectDatasetLink]("link")
    revision <- sql"SELECT revision FROM organization_policy WHERE singleton".query[Long].unique
    events <- sql"""SELECT e.scope,e.request::text,e.receipt::text,
        CASE WHEN a.event_id IS NOT NULL AND e.durability='Pending' THEN 'Durable' ELSE e.durability END
        FROM organization_event e LEFT JOIN policy_acknowledgement a ON a.event_id=e.id
        WHERE e.request IS NOT NULL ORDER BY e.revision"""
      .query[(String,String,String,String)].to[Vector].flatMap(_.traverse { (scope,request,receipt,durability) => for
        m <- lift(JsonCodecs.decode[OrganizationMutation](request))
        r <- lift(JsonCodecs.decode[OrganizationReceipt](receipt))
      yield (scope,m,r.copy(durability=PolicyDurability.valueOf(durability))) })
    imports <- sql"""SELECT p.project_id,o.lease_token FROM import_operation o JOIN organization_placement p ON p.dataset_id=o.dataset_id
      WHERE o.receipt IS NULL AND o.lease_until>clock_timestamp()""".query[(UUID,UUID)].to[Vector]
    runs <- sql"SELECT payload::text FROM processing_run".query[String].to[Vector]
      .flatMap(_.traverse(p => lift(JsonCodecs.decode[ProcessingRun](p))))
    runLeases = runs.filter(r => Set(RunStatus.Planned,RunStatus.Preparing,RunStatus.Executing)(r.status)).flatMap(r =>
      placements.filter(p => p.dataset == r.spec.destination || p.dataset == r.spec.input.dataset)
        .map(p => p.project -> Id[LeaseTag](uuid(r.id))))
    work = (imports.map((p,l) => Id[ProjectTag](p) -> Id[LeaseTag](l)) ++ runLeases).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
  yield Snapshot(people.map(p => p.id -> p).toMap, data.map(d => d.id -> d).toMap, labs.map(l => l.id -> l).toMap,
    projects.map(p => p.id -> p).toMap, members.map(m => m.id -> m).toMap, participants.map(p => p.id -> p).toMap,
    placements.map(p => p.dataset -> p).toMap, stewards.map(s => s.id -> s).toMap, links.map(l => l.id -> l).toMap,
    work, revision, events.map((_,m,r) => OrganizationAudit(r,m.command)), events.map((scope,m,r) => (r.actor,scope,r.key) -> (m,r)).toMap)

  private def write[K,A: Encoder](table: String, key: String, before: Map[K,A], after: Map[K,A]): ConnectionIO[Unit] =
    after.toVector.filter((id,value) => !before.get(id).contains(value)).traverse_ { (_,value) =>
      val text = JsonCodecs.encode(value)
      (Fragment.const(s"INSERT INTO organization_$table(payload) VALUES(") ++ fr"$text::jsonb" ++
        Fragment.const(s") ON CONFLICT($key) DO UPDATE SET payload=EXCLUDED.payload")).update.run.void
    }

  private def persist(before: Snapshot, after: Snapshot): ConnectionIO[Unit] =
    after.principals.toVector.filter((id,value) => !before.principals.get(id).contains(value)).traverse_ { (_,p) =>
      sql"""UPDATE principal SET label=${p.label.value},active=${p.active},generation=${p.generation.value},created_at=${Timestamp.from(p.createdAt)}
        WHERE id=${uuid(p.id)}""".update.run.void
    } *> write("lab","id",before.labs,after.labs) *> write("project","id",before.projects,after.projects) *>
      write("membership","id",before.memberships,after.memberships) *> write("participation","id",before.participations,after.participations) *>
      write("placement","dataset_id",before.placements,after.placements) *> write("stewardship","id",before.stewardships,after.stewardships) *>
      write("link","id",before.links,after.links)

  private def changes(before: Snapshot, after: Snapshot): Json =
    def diff[K,A: Encoder](kind: String, a: Map[K,A], b: Map[K,A]): Vector[Json] = b.toVector.filter((id,v) => !a.get(id).contains(v))
      .map((id,v) => Json.obj("kind" -> Json.fromString(kind), "before" -> a.get(id).asJson, "after" -> v.asJson))
    Json.arr((diff("principal",before.principals,after.principals) ++ diff("lab",before.labs,after.labs) ++
      diff("project",before.projects,after.projects) ++ diff("membership",before.memberships,after.memberships) ++
      diff("participation",before.participations,after.participations) ++ diff("placement",before.placements,after.placements) ++
      diff("stewardship",before.stewardships,after.stewardships) ++ diff("link",before.links,after.links)).sortBy(_.noSpaces)*)

  private def append(actor: PrincipalId, scope: String, key: IdempotencyKey, request: Option[OrganizationMutation],
      receipt: OrganizationReceipt, changes: Json, invalidations: Json = Json.obj())(effects: ConnectionIO[Unit]): ConnectionIO[Unit] = for
    creation <- request.toVector.collect { case OrganizationMutation(_,_,OrganizationCommand.PlaceNewDataset(d,_,_)) => d }
      .traverse(d => sql"SELECT custodian,curator FROM dataset WHERE id=${uuid(d)}".query[(UUID,UUID)].unique.map((c,p) =>
        Json.obj("id" -> d.asJson,"custodian" -> Json.fromString(c.toString),"attributedCurator" -> Json.fromString(p.toString))))
    _ <- PolicySql.commit(actor,scope,key.value,request.fold("OrganizationGenesis")(_.command.productPrefix),changes,
      request.map(JsonCodecs.encode(_)),Some((receipt.sequence,receipt.at,JsonCodecs.encode(receipt))),
      Json.obj("createdDatasets" -> creation.asJson,"invalidations" -> invalidations))(effects)
  yield ()

object PostgresOrganizationCatalog:
  def previewDigest(report: Json): Digest = OrganizationSql.reportDigest(report)

private[postgres] object OrganizationSql:
  def guard(write: Boolean): ConnectionIO[Boolean] =
    (fr"SELECT initialized FROM organization_policy WHERE singleton" ++ (if write then fr"FOR UPDATE" else fr"FOR SHARE"))
      .query[Boolean].unique.flatTap(_ => doobie.free.connection.raw(c => PolicyBridge.admit(c,write)))
  def now: ConnectionIO[Instant] = sql"SELECT clock_timestamp()".query[Timestamp].unique.map(_.toInstant)
  def reportDigest(report: Json): Digest = Digest.bytes(report.printWith(Printer.noSpaces.copy(sortKeys=true)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
