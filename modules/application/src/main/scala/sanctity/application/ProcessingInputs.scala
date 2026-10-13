package sanctity.application

import cats.effect.Async
import cats.syntax.all.*
import sanctity.kernel.*

trait InputPlanner:
  def assess(manifest: Manifest, interpretation: Interpretation, assessment: Assessment,
      roots: Vector[RelPath], contract: InputContract): Either[Problem, InputReadiness]

/** Compilation uses the existing authorized view workflow; the final report examines emitted inputs. */
final class ProcessingInputs[F[_]: Async](catalog: Catalog[F], repository: NativeRepository[F],
    views: ScientificViews[F], planner: InputPlanner):
  def readiness(ctx: AccessContext, source: ReleaseRef, selectionId: SelectionId,
      contract: InputContract): F[Either[Problem, InputReadiness]] = Checked.attempt {
    for
      sourceRelease <- catalog.release(ctx, source).flatMap(Checked.lift[F, Release])
      selection <- catalog.selection(ctx, selectionId).flatMap(Checked.lift[F, FrozenSelection])
      _ <- Checked.lift[F, Unit](Either.cond(selection.releases == Vector(source), (), Problem.Unsupported("input contract requires one pinned release")))
      report <- Checked.lift[F, InputReadiness](planner.assess(sourceRelease.manifest, sourceRelease.interpretation,
        sourceRelease.assessment, selection.hits.map(_.path), contract))
    yield report
  }

  def prepare(ctx: AccessContext, source: ReleaseRef, selection: SelectionId, destination: DatasetId,
      disclosure: DisclosureProfile, contract: InputContract): F[Either[Problem, Preview]] =
    prepareWith(ctx, source, selection, destination, disclosure, contract, false)

  def preparePrivate(ctx: AccessContext, source: ReleaseRef, selection: SelectionId, destination: DatasetId,
      fields: DisclosureProfile, contract: InputContract): F[Either[Problem, Preview]] =
    prepareWith(ctx, source, selection, destination, fields, contract, true)

  private def prepareWith(ctx: AccessContext, source: ReleaseRef, selection: SelectionId, destination: DatasetId,
      disclosure: DisclosureProfile, contract: InputContract, privateUse: Boolean): F[Either[Problem, Preview]] = Checked.attempt {
    for
      original <- readiness(ctx, source, selection, contract).flatMap(Checked.lift[F, InputReadiness])
      _ <- ready(original)
      preview <- (if privateUse then views.preparePrivate(ctx, source, selection, destination, disclosure)
        else views.prepare(ctx, source, selection, destination, disclosure)).flatMap(Checked.lift[F, Preview])
      c = preview.candidate
      emitted <- Checked.lift[F, InputReadiness](planner.assess(c.manifest, c.interpretation, c.assessment, original.roots, contract))
      _ <- ready(emitted)
      receipt <- Checked.lift[F, ProjectionReceipt](c.activity.projection.flatMap(_.receipt).toRight(Problem.Integrity("bundle lacks projection receipt")))
      binding = BundleEvidence(emitted, ContentTree.identity(c.manifest), receipt.id)
      draft <- catalog.revise(ctx, c.draft, c.generation, c.manifest, c.activity.copy(bundle = Some(binding))).flatMap(Checked.lift[F, Draft])
      prepared <- repository.prepare(ctx, draft.id).flatMap(Checked.lift[F, Preview])
    yield prepared
  }

  /** Retrieval checks current authority; this method never substitutes a newer head. */
  def bundle(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]] = Checked.attempt {
    for
      _ <- catalog.check(ctx, ref.dataset, Action.ReadContent, Some(ref.release)).flatMap(Checked.lift[F, Unit])
      release <- catalog.release(ctx, ref).flatMap(Checked.lift[F, Release])
      _ <- Checked.lift[F, Unit](Either.cond(release.activity.bundle.exists(_.matches(release.manifest,
        release.interpretation, release.assessment, release.activity.projection)), (), Problem.Unsupported("release is not an assessed processing bundle")))
    yield release
  }

  def materialize(ctx: AccessContext, ref: ReleaseRef, target: ExportTarget[F]): F[Either[Problem, ExportReceipt]] = Checked.attempt {
    bundle(ctx, ref).flatMap(Checked.lift[F, Release]) *> repository.exportFull(ctx, ref, target).flatMap(Checked.lift[F, ExportReceipt])
  }

  private def ready(report: InputReadiness): F[Unit] = Checked.lift[F, Unit](Either.cond(report.ready, (),
    Problem.Rejected(report.requirements.filter(_.status != RequirementStatus.Satisfied).map(r =>
      Finding("Input." + r.code, Severity.Error, r.target, r.message)))))
