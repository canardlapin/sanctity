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
          _ <- authorized(s, ctx, dataset, Action.Propose)
          _ <- Either.cond(activity.actor == ctx.principal, (), Problem.Denied)
          _ <- Either.cond(base.forall(r => s.releases.get(r).exists(_.ref.dataset == dataset)), (),
            Problem.NotFound("base release", base.fold("")(_.render)))
        yield Draft(id, dataset, base, 0L, manifest, activity)
        result match
          case Left(problem) => s -> Left(problem)
          case Right(draft) => s.copy(drafts = s.drafts.updated(id, draft)) -> Right(draft)
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
          _ <- Admission.prepared(draft, generation, s.acceptedDrafts.contains(id), interpretation, assessment)
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
              _ <- candidate.activity.projection.traverse_(p => authorized(s, ctx, p.source.dataset, Action.ManageAccess))
              _ <- Admission.accept(dataset, draft, candidate, s.acceptedDrafts.contains(draft.id))
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
      receipts: Map[(DatasetId, PrincipalId, IdempotencyKey), AcceptanceReceipt] = Map.empty
  )

  def create[F[_]: Sync](ids: IdSource[F]): F[ReferenceCatalog[F]] =
    Ref.of[F, State](State()).map(new ReferenceCatalog(_, ids))

  private def authorized(s: State, ctx: AccessContext, id: DatasetId,
      action: Action = Action.ReadMetadata, release: Option[ReleaseId] = None): Either[Problem, Dataset] =
    s.datasets.get(id).filter(d => ctx.credential.isEmpty && Authority.permits(d, s.grants.values, ctx, action, release)).toRight(Problem.Denied)

  private def authorizedDraft(s: State, ctx: AccessContext, id: DraftId): Either[Problem, Draft] =
    for
      draft <- s.drafts.get(id).toRight(Problem.Denied)
      _ <- authorized(s, ctx, draft.dataset, Action.Propose)
    yield draft

  private def authorizedCandidate(s: State, ctx: AccessContext, id: CandidateId, action: Action = Action.Propose): Either[Problem, Candidate] =
    for
      candidate <- s.candidates.get(id).toRight(Problem.Denied)
      _ <- authorized(s, ctx, candidate.dataset, action)
    yield candidate

  private def priorReceipt(s: State, ctx: AccessContext, id: CandidateId, key: IdempotencyKey): Either[Problem, Option[AcceptanceReceipt]] =
    authorizedCandidate(s, ctx, id, Action.Accept).flatMap { candidate =>
      candidate.activity.projection.traverse_(p => authorized(s, ctx, p.source.dataset, Action.ManageAccess)).flatMap { _ =>
        s.receipts.get((candidate.dataset, ctx.principal, key)) match
          case Some(receipt) if receipt.candidate != id => Left(Problem.Conflict("idempotency key was used for another candidate"))
          case receipt => Right(receipt)
      }
    }
