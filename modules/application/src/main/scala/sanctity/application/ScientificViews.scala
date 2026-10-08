package sanctity.application

import cats.effect.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

import java.nio.charset.StandardCharsets

trait ViewCompiler:
  def plan(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, ProjectionPlan]

/** A view becomes an ordinary assessed release with its own grants and custody.
  * Source disclosure authority is required at preparation AND final acceptance.
  */
final class ScientificViews[F[_]: Async](
    catalog: Catalog[F], store: BlobStore[F], repository: NativeRepository[F],
    compiler: ViewCompiler, ids: IdSource[F]
):
  def prepare(ctx: AccessContext, source: ReleaseRef, selectionId: SelectionId,
      destination: DatasetId, profile: DisclosureProfile): F[Either[Problem, Preview]] = Checked.attempt {
    for
      _ <- catalog.check(ctx, source.dataset, Action.ManageAccess).flatMap(Checked.lift[F, Unit])
      _ <- catalog.check(ctx, source.dataset, Action.ReadContent, Some(source.release)).flatMap(Checked.lift[F, Unit])
      _ <- catalog.check(ctx, destination, Action.Propose).flatMap(Checked.lift[F, Unit])
      target <- catalog.dataset(ctx, destination).flatMap(Checked.lift[F, Dataset])
      _ <- Checked.lift[F, Unit](Either.cond(target.head.isEmpty && destination != source.dataset, (),
        Problem.Conflict("view requires an empty, separate destination dataset")))
      release <- catalog.release(ctx, source).flatMap(Checked.lift[F, Release])
      selection <- catalog.selection(ctx, selectionId).flatMap(Checked.lift[F, FrozenSelection])
      plan <- Checked.lift[F, ProjectionPlan](compiler.plan(release, selection, profile))
      generated <- plan.generated.traverse { (path, text) =>
        for
          id <- ids.next[ArtifactTag]
          blob <- store.put(Stream.emits(text.getBytes(StandardCharsets.UTF_8)).covary[F])
        yield Entry.File(path, id, blob)
      }
      files = plan.retained ++ generated
      directories = files.flatMap { file =>
        Iterator.iterate(file.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten.toVector
      }.distinct.map(Entry.Directory.apply)
      manifest <- Checked.lift[F, Manifest](Manifest.from(directories ++ files))
      now <- Clock[F].realTimeInstant
      activity = Activity(ActivityKind.ScientificView, ctx.principal, "Materialized authorized scientific view",
        None, now, Some(plan.evidence))
      draft <- catalog.draft(ctx, destination, None, manifest, activity).flatMap(Checked.lift[F, Draft])
      preview <- repository.prepare(ctx, draft.id).flatMap(Checked.lift[F, Preview])
    yield preview
  }
