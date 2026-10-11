package sanctity.application

import cats.effect.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

trait ViewCompiler:
  def documents(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Vector[RelPath]]
  def plan(source: Release, selection: FrozenSelection, profile: DisclosureProfile,
      documents: Map[RelPath, String] = Map.empty): Either[Problem, ProjectionPlan]

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
      paths <- Checked.lift[F, Vector[RelPath]](compiler.documents(release, selection, profile))
      inputs <- Checked.lift[F, Vector[Entry.File]](paths.distinct.traverse(p => release.manifest.byPath.get(p)
        .collect { case f: Entry.File => f }.toRight(Problem.Integrity("view document is missing"))))
      _ <- Checked.lift[F, Unit](Either.cond(inputs.forall(_.blob.size <= 4L * 1024 * 1024) &&
        inputs.foldLeft(BigInt(0))((n, f) => n + f.blob.size) <= 32L * 1024 * 1024,
        (), Problem.LimitExceeded("view allows 4 MiB per source document and 32 MiB total")))
      documents <- inputs.traverse { f =>
        store.read(f.blob).chunkN(64 * 1024).evalTap(_ =>
          catalog.check(ctx, source.dataset, Action.ReadContent, Some(source.release)).flatMap(Checked.lift[F, Unit]))
          .unchunks.take(4L * 1024 * 1024 + 1).compile.to(Array).flatMap { bytes =>
          val decoded = for
            _ <- Either.cond(bytes.length.toLong == f.blob.size && Digest.bytes(bytes) == f.blob.digest, (),
              Problem.Integrity("view source bytes differ from the manifest"))
            text <- Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
              .toEither.leftMap(_ => Problem.Unsupported(s"view document is not UTF-8: ${f.path.value}"))
          yield f.path -> text
          Checked.lift[F, (RelPath, String)](decoded)
        }
      }.map(_.toMap)
      plan <- Checked.lift[F, ProjectionPlan](compiler.plan(release, selection, profile, documents))
      _ <- Checked.lift[F, Unit](Either.cond(plan.generated.forall(_._2.getBytes(StandardCharsets.UTF_8).length <= 4L * 1024 * 1024) &&
        plan.generated.foldLeft(BigInt(0))((n, v) => n + v._2.getBytes(StandardCharsets.UTF_8).length) <= 32L * 1024 * 1024,
        (), Problem.LimitExceeded("view generated documents exceed the 4 MiB/32 MiB profile")))
      retained <- plan.retained.traverse { file =>
        // Localizing one inherited companion to several acquisitions creates distinct artifacts sharing bytes.
        if release.manifest.byPath.get(file.path).contains(file) then Async[F].pure(file)
        else ids.next[ArtifactTag].map(id => Entry.File(file.path, id, file.blob))
      }
      generated <- plan.generated.traverse { (path, text) =>
        for
          id <- ids.next[ArtifactTag]
          blob <- store.put(Stream.emits(text.getBytes(StandardCharsets.UTF_8)).covary[F])
        yield Entry.File(path, id, blob)
      }
      files = retained ++ generated
      directories = files.flatMap { file =>
        Iterator.iterate(file.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten.toVector
      }.distinct.map(Entry.Directory.apply)
      manifest <- Checked.lift[F, Manifest](Manifest.from(directories ++ files))
      receipt <- Checked.lift[F, ProjectionReceipt](ProjectionReceipt.from(release.manifest,
        release.interpretation.id, manifest, plan.steps, plan.evidence.binding))
      now <- Clock[F].realTimeInstant
      activity = Activity(ActivityKind.ScientificView, ctx.principal, "Materialized authorized scientific view",
        None, now, Some(plan.evidence.copy(receipt = Some(receipt))))
      draft <- catalog.draft(ctx, destination, None, manifest, activity).flatMap(Checked.lift[F, Draft])
      preview <- repository.prepare(ctx, draft.id).flatMap(Checked.lift[F, Preview])
    yield preview
  }
