package sanctity.application

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all.*
import sanctity.kernel.*

/** In-process reference semantics, with the same action-specific policy as PostgreSQL.
  * Scientific records are immutable; this catalog is intentionally not durable.
  */
final class ReferenceCatalog[F[_]] private (
    state: Ref[F, ReferenceCatalog.State],
    ids: IdSource[F]
)(using F: Sync[F]) extends Catalog[F]:
  import ReferenceCatalog.*

  def check(ctx: AccessContext, dataset: DatasetId, action: Action, release: Option[ReleaseId] = None): F[Either[Problem, Unit]] =
    state.get.map(s => authorized(s, ctx, dataset, action, release).void)

  def grant(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId], actions: Set[Action]): F[Either[Problem, Grant]] =
    ids.next[GrantTag].flatMap { id =>
      state.modify { s =>
        val result = for
          _ <- authorized(s, ctx, dataset, Action.ManageAccess)
          _ <- Authority.validate(release, actions)
          _ <- Either.cond(release.forall(r => s.releases.get(r).exists(_.ref.dataset == dataset)), (), Problem.Denied)
        yield Grant(id, dataset, recipient, release, actions)
        result match
          case Left(problem) => s -> Left(problem)
          case Right(grant) => s.copy(grants = s.grants.updated(id, grant)) -> Right(grant)
      }
    }

  // Organization policy is evaluated by the PostgreSQL catalog; the reference catalog models legacy grants only.
  def grantShare(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId],
      actions: Set[OrganizationAuthority.Permission], expiresAt: Option[java.time.Instant]): F[Either[Problem, OrganizationAuthority.PolicyGrant]] =
    F.pure(Left(Problem.Unsupported("shares with discovery or expiry require an initialized organization")))
  def grants(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, Vector[OrganizationAuthority.GrantView]]] =
    F.pure(Left(Problem.Unsupported("grant inspection requires an initialized organization")))
  def explainAccess(ctx: AccessContext, subject: PrincipalId, scope: OrganizationAuthority.DataScope,
      permission: OrganizationAuthority.Permission): F[Either[Problem, OrganizationAuthority.Explanation]] =
    F.pure(Left(Problem.Unsupported("access explanation requires an initialized organization")))

  def revoke(ctx: AccessContext, dataset: DatasetId, grant: GrantId): F[Either[Problem, Unit]] =
    state.modify { s =>
      val result = for
        _ <- authorized(s, ctx, dataset, Action.ManageAccess)
        _ <- Either.cond(s.grants.get(grant).exists(_.dataset == dataset), (), Problem.Denied)
      yield ()
      result.fold(p => s -> Left(p), _ => s.copy(grants = s.grants - grant) -> Right(()))
    }

  def select(ctx: AccessContext, refs: Vector[ReleaseRef], query: ScientificQuery): F[Either[Problem, FrozenSelection]] =
    state.modify { s =>
      val result = for
        _ <- query.validate
        hits <- refs.traverse { ref =>
          for
            _ <- authorized(s, ctx, ref.dataset, Action.ReadMetadata, Some(ref.release))
            release <- s.releases.get(ref.release).filter(_.ref == ref).toRight(Problem.Denied)
            found <- ReferenceQuery.evaluate(ref, release.interpretation, query)
          yield found
        }
        selection <- FrozenSelection.from(refs, query, hits.flatten)
      yield selection
      result.fold(p => s -> Left(p), selection =>
        s.copy(selections = s.selections.updated((ctx.principal, selection.id), selection)) -> Right(selection))
    }

  def selection(ctx: AccessContext, id: SelectionId): F[Either[Problem, FrozenSelection]] =
    state.get.map { s =>
      for
        selection <- s.selections.get(ctx.principal -> id).toRight(Problem.Denied)
        _ <- selection.releases.traverse_(r => authorized(s, ctx, r.dataset, Action.ReadMetadata, Some(r.release)))
      yield selection
    }

  def startRun(ctx: AccessContext, spec: RunSpec, key: IdempotencyKey): F[Either[Problem, ProcessingRun]] =
    ids.next[RunTag].flatMap { id => state.modify { s =>
      val result = for
        target <- authorized(s, ctx, spec.destination, Action.Propose)
        _ <- authorized(s, ctx, spec.input.dataset, Action.ReadContent, Some(spec.input.release))
        source <- s.releases.get(spec.input.release).filter(_.ref == spec.input).toRight(Problem.Denied)
        _ <- spec.validateInput(source)
        run <- s.runs.values.find(r => r.owner == ctx.principal && r.spec.destination == spec.destination && r.key == key) match
          case Some(r) => Either.cond(r.spec == spec, r, Problem.Conflict("run key belongs to another request"))
          case None => Either.cond(target.head == spec.base, ProcessingRun(id, ctx.principal, spec, key, 0, RunStatus.Planned), Problem.Conflict("run destination head advanced"))
      yield run
      result.fold(p => s -> Left(p), r => s.copy(runs = s.runs.updated(r.id, r)) -> Right(r))
    }}

  def readRun(ctx: AccessContext, id: RunId): F[Either[Problem, ProcessingRun]] = state.get.map { s =>
    authorizedRun(s, ctx, id).flatTap(r => authorized(s, ctx, r.spec.input.dataset, Action.ReadContent, Some(r.spec.input.release)))
  }

  def completedRun(ctx: AccessContext, id: RunId): F[Either[Problem, ProcessingRun]] = state.get.map { s =>
    for
      r <- s.runs.get(id).filter(_.owner == ctx.principal).toRight(Problem.Denied)
      _ <- authorized(s, ctx, r.spec.input.dataset, Action.ReadContent, Some(r.spec.input.release))
      _ <- Either.cond(r.valid && r.status == RunStatus.Completed, (), Problem.Conflict("run has not completed"))
    yield r
  }

  def heartbeatRun(ctx: AccessContext, id: RunId): F[Either[Problem, Unit]] = state.modify { s =>
    s.runs.get(id).filter(_.owner == ctx.principal) match
      case None => s -> Left(Problem.Denied)
      case Some(r) =>
        val allowed = authorizedRun(s, ctx, id).flatMap(_ =>
          authorized(s, ctx, r.spec.input.dataset, Action.ReadContent, Some(r.spec.input.release))).void
        allowed match
          case Left(Problem.Denied) if Set(RunStatus.Preparing, RunStatus.Executing)(r.status) =>
            val failed = r.advance(RunUpdate(RunStatus.Failed, r.execution, detail = Some("Execution authority withdrawn")))
            failed.fold(p => s -> Left(p), next => s.copy(runs = s.runs.updated(id, next)) -> Left(Problem.Denied))
          case Left(p) => s -> Left(p)
          case Right(_) => s -> Either.cond(Set(RunStatus.Preparing, RunStatus.Executing, RunStatus.Completed)(r.status),
            (), Problem.Conflict("run is no longer executable"))
  }

  def advanceRun(ctx: AccessContext, id: RunId, generation: Long, update: RunUpdate): F[Either[Problem, ProcessingRun]] =
    state.modify { s =>
      val result = for
        current <- authorizedRun(s, ctx, id)
        _ <- Either.cond(current.generation == generation, (), Problem.Conflict("stale run generation"))
        _ <- if Set(RunStatus.Executing, RunStatus.Completed)(update.status) then
          authorized(s, ctx, current.spec.input.dataset, Action.ReadContent, Some(current.spec.input.release)).void else Right(())
        next <- current.advance(update)
        _ <- next.result.traverse_(p => s.releases.get(p.input.release).toRight(Problem.Denied).flatMap(p.validateInput))
      yield next
      result.fold(p => s -> Left(p), r => s.copy(runs = s.runs.updated(id, r)) -> Right(r))
    }

  def observations(ctx: AccessContext, refs: Vector[ReleaseRef], query: ObservationQuery): F[Either[Problem, Vector[ObservationHit]]] =
    state.get.map { s =>
      for
        _ <- Either.cond(query.valid && refs.size <= 100 && refs.distinct.size == refs.size, (), Problem.Unsupported("invalid observation query universe"))
        _ <- query.inputs.traverse_ { i =>
          authorized(s, ctx, i.at.dataset, Action.ReadMetadata, Some(i.at.release)) *>
            Either.cond(s.releases.get(i.at.release).exists(i.matches), (), Problem.Integrity("unknown observation input"))
        }
        hits <- refs.traverse { ref =>
          for
            _ <- authorized(s, ctx, ref.dataset, Action.ReadMetadata, Some(ref.release))
            r <- s.releases.get(ref.release).filter(_.ref == ref).toRight(Problem.Denied)
          yield r.interpretation.observations.filter(query.matches).map(ObservationHit(ref, _))
        }
      yield hits.flatten.sortBy(h => (h.at.dataset.render, h.at.release.render, h.observation.source.path.value))
    }

  def create(ctx: AccessContext, custodian: CustodianId): F[Dataset] =
    F.raiseWhen(ctx.credential.nonEmpty)(Failure(Problem.Denied)) *> ids.next[DatasetTag].flatMap { id =>
      val dataset = Dataset(id, custodian, ctx.principal, None)
      state.update(s => s.copy(datasets = s.datasets.updated(id, dataset))).as(dataset)
    }

  def dataset(ctx: AccessContext, id: DatasetId): F[Either[Problem, Dataset]] =
    state.get.map(authorized(_, ctx, id))

  def draft(ctx: AccessContext, dataset: DatasetId, base: Option[ReleaseId], manifest: Manifest, activity: Activity): F[Either[Problem, Draft]] =
    ids.next[DraftTag].flatMap { id =>
      state.modify { s =>
        val result = for
          target <- authorized(s, ctx, dataset, Action.Propose)
          _ <- Either.cond(activity.actor == ctx.principal, (), Problem.Denied)
          _ <- Either.cond(base.forall(r => s.releases.get(r).exists(_.ref.dataset == dataset)), (), Problem.NotFound("base release", base.fold("")(_.render)))
          _ <- processingInput(s, ctx, activity)
          _ <- activity.publication match
            case Some(p) => for {
              r <- s.runs.get(p.run).toRight(Problem.Denied)
              _ <- p.validate(ctx, r)
              _ <- Either.cond(p.matches(dataset, base, activity), (), Problem.Conflict("publication intent differs from draft"))
            } yield ()
            case None => activity.processing.traverse_(p => s.runs.get(p.invocation).traverse_(_.submission(ctx, dataset, base, p)))
          previousId = activity.publication match
            case Some(p) => s.publications.get(p.publisher -> p.key)
            case None => activity.processing.flatMap(p => s.submissions.get(dataset -> p.invocation))
          previous = previousId.flatMap(s.drafts.get)
          draft <- previous match
            case Some(d) => Either.cond(d.dataset == dataset && d.base == base && d.activity.actor == ctx.principal &&
              d.activity.publication == activity.publication &&
              d.activity.processing.map(_.id) == activity.processing.map(_.id), d, Problem.Conflict("invocation already collected with another request"))
            case None => Either.cond(activity.processing.isEmpty || target.head == base, Draft(id, dataset, base, 0L, manifest, activity), Problem.Conflict("derivative destination head advanced"))
        yield draft
        result match
          case Left(problem) => s -> Left(problem)
          case Right(draft) => s.copy(drafts = s.drafts.updated(draft.id, draft),
            publications = activity.publication.fold(s.publications)(p => s.publications.updated(p.publisher -> p.key, draft.id)),
            submissions = activity.processing.filter(_ => activity.publication.isEmpty).fold(s.submissions)(p =>
              s.submissions.updated(dataset -> p.invocation, draft.id))) -> Right(draft)
      }
    }

  def readDraft(ctx: AccessContext, id: DraftId): F[Either[Problem, Draft]] =
    state.get.map(s => authorizedDraft(s, ctx, id))

  def revise(ctx: AccessContext, id: DraftId, generation: Long, manifest: Manifest, activity: Activity): F[Either[Problem, Draft]] =
    state.modify { s =>
      val result = for
        current <- authorizedDraft(s, ctx, id)
        _ <- Either.cond(!s.acceptedDrafts.contains(id), (), Problem.Conflict("draft already accepted"))
        _ <- Either.cond(current.generation == generation, (), Problem.Conflict("stale draft generation"))
        _ <- Admission.revision(current.activity, activity)
        _ <- Either.cond(activity.actor == ctx.principal, (), Problem.Denied)
      yield current.copy(generation = generation + 1, manifest = manifest, activity = activity)
      result match
        case Left(problem) => s -> Left(problem)
        case Right(updated) => s.copy(drafts = s.drafts.updated(id, updated)) -> Right(updated)
    }

  def prepared(ctx: AccessContext, id: DraftId, generation: Long, interpretation: Interpretation, assessment: Assessment): F[Either[Problem, Candidate]] =
    ids.next[CandidateTag].flatMap { candidateId =>
      state.modify { s =>
        val result = for
          draft <- authorizedDraft(s, ctx, id)
          _ <- Admission.prepared(draft, generation, s.acceptedDrafts.contains(id), interpretation, assessment,
            draft.base.flatMap(s.releases.get).filter(_.ref.dataset == draft.dataset).map(_.manifest.id))
          _ <- DerivativeEvidence.validate(draft.activity, interpretation, assessment, s.releases.values.toVector)
        yield Candidate(candidateId, id, draft.dataset, draft.base, generation, draft.manifest, interpretation, assessment, draft.activity)
        result match
          case Left(problem) => s -> Left(problem)
          case Right(candidate) => s.copy(candidates = s.candidates.updated(candidateId, candidate)) -> Right(candidate)
      }
    }

  def candidate(ctx: AccessContext, id: CandidateId): F[Either[Problem, Candidate]] =
    state.get.map(s => authorizedCandidate(s, ctx, id).orElse(authorizedCandidate(s, ctx, id, Action.Accept)))

  def receipt(ctx: AccessContext, candidate: CandidateId, key: IdempotencyKey): F[Either[Problem, Option[AcceptanceReceipt]]] =
    state.get.map(s => priorReceipt(s, ctx, candidate, key))

  def accept(ctx: AccessContext, id: CandidateId, key: IdempotencyKey, lease: Option[WorkLease] = None): F[Either[Problem, AcceptanceReceipt]] =
    if lease.nonEmpty then F.pure(Left(Problem.Unsupported("durable leases require the PostgreSQL catalog")))
    else (ids.next[ReleaseTag], Clock[F].realTimeInstant).tupled.flatMap { (releaseId, now) =>
      state.modify { s =>
        priorReceipt(s, ctx, id, key) match
          case Left(problem) => s -> Left(problem)
          case Right(Some(receipt)) => s -> Right(receipt)
          case Right(None) =>
            val result = for
              candidate <- authorizedCandidate(s, ctx, id, Action.Accept)
              dataset <- authorized(s, ctx, candidate.dataset, Action.Accept)
              draft <- s.drafts.get(candidate.draft).toRight(Problem.Denied)
              _ <- projectionAuthority(s, ctx, candidate.activity)
              _ <- processingInput(s, ctx, candidate.activity)
              _ <- DerivativeEvidence.validate(candidate.activity, candidate.interpretation, candidate.assessment, s.releases.values.toVector)
              _ <- Admission.accept(dataset, draft, candidate, s.acceptedDrafts.contains(draft.id),
                draft.base.flatMap(s.releases.get).filter(_.ref.dataset == draft.dataset).map(_.manifest.id))
            yield (candidate, dataset)

            result match
              case Left(problem) => s -> Left(problem)
              case Right((candidate, dataset)) =>
                val ref = ReleaseRef(dataset.id, releaseId)
                val receipt = AcceptanceReceipt(ref, id, key, now)
                val release = Release(ref, candidate.base, candidate.manifest, candidate.interpretation,
                  candidate.assessment, candidate.activity, ctx.principal, now)
                val next = s.copy(
                  datasets = s.datasets.updated(dataset.id, dataset.copy(head = Some(releaseId))),
                  releases = s.releases.updated(releaseId, release),
                  acceptedDrafts = s.acceptedDrafts + candidate.draft,
                  receipts = s.receipts.updated((dataset.id, ctx.principal, key), receipt)
                )
                next -> Right(receipt)
      }
    }

  def release(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]] =
    state.get.map { s =>
      for
        _ <- authorized(s, ctx, ref.dataset, Action.ReadMetadata, Some(ref.release))
        release <- s.releases.get(ref.release).filter(_.ref == ref).toRight(Problem.NotFound("release", ref.release.render))
      yield release
    }

  def metadata(ctx: AccessContext, ref: ReleaseRef, path: RelPath): F[Either[Problem, ArtifactMetadata]] =
    release(ctx, ref).map(_.flatMap(_.interpretation.byPath.get(path).toRight(Problem.NotFound("interpreted artifact", path.value))))

