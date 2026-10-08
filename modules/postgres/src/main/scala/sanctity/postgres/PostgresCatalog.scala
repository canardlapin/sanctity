package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment, Transactor}
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import sanctity.application.{Catalog, Checked, Failure, IdSource}
import sanctity.kernel.*

import java.sql.{SQLException, Timestamp}
import java.util.UUID

/** Complete catalog transactions; no filesystem, converter, or remote calls occur inside them. */
final class PostgresCatalog[F[_]] private[postgres] (
    xa: Transactor[F], ids: IdSource[F]
)(using F: Async[F]) extends Catalog[F]:
  import PostgresCatalog.*
  import Records.{lift, uuid}
  import JsonCodecs.given

  private def run[A](program: ConnectionIO[A]): F[Either[Problem, A]] =
    program.transact(xa).attempt.map(_.leftMap {
      case Failure(problem) => problem
      case error: SQLException => Problem.Operational(s"catalog transaction failed (SQLSTATE ${Option(error.getSQLState).getOrElse("unknown")})")
      case _ => Problem.Operational("catalog operation failed")
    })

  def check(ctx: AccessContext, dataset: DatasetId, action: Action, release: Option[ReleaseId] = None): F[Either[Problem, Unit]] =
    run(authorize(ctx, uuid(dataset), false, action, release).void)

  def grant(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId], actions: Set[Action]): F[Either[Problem, Grant]] =
    ids.next[GrantTag].flatMap { id => run {
      for
        _ <- authorize(ctx, uuid(dataset), true, Action.ManageAccess)
        _ <- lift(Authority.validate(release, actions))
        _ <- release.traverse_ { r =>
          sql"SELECT EXISTS(SELECT 1 FROM release WHERE id=${uuid(r)} AND dataset_id=${uuid(dataset)})".query[Boolean].unique
            .flatMap(found => lift(Either.cond(found, (), Problem.Denied)))
        }
        _ <- sql"INSERT INTO principal(id) VALUES (${uuid(recipient)}) ON CONFLICT DO NOTHING".update.run
        _ <- sql"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions)
          VALUES (${uuid(id)},${uuid(dataset)},${uuid(recipient)},${release.map(uuid)},${actions.toList.map(_.toString).sorted})""".update.run
        _ <- sql"""INSERT INTO policy_audit(dataset_id,actor,grant_id,action) VALUES
          (${uuid(dataset)},${uuid(ctx.principal)},${uuid(id)},'Granted')""".update.run
      yield Grant(id, dataset, recipient, release, actions)
    }}

  def revoke(ctx: AccessContext, dataset: DatasetId, grant: GrantId): F[Either[Problem, Unit]] = run {
    for
      _ <- authorize(ctx, uuid(dataset), true, Action.ManageAccess)
      changed <- sql"""UPDATE access_grant SET revoked_at=COALESCE(revoked_at,clock_timestamp())
        WHERE id=${uuid(grant)} AND dataset_id=${uuid(dataset)}""".update.run
      _ <- lift(Either.cond(changed == 1, (), Problem.Denied))
      _ <- sql"""INSERT INTO policy_audit(dataset_id,actor,grant_id,action) VALUES
        (${uuid(dataset)},${uuid(ctx.principal)},${uuid(grant)},'Revoked')""".update.run
    yield ()
  }

  def select(ctx: AccessContext, refs: Vector[ReleaseRef], query: ScientificQuery): F[Either[Problem, FrozenSelection]] = run {
    for
      _ <- lift(FrozenSelection.from(refs, query, Vector.empty))
      hits <- refs.sortBy(r => (r.dataset.render, r.release.render)).traverse { ref =>
        for
          _ <- authorize(ctx, uuid(ref.dataset), false, Action.ReadMetadata, Some(ref.release))
          id <- sql"""SELECT c.interpretation_id FROM release r JOIN candidate c ON c.id=r.candidate_id
            WHERE r.dataset_id=${uuid(ref.dataset)} AND r.id=${uuid(ref.release)}""".query[String].option
            .flatMap(v => lift(v.toRight(Problem.Denied)))
          interpretation <- Records.loadInterpretation(id)
          _ <- ScientificSql.ready(interpretation)
          hits <- ScientificSql.select(ref, interpretation, query)
        yield hits
      }
      selection <- lift(FrozenSelection.from(refs, query, hits.flatten))
      payload = JsonCodecs.encode(selection)
      _ <- sql"""INSERT INTO frozen_selection(id,owner,payload) VALUES
        (${selection.id.value.value},${uuid(ctx.principal)},$payload::jsonb) ON CONFLICT DO NOTHING""".update.run
    yield selection
  }

  def selection(ctx: AccessContext, id: SelectionId): F[Either[Problem, FrozenSelection]] = run {
    for
      payload <- sql"SELECT payload::text FROM frozen_selection WHERE id=${id.value.value} AND owner=${uuid(ctx.principal)}"
        .query[String].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      result <- lift(JsonCodecs.decode[FrozenSelection](payload))
      _ <- Records.require(result.id == id, "selection identity mismatch")
      _ <- result.releases.traverse_(r => authorize(ctx, uuid(r.dataset), false, Action.ReadMetadata, Some(r.release)))
    yield result
  }

  def create(ctx: AccessContext, custodian: CustodianId): F[Dataset] =
    ids.next[DatasetTag].flatMap { id =>
      val dataset = Dataset(id, custodian, ctx.principal, None)
      run((OperationsSql.active *> CredentialSql.check(ctx) *> sql"INSERT INTO principal(id) VALUES (${uuid(ctx.principal)}) ON CONFLICT DO NOTHING".update.run *> sql"INSERT INTO dataset (id,custodian,curator) VALUES (${uuid(id)},${uuid(custodian)},${uuid(ctx.principal)})"
        .update.run).as(dataset)).flatMap(Checked.lift[F, Dataset])
    }

  def dataset(ctx: AccessContext, id: DatasetId): F[Either[Problem, Dataset]] = run(authorize(ctx, uuid(id), false))

  def draft(ctx: AccessContext, dataset: DatasetId, base: Option[ReleaseId], manifest: Manifest, activity: Activity): F[Either[Problem, Draft]] =
    ids.next[DraftTag].flatMap { id =>
      run {
        for
          _ <- authorize(ctx, uuid(dataset), true, Action.Propose)
          _ <- lift(Either.cond(activity.actor == ctx.principal, (), Problem.Denied))
          _ <- base.traverse_ { release =>
            sql"SELECT EXISTS (SELECT 1 FROM release WHERE dataset_id=${uuid(dataset)} AND id=${uuid(release)})".query[Boolean].unique
              .flatMap(found => lift(Either.cond(found, (), Problem.NotFound("base release", release.render))))
          }
          _ <- Records.storeManifest(manifest)
          _ <- Records.storeEvidence(activity)
          payload = JsonCodecs.encode(activity)
          _ <- sql"""INSERT INTO draft (id,dataset_id,base,generation,manifest_id,activity)
            VALUES (${uuid(id)},${uuid(dataset)},${base.map(uuid)},0,${manifest.id.value.value},$payload::jsonb)""".update.run
        yield Draft(id, dataset, base, 0L, manifest, activity)
      }
    }

  def readDraft(ctx: AccessContext, id: DraftId): F[Either[Problem, Draft]] =
    run(authorizedDraft(ctx, uuid(id), false).map(_._1))

  def revise(ctx: AccessContext, id: DraftId, generation: Long, manifest: Manifest, activity: Activity): F[Either[Problem, Draft]] =
    run {
      for
        loaded <- authorizedDraft(ctx, uuid(id), true)
        (current, accepted) = loaded
        _ <- lift(Either.cond(!accepted, (), Problem.Conflict("draft already accepted")))
        _ <- lift(Either.cond(current.generation == generation, (), Problem.Conflict("stale draft generation")))
        _ <- lift(Either.cond(activity.actor == ctx.principal, (), Problem.Denied))
        _ <- lift(Either.cond(generation < Long.MaxValue, (), Problem.Conflict("draft generation exhausted")))
        _ <- Records.storeManifest(manifest)
        _ <- Records.storeEvidence(activity)
        payload = JsonCodecs.encode(activity)
        next = generation + 1
        _ <- sql"""UPDATE draft SET generation=$next,manifest_id=${manifest.id.value.value},activity=$payload::jsonb
          WHERE id=${uuid(id)}""".update.run
      yield current.copy(generation = next, manifest = manifest, activity = activity)
    }

  def prepared(ctx: AccessContext, id: DraftId, generation: Long, interpretation: Interpretation, assessment: Assessment): F[Either[Problem, Candidate]] =
    ids.next[CandidateTag].flatMap { candidateId =>
      run {
        for
          loaded <- authorizedDraft(ctx, uuid(id), true)
          (draft, accepted) = loaded
          _ <- lift(Admission.prepared(draft, generation, accepted, interpretation, assessment))
          _ <- Records.storeInterpretation(interpretation)
          _ <- Records.storeAssessment(assessment)
          _ <- Records.indexesReady(draft.manifest, interpretation)
          payload = JsonCodecs.encode(draft.activity)
          _ <- sql"""INSERT INTO candidate (id,draft_id,dataset_id,base,generation,manifest_id,interpretation_id,assessment_id,activity)
            VALUES (${uuid(candidateId)},${uuid(id)},${uuid(draft.dataset)},${draft.base.map(uuid)},$generation,
              ${draft.manifest.id.value.value},${interpretation.id.value.value},${assessment.id.value.value},$payload::jsonb)""".update.run
        yield Candidate(candidateId, id, draft.dataset, draft.base, generation, draft.manifest, interpretation, assessment, draft.activity)
      }
    }

  def candidate(ctx: AccessContext, id: CandidateId): F[Either[Problem, Candidate]] =
    run(candidateScope(ctx, uuid(id), false, Action.Propose, allowAccept = true).flatMap(pair => hydrateCandidate(pair._2)))

  def receipt(ctx: AccessContext, id: CandidateId, key: IdempotencyKey): F[Either[Problem, Option[AcceptanceReceipt]]] =
    run(candidateScope(ctx, uuid(id), false, Action.Accept).flatMap { (_, row) =>
      lift(JsonCodecs.decode[Activity](row.activity)).flatMap(_.projection.traverse_(p =>
        authorize(ctx, uuid(p.source.dataset), false, Action.ManageAccess))) *> priorReceipt(ctx, row, key)
    })

  def accept(ctx: AccessContext, id: CandidateId, key: IdempotencyKey, lease: Option[WorkLease] = None): F[Either[Problem, AcceptanceReceipt]] =
    ids.next[ReleaseTag].flatMap { releaseId =>
      run {
        for
          initial <- fetchCandidate(uuid(id))
          activity <- lift(JsonCodecs.decode[Activity](initial.activity))
          locks = (Vector(Id[DatasetTag](initial.dataset) -> Action.Accept) ++ activity.projection.toVector.map(p => p.source.dataset -> Action.ManageAccess)).sortBy(_._1.render)
          _ <- locks.traverse_((dataset, action) => authorize(ctx, uuid(dataset), true, action))
          scoped <- candidateScope(ctx, uuid(id), true, Action.Accept)
          (dataset, row) = scoped
          prior <- priorReceipt(ctx, row, key)
          receipt <- prior match
            case Some(receipt) => receipt.pure[ConnectionIO]
            case None =>
              for
                _ <- OperationsSql.fenceCandidate(row.id, lease)
                candidate <- hydrateCandidate(row)
                draftRow <- fetchDraft(row.draft)
                draft <- hydrateDraft(draftRow)
                _ <- lift(Admission.accept(dataset, draft, candidate, draftRow.accepted.nonEmpty))
                _ <- Records.indexesReady(candidate.manifest, candidate.interpretation)
                timestamp <- sql"SELECT clock_timestamp()".query[Timestamp].unique
                at = timestamp.toInstant
                _ <- sql"""INSERT INTO release (id,dataset_id,parent,draft_id,candidate_id,accepted_by,accepted_at)
                  VALUES (${uuid(releaseId)},${row.dataset},${row.base},${row.draft},${row.id},${uuid(ctx.principal)},$timestamp)""".update.run
                _ <- sql"UPDATE dataset SET head=${uuid(releaseId)} WHERE id=${row.dataset}".update.run
                _ <- sql"UPDATE draft SET accepted_release=${uuid(releaseId)} WHERE id=${row.draft}".update.run
                _ <- sql"""INSERT INTO acceptance_receipt (dataset_id,principal,idempotency_key,candidate_id,release_id)
                  VALUES (${row.dataset},${uuid(ctx.principal)},${key.value},${row.id},${uuid(releaseId)})""".update.run
                _ <- sql"""INSERT INTO audit (dataset_id,release_id,actor,action,recorded_at)
                  VALUES (${row.dataset},${uuid(releaseId)},${uuid(ctx.principal)},'ReleaseAccepted',$timestamp)""".update.run
                event = Json.obj(
                  "version" -> Json.fromInt(1),
                  "dataset" -> Json.fromString(candidate.dataset.render),
                  "release" -> Json.fromString(releaseId.render),
                  "candidate" -> Json.fromString(id.render),
                  "generation" -> Json.fromLong(candidate.generation),
                  "manifest" -> Json.fromString(candidate.manifest.id.value.value),
                  "interpretation" -> Json.fromString(candidate.interpretation.id.value.value),
                  "assessment" -> Json.fromString(candidate.assessment.id.value.value)
                ).noSpaces
                _ <- sql"""INSERT INTO outbox (dataset_id,release_id,event_type,payload,recorded_at)
                  VALUES (${row.dataset},${uuid(releaseId)},'ReleaseAccepted',$event::jsonb,$timestamp)""".update.run
              yield AcceptanceReceipt(ReleaseRef(candidate.dataset, releaseId), id, key, at)
        yield receipt
      }
    }

  def release(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]] = run {
    for
      _ <- authorize(ctx, uuid(ref.dataset), false, Action.ReadMetadata, Some(ref.release))
      row <- sql"""SELECT parent,candidate_id,accepted_by,accepted_at FROM release
        WHERE dataset_id=${uuid(ref.dataset)} AND id=${uuid(ref.release)}""".query[(Option[UUID], UUID, UUID, Timestamp)].option
        .flatMap(value => lift(value.toRight(Problem.NotFound("release", ref.release.render))))
      (parent, candidateId, acceptedBy, acceptedAt) = row
      candidate <- fetchCandidate(candidateId).flatMap(hydrateCandidate)
      _ <- Records.require(candidate.base == parent.map(Id[ReleaseTag].apply), "release parent differs from accepted candidate")
    yield Release(ref, candidate.base, candidate.manifest, candidate.interpretation, candidate.assessment,
      candidate.activity, Id[PrincipalTag](acceptedBy), acceptedAt.toInstant)
  }

  def metadata(ctx: AccessContext, ref: ReleaseRef, path: RelPath): F[Either[Problem, ArtifactMetadata]] = run {
    for
      _ <- authorize(ctx, uuid(ref.dataset), false, Action.ReadMetadata, Some(ref.release))
      interpretation <- sql"""SELECT c.interpretation_id FROM release r JOIN candidate c ON c.id=r.candidate_id
        WHERE r.dataset_id=${uuid(ref.dataset)} AND r.id=${uuid(ref.release)}""".query[String].option
        .flatMap(value => lift(value.toRight(Problem.NotFound("release", ref.release.render))))
      metadata <- Records.metadata(interpretation, path)
    yield metadata
  }

  private[postgres] def authorize(ctx: AccessContext, id: UUID, write: Boolean,
      action: Action = Action.ReadMetadata, release: Option[ReleaseId] = None,
      allowAccept: Boolean = false): ConnectionIO[Dataset] =
    val lock = if write then Fragment.const("FOR UPDATE") else Fragment.const("FOR SHARE")
    for
      _ <- OperationsSql.active
      _ <- CredentialSql.check(ctx)
      row <- (fr"SELECT id,custodian,curator,head FROM dataset WHERE id=$id" ++ lock)
        .query[(UUID, UUID, UUID, Option[UUID])].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      (datasetId, custodian, curator, head) = row
      dataset = Dataset(Id[DatasetTag](datasetId), Id[CustodianTag](custodian), Id[PrincipalTag](curator), head.map(Id[ReleaseTag].apply))
      grants <- sql"""SELECT id,recipient,release_id,actions FROM access_grant
        WHERE dataset_id=$id AND recipient=${uuid(ctx.principal)} AND revoked_at IS NULL"""
        .query[(UUID, UUID, Option[UUID], List[String])].to[Vector]
      decoded <- grants.traverse { (grant, recipient, fixed, actions) =>
        actions.traverse(a => lift(Action.values.find(_.toString == a).toRight(Problem.Integrity("unknown grant action"))))
          .map(as => Grant(Id[GrantTag](grant), dataset.id, Id[PrincipalTag](recipient), fixed.map(Id[ReleaseTag].apply), as.toSet))
      }
      _ <- lift(Either.cond(Authority.permits(dataset, decoded, ctx, action, release) ||
        (allowAccept && Authority.permits(dataset, decoded, ctx, Action.Accept, release)), (), Problem.Denied))
    yield dataset

  private def authorizedDraft(ctx: AccessContext, id: UUID, write: Boolean): ConnectionIO[(Draft, Boolean)] =
    for
      dataset <- sql"SELECT dataset_id FROM draft WHERE id=$id".query[UUID].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      _ <- authorize(ctx, dataset, write, Action.Propose)
      row <- fetchDraft(id)
      draft <- hydrateDraft(row)
    yield draft -> row.accepted.nonEmpty

  private def candidateScope(ctx: AccessContext, id: UUID, write: Boolean, action: Action, allowAccept: Boolean = false): ConnectionIO[(Dataset, CandidateRow)] =
    for
      datasetId <- sql"SELECT dataset_id FROM candidate WHERE id=$id".query[UUID].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      dataset <- authorize(ctx, datasetId, write, action, allowAccept = allowAccept)
      row <- fetchCandidate(id)
    yield dataset -> row

  private def fetchDraft(id: UUID): ConnectionIO[DraftRow] =
    sql"SELECT id,dataset_id,base,generation,manifest_id,activity::text,accepted_release FROM draft WHERE id=$id"
      .query[DraftRow].option.flatMap(v => lift(v.toRight(Problem.Denied)))

  private def fetchCandidate(id: UUID): ConnectionIO[CandidateRow] =
    sql"SELECT id,draft_id,dataset_id,base,generation,manifest_id,interpretation_id,assessment_id,activity::text FROM candidate WHERE id=$id"
      .query[CandidateRow].option.flatMap(v => lift(v.toRight(Problem.Denied)))

  private def hydrateDraft(row: DraftRow): ConnectionIO[Draft] =
    for
      manifest <- Records.loadManifest(row.manifest)
      activity <- lift(JsonCodecs.decode[Activity](row.activity))
    yield Draft(Id[DraftTag](row.id), Id[DatasetTag](row.dataset), row.base.map(Id[ReleaseTag].apply), row.generation, manifest, activity)

  private def hydrateCandidate(row: CandidateRow): ConnectionIO[Candidate] =
    for
      manifest <- Records.loadManifest(row.manifest)
      interpretation <- Records.loadInterpretation(row.interpretation)
      assessment <- Records.loadAssessment(row.assessment, interpretation)
      activity <- lift(JsonCodecs.decode[Activity](row.activity))
      _ <- Records.require(manifest.id == interpretation.content && assessment.content == manifest.id, "candidate evidence binding mismatch")
    yield Candidate(Id[CandidateTag](row.id), Id[DraftTag](row.draft), Id[DatasetTag](row.dataset),
      row.base.map(Id[ReleaseTag].apply), row.generation, manifest, interpretation, assessment, activity)

  private def priorReceipt(ctx: AccessContext, row: CandidateRow, key: IdempotencyKey): ConnectionIO[Option[AcceptanceReceipt]] =
    sql"""SELECT a.candidate_id,a.release_id,r.accepted_at FROM acceptance_receipt a
      JOIN release r ON r.id=a.release_id WHERE a.dataset_id=${row.dataset}
      AND a.principal=${uuid(ctx.principal)} AND a.idempotency_key=${key.value}"""
      .query[(UUID, UUID, Timestamp)].option.flatMap {
        case Some((candidate, _, _)) if candidate != row.id => lift(Left(Problem.Conflict("idempotency key was used for another candidate")))
        case value => value.map { (_, release, at) =>
          AcceptanceReceipt(ReleaseRef(Id[DatasetTag](row.dataset), Id[ReleaseTag](release)), Id[CandidateTag](row.id), key, at.toInstant)
        }.pure[ConnectionIO]
      }

private object PostgresCatalog:
  final case class DraftRow(id: UUID, dataset: UUID, base: Option[UUID], generation: Long, manifest: String, activity: String, accepted: Option[UUID])
  final case class CandidateRow(id: UUID, draft: UUID, dataset: UUID, base: Option[UUID], generation: Long,
      manifest: String, interpretation: String, assessment: String, activity: String)
