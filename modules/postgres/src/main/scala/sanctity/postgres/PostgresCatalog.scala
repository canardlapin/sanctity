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

  /** Stable grant IDs bind retries to the entire intent. Legacy callers still receive their
    * unchanged action set; discovery/evidence are never inferred during migration.
    */
  def grantPolicy(ctx: AccessContext, grant: OrganizationAuthority.PolicyGrant): F[Either[Problem, OrganizationAuthority.PolicyGrant]] =
    run(for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(true)
      _ <- lift(Either.cond(enabled, (), Problem.Conflict("organization bootstrap required")))
      result <- putGrant(ctx,grant)
    yield result)

  def grant(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId], actions: Set[Action]): F[Either[Problem, Grant]] =
    ids.next[GrantTag].flatMap { id => run {
      for
        _ <- lift(Authority.validate(release,actions))
        now <- OrganizationSql.now
        legacy = Grant(id,dataset,recipient,release,actions)
        _ <- putGrant(ctx, OrganizationAuthority.PolicyGrant.legacy(legacy,now,OrganizationGeneration.initial).copy(issuer=Some(ctx.principal)))
      yield legacy
    }}

  def grantShare(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId],
      actions: Set[OrganizationAuthority.Permission], expiresAt: Option[java.time.Instant]): F[Either[Problem, OrganizationAuthority.PolicyGrant]] =
    import java.time.temporal.ChronoUnit.MICROS
    ids.next[GrantTag].flatMap { id => run(for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(true)
      _ <- lift(Either.cond(enabled, (), Problem.Unsupported("shares with discovery or expiry require an initialized organization")))
      now <- OrganizationSql.now
      share = OrganizationAuthority.PolicyGrant(id, Some(ctx.principal), recipient, OrganizationAuthority.DataScope(dataset, release),
        actions, now.truncatedTo(MICROS), expiresAt.map(_.truncatedTo(MICROS)), OrganizationAuthority.GrantBasis.Independent, OrganizationGeneration.initial)
      issued <- putGrant(ctx, share)
    yield issued) }

  private def putGrant(ctx: AccessContext, grant: OrganizationAuthority.PolicyGrant): ConnectionIO[OrganizationAuthority.PolicyGrant] =
    import OrganizationAuthority.*
    import OrganizationCodecs.given
    import io.circe.syntax.*
    for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(true)
      _ <- CredentialSql.check(ctx)
      _ <- lift(Either.cond(grant.createdAt.getNano % 1000 == 0 && grant.expiresAt.forall(_.getNano % 1000 == 0), (),
        Problem.Unsupported("grant timestamps require microsecond precision")))
      _ <- lift(Either.cond(grant.generation == OrganizationGeneration.initial && grant.issuer.contains(ctx.principal) && !grant.revoked && validGrantShape(grant), (), Problem.Denied))
      _ <- if enabled then ().pure[ConnectionIO] else authorize(ctx,uuid(grant.scope.dataset),true,Action.ManageAccess).void
      state <- PolicySql.load
      now <- OrganizationSql.now
      // Legacy API admits a named local principal, not a verified institutional identity.
      person = OrganizationPrincipal(grant.recipient,OrganizationName.from("Local principal").toOption.get,true,OrganizationGeneration.initial,now)
      created = Option.when(!state.principals.contains(grant.recipient))(person)
      proposed = created.fold(state)(p => state.copy(principals=state.principals.updated(p.id,p),eligibility=state.eligibility.updated(p.id,Eligibility.Local)))
      _ <- if enabled then lift(Either.cond(OrganizationAuthority.permits(proposed,PolicyAdmission(ctx.principal,true,None),Request.IssueGrant(grant),now), (), Problem.Denied)) else ().pure[ConnectionIO]
      existing = state.grants.get(grant.id)
      _ <- lift(Either.cond(existing.forall(_ == grant), (), Problem.Conflict("grant ID belongs to another intent or was revoked")))
      _ <- if existing.nonEmpty then ().pure[ConnectionIO] else
        PolicySql.commit(ctx.principal,"grant:"+grant.id.render,grant.id.render,"GrantIssued",
          Json.obj("principalCreated" -> created.asJson,"before" -> Json.Null,"after" -> grant.asJson)) {
          for
            _ <- created.traverse_(p => sql"""INSERT INTO principal(id,label,created_at) VALUES
              (${uuid(p.id)},${p.label.value},${Timestamp.from(p.createdAt)})""".update.run)
            basis = grant.basis match { case GrantBasis.Independent => "Independent"; case _ => "Derived" }
            payload = JsonCodecs.encode(grant.basis)
            _ <- sql"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions,issuer,created_at,expires_at,basis,basis_payload,generation)
              VALUES(${uuid(grant.id)},${uuid(grant.scope.dataset)},${uuid(grant.recipient)},${grant.scope.release.map(uuid)},
                ${grant.actions.toList.map(_.toString).sorted},${grant.issuer.map(uuid)},${Timestamp.from(grant.createdAt)},
                ${grant.expiresAt.map(Timestamp.from)},$basis,$payload::jsonb,${grant.generation.value})""".update.run
            _ <- sql"""INSERT INTO policy_audit(dataset_id,actor,grant_id,action) VALUES
              (${uuid(grant.scope.dataset)},${uuid(ctx.principal)},${uuid(grant.id)},'Granted')""".update.run
          yield ()
        }
    yield grant

  /** Current policy projections for access managers and principals (F03.3). Both need an initialized organization;
    * a legacy catalog has no recorded bases to explain. Evaluated under the shared policy guard with the DB clock.
    */
  private def policyRead[A](ctx: AccessContext)(f: (OrganizationAuthority.State, OrganizationAuthority.PolicyAdmission, java.time.Instant) => Either[Problem, A]): ConnectionIO[A] =
    for
      _ <- OperationsSql.active
      organized <- OrganizationSql.guard(false)
      _ <- lift(Either.cond(organized, (), Problem.Unsupported("access inspection requires an initialized organization")))
      _ <- CredentialSql.check(ctx)
      state <- PolicySql.load
      now <- OrganizationSql.now
      value <- lift(f(state, OrganizationAuthority.PolicyAdmission(ctx.principal, true, None), now))
    yield value

  def grants(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, Vector[OrganizationAuthority.GrantView]]] =
    run(policyRead(ctx)((s, a, now) => OrganizationAuthority.datasetGrants(s, a, dataset, now)))

  def explainAccess(ctx: AccessContext, subject: PrincipalId, scope: OrganizationAuthority.DataScope,
      permission: OrganizationAuthority.Permission): F[Either[Problem, OrganizationAuthority.Explanation]] =
    run(policyRead(ctx)((s, a, now) => OrganizationAuthority.explain(s, a, subject, scope, permission, now)))

  def revoke(ctx: AccessContext, dataset: DatasetId, grant: GrantId): F[Either[Problem, Unit]] = run {
    import OrganizationAuthority.*
    import OrganizationCodecs.given
    import io.circe.syntax.*
    for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(true)
      _ <- CredentialSql.check(ctx)
      shares <- PolicySql.grants
      before <- lift(shares.find(g => g.id == grant && g.scope.dataset == dataset).toRight(Problem.Denied))
      _ <- if enabled then PolicySql.check(ctx,Request.RevokeGrant(grant)) else authorize(ctx,uuid(dataset),true,Action.ManageAccess).void
      _ <- if before.revoked then ().pure[ConnectionIO] else for
        generation <- lift(OrganizationGeneration.from(before.generation.value + 1))
        after = before.copy(revoked=true,generation=generation)
        at <- OrganizationSql.now
        _ <- PolicySql.commit(ctx.principal,"grant:"+grant.render,"revoke:"+grant.render,"GrantRevoked",
          Json.obj("before" -> before.asJson,"after" -> after.asJson,"revokedAt" -> at.asJson)) {
          sql"UPDATE access_grant SET revoked_at=${Timestamp.from(at)},generation=${generation.value} WHERE id=${uuid(grant)}".update.run *>
          sql"""INSERT INTO policy_audit(dataset_id,actor,grant_id,action) VALUES
            (${uuid(dataset)},${uuid(ctx.principal)},${uuid(grant)},'Revoked')""".update.run.void
        }
      yield ()
    yield ()
  }

  def select(ctx: AccessContext, refs: Vector[ReleaseRef], query: ScientificQuery): F[Either[Problem, FrozenSelection]] = run {
    for
      _ <- lift(FrozenSelection.from(refs, query, Vector.empty))
      _ <- authorizeScopes(ctx, refs.map(r => (r.dataset, Action.ReadMetadata, Some(r.release))), false)
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
      _ <- authorizeScopes(ctx, result.releases.map(r => (r.dataset, Action.ReadMetadata, Some(r.release))), false)
    yield result
  }

  def startRun(ctx: AccessContext, spec: RunSpec, key: IdempotencyKey): F[Either[Problem, ProcessingRun]] =
    ids.next[RunTag].flatMap { id => run {
      for
        _ <- lift(Either.cond(spec.valid, (), Problem.Unsupported("invalid run specification")))
        _ <- runAuthority(ctx, spec, true)
        input <- acceptedSource(spec.input)
        _ <- lift(spec.validateInput(input))
        previous <- sql"SELECT payload::text FROM processing_run WHERE destination=${uuid(spec.destination)} AND owner=${uuid(ctx.principal)} AND request_key=${key.value}"
          .query[String].option
        result <- previous match
          case Some(payload) => lift(JsonCodecs.decode[ProcessingRun](payload)).flatMap(r =>
            lift(Either.cond(r.spec == spec, r, Problem.Conflict("run key belongs to another request"))))
          case None => for {
            target <- authorize(ctx, uuid(spec.destination), true, Action.Propose)
            _ <- lift(Either.cond(target.head == spec.base, (), Problem.Conflict("run destination head advanced")))
            record = ProcessingRun(id, ctx.principal, spec, key, 0, RunStatus.Planned)
            payload = JsonCodecs.encode(record)
            _ <- sql"INSERT INTO processing_run(id,destination,owner,request_key,payload) VALUES(${uuid(id)},${uuid(spec.destination)},${uuid(ctx.principal)},${key.value},$payload::jsonb)".update.run
          } yield record
      yield result
    }}

  private def runAuthority(ctx: AccessContext, spec: RunSpec, source: Boolean): ConnectionIO[Unit] =
    authorizeScopes(ctx, Vector((spec.destination, Action.Propose, Option.empty[ReleaseId])) ++
      (if source then Vector((spec.input.dataset, Action.ReadContent, Some(spec.input.release))) else Vector.empty), true)

  private def loadRun(ctx: AccessContext, id: RunId, lock: Boolean): ConnectionIO[ProcessingRun] =
    (fr"SELECT payload::text FROM processing_run WHERE id=${uuid(id)} AND owner=${uuid(ctx.principal)}" ++
      (if lock then fr"FOR UPDATE" else Fragment.empty)).query[String].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      .flatMap(p => lift(JsonCodecs.decode[ProcessingRun](p))).flatMap(r => Records.require(r.id == id && r.owner == ctx.principal, "run identity mismatch").as(r))

  def readRun(ctx: AccessContext, id: RunId): F[Either[Problem, ProcessingRun]] = run {
    for
      record <- loadRun(ctx, id, false)
      _ <- runAuthority(ctx, record.spec, false)
    yield record
  }

  def advanceRun(ctx: AccessContext, id: RunId, generation: Long, update: RunUpdate): F[Either[Problem, ProcessingRun]] = run {
    for
      initial <- loadRun(ctx, id, false)
      _ <- runAuthority(ctx, initial.spec, Set(RunStatus.Executing, RunStatus.Completed)(update.status))
      current <- loadRun(ctx, id, true)
      _ <- lift(Either.cond(current.generation == generation, (), Problem.Conflict("stale run generation")))
      next <- lift(current.advance(update))
      _ <- next.result.traverse_(p => acceptedSource(p.input).flatMap(r => lift(p.validateInput(r))))
      _ <- next.retained.traverse_(b => sql"INSERT INTO blob(digest,size) VALUES(${b.digest.value},${b.size}) ON CONFLICT DO NOTHING".update.run)
      payload = JsonCodecs.encode(next)
      _ <- sql"UPDATE processing_run SET payload=$payload::jsonb WHERE id=${uuid(id)}".update.run
    yield next
  }

  def completedRun(ctx: AccessContext, id: RunId): F[Either[Problem, ProcessingRun]] = run {
    for
      record <- loadRun(ctx, id, false)
      _ <- authorizeScopes(ctx, Vector((record.spec.input.dataset, Action.ReadContent, Some(record.spec.input.release))), false)
      _ <- lift(Either.cond(record.valid && record.status == RunStatus.Completed, (), Problem.Conflict("run has not completed")))
    yield record
  }

  def observations(ctx: AccessContext, refs: Vector[ReleaseRef], query: ObservationQuery): F[Either[Problem, Vector[ObservationHit]]] = run {
    for
      _ <- lift(Either.cond(query.valid && refs.size <= 100 && refs.distinct.size == refs.size, (), Problem.Unsupported("invalid observation query universe")))
      _ <- authorizeScopes(ctx, (refs ++ query.inputs.map(_.at)).distinct.map(r => (r.dataset, Action.ReadMetadata, Some(r.release))), false)
      // Accepted releases are immutable. Decode each input release once in this request,
      // while retaining the exact identity/path/blob check for every requested artifact.
      _ <- query.inputs.groupBy(_.at).toVector.sortBy((at, _) => (at.dataset.render, at.release.render)).traverse_ { (at, inputs) =>
        acceptedSource(at).flatMap(r => inputs.traverse_(i => Records.require(i.matches(r), "unknown observation input")))
      }
      hits <- refs.traverse { ref =>
        acceptedSource(ref).flatMap(r => ScientificSql.ready(r.interpretation) *> ScientificSql.observations(ref, r.interpretation, query))
      }
    yield hits.flatten.sortBy(h => (h.at.dataset.render, h.at.release.render, h.observation.source.path.value))
  }

  private def acceptedSource(ref: ReleaseRef): ConnectionIO[Release] =
    for
      row <- sql"SELECT candidate_id,accepted_by,accepted_at FROM release WHERE dataset_id=${uuid(ref.dataset)} AND id=${uuid(ref.release)}"
        .query[(UUID, UUID, Timestamp)].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      (id, actor, at) = row
      c <- fetchCandidate(id).flatMap(hydrateCandidate)
    yield Release(ref, c.base, c.manifest, c.interpretation, c.assessment, c.activity, Id[PrincipalTag](actor), at.toInstant)

  private def validateEvidence(activity: Activity, interpretation: Interpretation, assessment: Assessment): ConnectionIO[Unit] =
    activity.projection.filter(_ => activity.kind == ActivityKind.PrivateBundle).traverse_(p => acceptedSource(p.source).flatMap(r =>
      Records.require(r.activity.kind != ActivityKind.PrivateBundle, "nested private bundles are not supported"))) *>
      activity.processing.toVector.traverse(p => acceptedSource(p.input)).flatMap(sources =>
        lift(DerivativeEvidence.validate(activity, interpretation, assessment, sources)))

  def create(ctx: AccessContext, custodian: CustodianId): F[Dataset] =
    ids.next[DatasetTag].flatMap { id =>
      val dataset = Dataset(id, custodian, ctx.principal, None)
      run((OperationsSql.active *> OrganizationSql.guard(true).flatMap(enabled =>
        lift(Either.cond(!enabled, (), Problem.Conflict("use atomic organization dataset creation")))) *> CredentialSql.check(ctx) *> sql"INSERT INTO principal(id) VALUES (${uuid(ctx.principal)}) ON CONFLICT DO NOTHING".update.run *> sql"INSERT INTO dataset (id,custodian,curator) VALUES (${uuid(id)},${uuid(custodian)},${uuid(ctx.principal)})"
        .update.run).as(dataset)).flatMap(Checked.lift[F, Dataset])
    }

  def dataset(ctx: AccessContext, id: DatasetId): F[Either[Problem, Dataset]] = run(for
      dataset <- authorize(ctx,uuid(id),false)
      enabled <- OrganizationSql.guard(false)
      _ <- if enabled then PolicySql.check(ctx,OrganizationAuthority.Request.ReadHead(id)) else ().pure[ConnectionIO]
    yield dataset)

  def draft(ctx: AccessContext, dataset: DatasetId, base: Option[ReleaseId], manifest: Manifest, activity: Activity): F[Either[Problem, Draft]] =
    ids.next[DraftTag].flatMap { id => run {
      for
        // A publisher/key spans destinations; serialize before taking ordered dataset locks.
        _ <- activity.publication.traverse_ { p =>
          val lock = s"sanctity.publication:${ctx.principal.render}:${p.key.value}"
          sql"SELECT pg_advisory_xact_lock(hashtextextended($lock, 0))::text".query[String].unique.void
        }
        _ <- authorizeScopes(ctx, Vector((dataset, Action.Propose, Option.empty[ReleaseId])) ++
          activity.processing.toVector.map(p => (p.input.dataset, Action.ReadContent, Some(p.input.release))) ++
          activity.projection.filter(_ => activity.kind == ActivityKind.PrivateBundle).toVector.map(p => (p.source.dataset, Action.ReadContent, Some(p.source.release))), true)
        target <- authorize(ctx, uuid(dataset), true, Action.Propose)
        _ <- lift(Either.cond(activity.actor == ctx.principal, (), Problem.Denied))
        _ <- processingInput(ctx, activity)
        _ <- activity.publication match
          case Some(p) => for {
            r <- loadRun(ctx, p.run, false)
            _ <- lift(p.validate(ctx, r))
            _ <- lift(Either.cond(p.matches(dataset, base, activity), (), Problem.Conflict("publication intent differs from draft")))
          } yield ()
          case None => activity.processing.traverse_(p => sql"SELECT payload::text FROM processing_run WHERE id=${uuid(p.invocation)}".query[String].option
            .flatMap(_.traverse_(text => lift(JsonCodecs.decode[ProcessingRun](text)).flatMap(r => lift(r.submission(ctx, dataset, base, p))))))
        _ <- base.traverse_(r => acceptedSource(ReleaseRef(dataset, r)))
        previous <- activity.publication match
          case Some(p) => sql"SELECT intent_id,draft_id FROM processing_publication WHERE publisher=${uuid(ctx.principal)} AND request_key=${p.key.value}"
            .query[(String, UUID)].option.flatMap(_.traverse { (intent, draft) =>
              lift(Either.cond(intent == p.id.value, draft, Problem.Conflict("publication key belongs to another request")))
            })
          case None => activity.processing.flatTraverse(p => sql"SELECT draft_id FROM derivative_submission WHERE destination=${uuid(dataset)} AND invocation=${uuid(p.invocation)}".query[UUID].option)
        result <- previous match
          case Some(previousId) => authorizedDraft(ctx, previousId, false).flatMap { (d, _) =>
            lift(Either.cond(d.dataset == dataset && d.base == base && d.activity.actor == ctx.principal &&
              d.activity.publication == activity.publication && d.activity.processing.map(_.id) == activity.processing.map(_.id),
              d, Problem.Conflict("invocation already collected with another request")))
          }
          case None => for {
            _ <- lift(Either.cond(activity.processing.isEmpty || target.head == base, (), Problem.Conflict("derivative destination head advanced")))
            _ <- Records.storeManifest(manifest)
            _ <- Records.storeEvidence(activity)
            payload = JsonCodecs.encode(activity)
            _ <- sql"""INSERT INTO draft (id,dataset_id,base,generation,manifest_id,activity)
              VALUES (${uuid(id)},${uuid(dataset)},${base.map(uuid)},0,${manifest.id.value.value},$payload::jsonb)""".update.run
            _ <- activity.publication match
              case Some(p) => sql"""INSERT INTO processing_publication(publisher,request_key,intent_id,run_id,draft_id)
                VALUES(${uuid(p.publisher)},${p.key.value},${p.id.value},${uuid(p.run)},${uuid(id)})""".update.run.void
              case None => activity.processing.traverse_(p => sql"INSERT INTO derivative_submission(destination,invocation,draft_id) VALUES(${uuid(dataset)},${uuid(p.invocation)},${uuid(id)})".update.run)
          } yield Draft(id, dataset, base, 0L, manifest, activity)
      yield result
    }}

  def readDraft(ctx: AccessContext, id: DraftId): F[Either[Problem, Draft]] =
    run(authorizedDraft(ctx, uuid(id), false).map(_._1))

  def revise(ctx: AccessContext, id: DraftId, generation: Long, manifest: Manifest, activity: Activity): F[Either[Problem, Draft]] =
    run {
      for
        loaded <- authorizedDraft(ctx, uuid(id), true)
        (current, accepted) = loaded
        _ <- lift(Either.cond(!accepted, (), Problem.Conflict("draft already accepted")))
        _ <- lift(Either.cond(current.generation == generation, (), Problem.Conflict("stale draft generation")))
        _ <- lift(Admission.revision(current.activity, activity))
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
          baseContent <- reinterpretationBase(draft)
          _ <- lift(Admission.prepared(draft, generation, accepted, interpretation, assessment, baseContent))
          _ <- validateEvidence(draft.activity, interpretation, assessment)
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

  /** A candidate discloses a proposed state: current Propose plus dataset metadata rights (ADR 002), never Accept
    * alone. The public candidate carries no activity or source evidence, so no evidence scopes are requested; private
    * bundle sources keep their actor-and-ReadContent restriction. A legacy catalog keeps Propose-or-Accept.
    */
  def candidate(ctx: AccessContext, id: CandidateId): F[Either[Problem, Candidate]] =
    run(for
      organized <- OrganizationSql.guard(false)
      pair <- candidateScope(ctx, uuid(id), false, Action.Propose, allowAccept = !organized)
      _ <- if organized then PolicySql.check(ctx, OrganizationAuthority.Request.Candidate(pair._1.id, Vector.empty)) else ().pure[ConnectionIO]
      candidate <- hydrateCandidate(pair._2)
    yield candidate)

  def receipt(ctx: AccessContext, id: CandidateId, key: IdempotencyKey): F[Either[Problem, Option[AcceptanceReceipt]]] =
    run {
      for
        row <- fetchCandidate(uuid(id))
        activity <- lift(JsonCodecs.decode[Activity](row.activity))
        // Replaying a receipt reauthorizes metadata on its exact release (Request.Receipt, in priorReceipt), not Accept;
        // private-source and processing-input restrictions still apply. A legacy catalog keeps its Accept check.
        organized <- OrganizationSql.guard(false)
        _ <- authorizeActivity(ctx, row, activity, false, requireAccept = !organized)
        _ <- processingInput(ctx, activity)
        receipt <- priorReceipt(ctx, row, key)
      yield receipt
    }

  def accept(ctx: AccessContext, id: CandidateId, key: IdempotencyKey, lease: Option[WorkLease] = None): F[Either[Problem, AcceptanceReceipt]] =
    ids.next[ReleaseTag].flatMap { releaseId =>
      run {
        for
          initial <- fetchCandidate(uuid(id))
          activity <- lift(JsonCodecs.decode[Activity](initial.activity))
          _ <- authorizeActivity(ctx, initial, activity, true)
          _ <- processingInput(ctx, activity)
          scoped <- candidateScope(ctx, uuid(id), true, Action.Accept)
          (dataset, row) = scoped
          prior <- priorReceipt(ctx, row, key)
          receipt <- prior match
            case Some(receipt) => receipt.pure[ConnectionIO]
            case None =>
              for
                // The first successful response discloses the new receipt too. Require
                // metadata rights covering the future release before committing science.
                enabled <- OrganizationSql.guard(false)
                _ <- if enabled then PolicySql.check(ctx,OrganizationAuthority.Request.Data(
                  OrganizationAuthority.DataScope(dataset.id),OrganizationAuthority.Permission.ReadMetadata)) else ().pure[ConnectionIO]
                _ <- OperationsSql.fenceCandidate(row.id, lease)
                candidate <- hydrateCandidate(row)
                draftRow <- fetchDraft(row.draft)
                draft <- hydrateDraft(draftRow)
                baseContent <- reinterpretationBase(draft)
                _ <- validateEvidence(candidate.activity, candidate.interpretation, candidate.assessment)
                _ <- lift(Admission.accept(dataset, draft, candidate, draftRow.accepted.nonEmpty, baseContent))
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

  private def authorizeActivity(ctx: AccessContext, row: CandidateRow, activity: Activity, write: Boolean, requireAccept: Boolean = true): ConnectionIO[Unit] =
    val scopes = Vector((Id[DatasetTag](row.dataset), Action.Accept, Option.empty[ReleaseId])).filter(_ => requireAccept) ++
      activity.projection.toVector.map(p => if activity.kind == ActivityKind.PrivateBundle then
        (p.source.dataset, Action.ReadContent, Some(p.source.release)) else (p.source.dataset, Action.ManageAccess, Option.empty[ReleaseId])) ++
      activity.processing.toVector.map(p => (p.input.dataset, Action.ReadContent, Some(p.input.release)))
    authorizeScopes(ctx, scopes, write) *>
      privateActivity(ctx, activity)

  private def processingInput(ctx: AccessContext, activity: Activity): ConnectionIO[Unit] =
    activity.processing.traverse_ { p =>
      for
        _ <- authorize(ctx, uuid(p.input.dataset), false, Action.ReadContent, Some(p.input.release))
        row <- sql"SELECT candidate_id,accepted_by,accepted_at FROM release WHERE dataset_id=${uuid(p.input.dataset)} AND id=${uuid(p.input.release)}"
          .query[(UUID, UUID, Timestamp)].option.flatMap(v => lift(v.toRight(Problem.Denied)))
        (id, actor, at) = row
        c <- fetchCandidate(id).flatMap(hydrateCandidate)
        _ <- lift(p.validateInput(Release(p.input, c.base, c.manifest, c.interpretation, c.assessment, c.activity,
          Id[PrincipalTag](actor), at.toInstant)))
      yield ()
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

  /** Discover inherited private scopes before locking any dataset; recheck after locks. */
  private def privateSource(id: UUID, release: Option[ReleaseId]): ConnectionIO[Option[ReleaseRef]] =
    for
      row <- sql"SELECT head FROM dataset WHERE id=$id".query[Option[UUID]].option
      selected = release.map(uuid).orElse(row.flatten)
      activity <- selected.flatTraverse(r => sql"SELECT c.activity::text FROM release r JOIN candidate c ON c.id=r.candidate_id WHERE r.dataset_id=$id AND r.id=$r"
        .query[String].option.flatMap(_.traverse(p => lift(JsonCodecs.decode[Activity](p)))))
    yield activity.filter(_.kind == ActivityKind.PrivateBundle).flatMap(_.projection).map(_.source)

  private def lockScopes(scopes: Vector[(DatasetId, Action, Option[ReleaseId])], write: Boolean): ConnectionIO[Unit] =
    for
      _ <- OperationsSql.active
      _ <- OrganizationSql.guard(false)
      dependencies <- scopes.traverse((id, _, release) => privateSource(uuid(id), release))
      ids = (scopes.map(_._1) ++ dependencies.flatten.map(_.dataset)).distinct.sortBy(_.render)
      lock = if write then Fragment.const("FOR UPDATE") else Fragment.const("FOR SHARE")
      _ <- ids.traverse_(id => (fr"SELECT id FROM dataset WHERE id=${uuid(id)}" ++ lock).query[UUID].option)
      current <- scopes.traverse((id, _, release) => privateSource(uuid(id), release))
      _ <- lift(Either.cond(current == dependencies, (), Problem.Conflict("private authorization scope changed; retry")))
    yield ()

  private def authorizeScopes(ctx: AccessContext, scopes: Vector[(DatasetId, Action, Option[ReleaseId])], write: Boolean): ConnectionIO[Unit] =
    lockScopes(scopes, write) *> scopes.sortBy(_._1.render).traverse_((id, action, release) => authorize(ctx, uuid(id), write, action, release))

  private[postgres] def authorize(ctx: AccessContext, id: UUID, write: Boolean,
      action: Action = Action.ReadMetadata, release: Option[ReleaseId] = None,
      allowAccept: Boolean = false): ConnectionIO[Dataset] =
    val lock = if write then Fragment.const("FOR UPDATE") else Fragment.const("FOR SHARE")
    for
      _ <- OperationsSql.active
      organized <- OrganizationSql.guard(false)
      _ <- CredentialSql.check(ctx)
      _ <- lockScopes(Vector((Id[DatasetTag](id), action, release)), write)
      row <- (fr"SELECT id,custodian,curator,head FROM dataset WHERE id=$id" ++ lock)
        .query[(UUID, UUID, UUID, Option[UUID])].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      (datasetId, custodian, curator, head) = row
      dataset = Dataset(Id[DatasetTag](datasetId), Id[CustodianTag](custodian), Id[PrincipalTag](curator), head.map(Id[ReleaseTag].apply))
      _ <- if organized then for
        allowed <- PolicySql.permits(ctx, OrganizationAuthority.Request.Data(
          OrganizationAuthority.DataScope(dataset.id,release),OrganizationAuthority.Permission.fromLegacy(action)))
        alternate <- if allowAccept then PolicySql.permits(ctx,OrganizationAuthority.Request.Data(
          OrganizationAuthority.DataScope(dataset.id,release),OrganizationAuthority.Permission.Accept)) else false.pure[ConnectionIO]
        _ <- lift(Either.cond(allowed || alternate, (), Problem.Denied))
      yield ()
      else for
        grants <- sql"""SELECT id,recipient,release_id,actions FROM access_grant
          WHERE dataset_id=$id AND recipient=${uuid(ctx.principal)} AND revoked_at IS NULL
            AND (expires_at IS NULL OR expires_at>clock_timestamp())"""
          .query[(UUID,UUID,Option[UUID],List[String])].to[Vector]
        decoded <- grants.traverse { (grant,recipient,fixed,actions) =>
          actions.traverse(a => lift(Action.values.find(_.toString == a).toRight(Problem.Integrity("unknown legacy action"))))
            .map(as => Grant(Id[GrantTag](grant),dataset.id,Id[PrincipalTag](recipient),fixed.map(Id[ReleaseTag].apply),as.toSet))
        }
        _ <- lift(Either.cond(Authority.permits(dataset,decoded,ctx,action,release) ||
          (allowAccept && Authority.permits(dataset,decoded,ctx,Action.Accept,release)), (), Problem.Denied))
      yield ()
      _ <- release.orElse(dataset.head).traverse_ { selected =>
        sql"SELECT c.activity::text FROM release r JOIN candidate c ON c.id=r.candidate_id WHERE r.dataset_id=$id AND r.id=${uuid(selected)}"
          .query[String].option.flatMap(_.traverse_(payload => lift(JsonCodecs.decode[Activity](payload)).flatMap { activity =>
            if activity.kind != ActivityKind.PrivateBundle then ().pure[ConnectionIO]
            else for
              _ <- lift(Either.cond(activity.actor == ctx.principal && Set(Action.ReadMetadata, Action.ReadContent, Action.Accept)(action), (), Problem.Denied))
              p <- lift(activity.projection.toRight(Problem.Denied))
              _ <- authorize(ctx, uuid(p.source.dataset), false, if action == Action.ReadMetadata then Action.ReadMetadata else Action.ReadContent, Some(p.source.release))
            yield ()
          }))
      }
    yield dataset

  private def privateActivity(ctx: AccessContext, activity: Activity): ConnectionIO[Unit] =
    if activity.kind != ActivityKind.PrivateBundle then ().pure[ConnectionIO]
    else for
      _ <- lift(Either.cond(activity.actor == ctx.principal, (), Problem.Denied))
      p <- lift(activity.projection.toRight(Problem.Denied))
      _ <- authorize(ctx, uuid(p.source.dataset), false, Action.ReadContent, Some(p.source.release))
    yield ()

  private def authorizedDraft(ctx: AccessContext, id: UUID, write: Boolean): ConnectionIO[(Draft, Boolean)] =
    for
      dataset <- sql"SELECT dataset_id FROM draft WHERE id=$id".query[UUID].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      row <- fetchDraft(id)
      draft <- hydrateDraft(row)
      _ <- authorizeScopes(ctx, Vector((Id[DatasetTag](dataset), Action.Propose, Option.empty[ReleaseId])) ++
        draft.activity.projection.filter(_ => draft.activity.kind == ActivityKind.PrivateBundle).toVector.map(p => (p.source.dataset, Action.ReadContent, Some(p.source.release))), write)
      _ <- privateActivity(ctx, draft.activity)
      current <- fetchDraft(id)
      _ <- lift(Either.cond(current.generation == row.generation && current.accepted == row.accepted, (), Problem.Conflict("draft changed while acquiring authority; retry")))
    yield draft -> row.accepted.nonEmpty

  private def candidateScope(ctx: AccessContext, id: UUID, write: Boolean, action: Action, allowAccept: Boolean = false): ConnectionIO[(Dataset, CandidateRow)] =
    for
      datasetId <- sql"SELECT dataset_id FROM candidate WHERE id=$id".query[UUID].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      row <- fetchCandidate(id)
      activity <- lift(JsonCodecs.decode[Activity](row.activity))
      _ <- lockScopes(Vector((Id[DatasetTag](datasetId), action, Option.empty[ReleaseId])) ++
        activity.projection.filter(_ => activity.kind == ActivityKind.PrivateBundle).toVector.map(p => (p.source.dataset, Action.ReadContent, Some(p.source.release))), write)
      dataset <- authorize(ctx, datasetId, write, action, allowAccept = allowAccept)
      _ <- privateActivity(ctx, activity)
    yield dataset -> row

  private def reinterpretationBase(draft: Draft): ConnectionIO[Option[ManifestId]] =
    if draft.activity.kind != ActivityKind.Reinterpretation then none[ManifestId].pure[ConnectionIO]
    else draft.base.flatTraverse { base =>
      sql"""SELECT c.manifest_id FROM release r JOIN candidate c ON c.id=r.candidate_id
        WHERE r.id=${uuid(base)} AND r.dataset_id=${uuid(draft.dataset)}""".query[String].option
        .flatMap(_.traverse(value => lift(Digest.from(value).map(ManifestId.apply))))
    }

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
        case None => none[AcceptanceReceipt].pure[ConnectionIO]
        case Some((candidate, release, at)) =>
          val receipt = AcceptanceReceipt(ReleaseRef(Id[DatasetTag](row.dataset),Id[ReleaseTag](release)),Id[CandidateTag](row.id),key,at.toInstant)
          for
            enabled <- OrganizationSql.guard(false)
            _ <- if enabled then PolicySql.check(ctx,OrganizationAuthority.Request.Receipt(receipt.release)) else ().pure[ConnectionIO]
            _ <- lift(Either.cond(candidate == row.id, (), Problem.Conflict("idempotency key was used for another candidate")))
          yield Some(receipt)
      }


private object PostgresCatalog:
  final case class DraftRow(id: UUID, dataset: UUID, base: Option[UUID], generation: Long, manifest: String, activity: String, accepted: Option[UUID])
  final case class CandidateRow(id: UUID, draft: UUID, dataset: UUID, base: Option[UUID], generation: Long,
      manifest: String, interpretation: String, assessment: String, activity: String)