object ReferenceCatalog:
  private final case class State(
      datasets: Map[DatasetId, Dataset] = Map.empty,
      grants: Map[GrantId, Grant] = Map.empty,
      selections: Map[(PrincipalId, SelectionId), FrozenSelection] = Map.empty,
      drafts: Map[DraftId, Draft] = Map.empty,
      candidates: Map[CandidateId, Candidate] = Map.empty,
      releases: Map[ReleaseId, Release] = Map.empty,
      acceptedDrafts: Set[DraftId] = Set.empty,
      runs: Map[RunId, ProcessingRun] = Map.empty,
      submissions: Map[(DatasetId, RunId), DraftId] = Map.empty,
      publications: Map[(PrincipalId, IdempotencyKey), DraftId] = Map.empty,
      receipts: Map[(DatasetId, PrincipalId, IdempotencyKey), AcceptanceReceipt] = Map.empty
  )

  def create[F[_]: Sync](ids: IdSource[F]): F[ReferenceCatalog[F]] =
    Ref.of[F, State](State()).map(new ReferenceCatalog(_, ids))

  private def authorized(s: State, ctx: AccessContext, id: DatasetId,
      action: Action = Action.ReadMetadata, release: Option[ReleaseId] = None): Either[Problem, Dataset] =
    for
      dataset <- s.datasets.get(id).filter(d => ctx.credential.isEmpty && Authority.permits(d, s.grants.values, ctx, action, release)).toRight(Problem.Denied)
      _ <- release.orElse(dataset.head).flatMap(s.releases.get).filter(_.activity.kind == ActivityKind.PrivateBundle).traverse_ { r =>
        for
          _ <- Either.cond(r.activity.actor == ctx.principal && Set(Action.ReadMetadata, Action.ReadContent, Action.Accept)(action), (), Problem.Denied)
          p <- r.activity.projection.toRight(Problem.Denied)
          _ <- authorized(s, ctx, p.source.dataset, if action == Action.ReadMetadata then Action.ReadMetadata else Action.ReadContent, Some(p.source.release))
        yield ()
      }
    yield dataset

  private def projectionAuthority(s: State, ctx: AccessContext, activity: Activity): Either[Problem, Unit] =
    activity.projection.traverse_ { p =>
      if activity.kind == ActivityKind.PrivateBundle then
        authorized(s, ctx, p.source.dataset, Action.ReadContent, Some(p.source.release)) *>
          Either.cond(s.releases.get(p.source.release).exists(_.activity.kind != ActivityKind.PrivateBundle), (), Problem.Unsupported("nested private bundles are not supported"))
      else authorized(s, ctx, p.source.dataset, Action.ManageAccess).void
    }

  private def authorizedRun(s: State, ctx: AccessContext, id: RunId): Either[Problem, ProcessingRun] =
    for
      r <- s.runs.get(id).filter(_.owner == ctx.principal).toRight(Problem.Denied)
      _ <- authorized(s, ctx, r.spec.destination, Action.Propose)
    yield r

  private def privateActivity(s: State, ctx: AccessContext, activity: Activity): Either[Problem, Unit] =
    if activity.kind != ActivityKind.PrivateBundle then Right(())
    else Either.cond(activity.actor == ctx.principal, (), Problem.Denied) *> projectionAuthority(s, ctx, activity)

  private def authorizedDraft(s: State, ctx: AccessContext, id: DraftId): Either[Problem, Draft] =
    for
      draft <- s.drafts.get(id).toRight(Problem.Denied)
      _ <- authorized(s, ctx, draft.dataset, Action.Propose)
      _ <- privateActivity(s, ctx, draft.activity)
    yield draft

  private def authorizedCandidate(s: State, ctx: AccessContext, id: CandidateId, action: Action = Action.Propose): Either[Problem, Candidate] =
    for
      candidate <- s.candidates.get(id).toRight(Problem.Denied)
      _ <- authorized(s, ctx, candidate.dataset, action)
      _ <- privateActivity(s, ctx, candidate.activity)
    yield candidate

  private def processingInput(s: State, ctx: AccessContext, activity: Activity): Either[Problem, Unit] =
    activity.processing.traverse_ { p =>
      for
        _ <- authorized(s, ctx, p.input.dataset, Action.ReadContent, Some(p.input.release))
        release <- s.releases.get(p.input.release).filter(_.ref == p.input).toRight(Problem.Denied)
        _ <- p.validateInput(release)
      yield ()
    }

  private def priorReceipt(s: State, ctx: AccessContext, id: CandidateId, key: IdempotencyKey): Either[Problem, Option[AcceptanceReceipt]] =
    authorizedCandidate(s, ctx, id, Action.Accept).flatMap { candidate =>
      (projectionAuthority(s, ctx, candidate.activity) *>
        processingInput(s, ctx, candidate.activity)).flatMap { _ =>
        s.receipts.get((candidate.dataset, ctx.principal, key)) match
          case Some(receipt) if receipt.candidate != id => Left(Problem.Conflict("idempotency key was used for another candidate"))
          case receipt => Right(receipt)
      }
    }
