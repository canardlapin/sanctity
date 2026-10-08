package sanctity.application

import cats.effect.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

import java.nio.charset.StandardCharsets

/** First-slice workflows. Public failures are typed; cancellation remains cancellation. */
final class NativeRepository[F[_]](
    catalog: Catalog[F],
    store: BlobStore[F],
    semantics: SemanticInterpreter[F],
    ids: IdSource[F]
)(using F: Async[F]):
  private val maxEntries = 10000
  private val maxEditBytes = 4 * 1024 * 1024

  def create(ctx: AccessContext, custodian: CustodianId): F[Dataset] = catalog.create(ctx, custodian)

  def importNative(ctx: AccessContext, datasetId: DatasetId, source: NativeSource[F]): F[Either[Problem, Preview]] =
    Checked.attempt {
      for
        _ <- catalog.check(ctx, datasetId, Action.Propose).flatMap(Checked.lift[F, Unit])
        dataset <- catalog.dataset(ctx, datasetId).flatMap(Checked.lift[F, Dataset])
        _ <- Checked.lift[F, Unit](Either.cond(dataset.head.isEmpty, (), Problem.Conflict("native import requires an empty dataset")))
        inventory <- source.entries.take(maxEntries.toLong + 1).compile.toVector
        _ <- Checked.lift[F, Unit](Either.cond(inventory.size <= maxEntries, (), Problem.LimitExceeded(s"reference inventory exceeds $maxEntries entries")))
        entries <- inventory.traverse {
          case SourceEntry.Directory(path) => F.pure[Entry](Entry.Directory(path))
          case file: SourceEntry.File =>
            for
              artifact <- ids.next[ArtifactTag]
              blob <- store.put(source.read(file))
              _ <- Checked.lift[F, Unit](Either.cond(blob.size == file.size, (), Problem.Integrity(s"source size changed: ${file.path.value}")))
            yield Entry.File(file.path, artifact, blob)
        }
        manifest <- Checked.lift[F, Manifest](Manifest.from(entries))
        now <- Clock[F].realTimeInstant
        activity = Activity(ActivityKind.NativeImport, ctx.principal, "Native BIDS import", Some(source.label), now, evidence = source.evidence, retained = source.retainedEvidence)
        draft <- catalog.draft(ctx, datasetId, None, manifest, activity).flatMap(Checked.lift[F, Draft])
        preview <- prepareInternal(ctx, draft)
      yield preview
    }

  def correctMetadata(ctx: AccessContext, base: ReleaseRef, path: RelPath, text: String, reason: String): F[Either[Problem, Preview]] =
    Checked.attempt {
      for
        _ <- catalog.check(ctx, base.dataset, Action.Propose).flatMap(Checked.lift[F, Unit])
        release <- catalog.release(ctx, base).flatMap(Checked.lift[F, Release])
        _ <- Checked.lift[F, Unit](Either.cond(path.fileName.endsWith(".json"), (), Problem.Unsupported("this slice edits JSON metadata only")))
        _ <- Checked.lift[F, Unit](Either.cond(reason.trim.nonEmpty, (), Problem.Unsupported("a correction reason is required")))
        bytes = text.getBytes(StandardCharsets.UTF_8)
        _ <- Checked.lift[F, Unit](Either.cond(bytes.length <= maxEditBytes, (), Problem.LimitExceeded("metadata edit exceeds 4 MiB")))
        artifact <- release.manifest.byPath.get(path) match
          case Some(Entry.File(_, id, _)) => F.pure(id)
          case Some(Entry.Directory(_)) => F.raiseError[ArtifactId](Failure(Problem.Conflict("cannot replace a directory")))
          case None => ids.next[ArtifactTag]
        blob <- store.put(Stream.emits(bytes).covary[F])
        manifest <- Checked.lift[F, Manifest](release.manifest.change(Vector(Change.Put(path, artifact, blob))))
        now <- Clock[F].realTimeInstant
        draft <- catalog.draft(ctx, base.dataset, Some(base.release), manifest,
          Activity(ActivityKind.MetadataCorrection, ctx.principal, reason, None, now)).flatMap(Checked.lift[F, Draft])
        preview <- prepareInternal(ctx, draft)
      yield preview
    }

  def prepare(ctx: AccessContext, draft: DraftId): F[Either[Problem, Preview]] =
    Checked.attempt(catalog.readDraft(ctx, draft).flatMap(Checked.lift[F, Draft]).flatMap(prepareInternal(ctx, _)))

  def accept(ctx: AccessContext, candidate: CandidateId, key: IdempotencyKey, lease: Option[WorkLease] = None): F[Either[Problem, AcceptanceReceipt]] =
    Checked.attempt {
      catalog.receipt(ctx, candidate, key).flatMap(Checked.lift[F, Option[AcceptanceReceipt]]).flatMap {
        case Some(receipt) => F.pure(receipt)
        case None =>
          for
            prepared <- catalog.candidate(ctx, candidate).flatMap(Checked.lift[F, Candidate])
            _ <- Checked.lift[F, Unit](Either.cond(prepared.assessment.admissible, (), Problem.Rejected(prepared.assessment.errors)))
            _ <- (prepared.manifest.files.map(_.blob) ++ prepared.activity.evidence ++ prepared.activity.retained).distinct.traverse_(store.verify)
            receipt <- catalog.accept(ctx, candidate, key, lease).flatMap(Checked.lift[F, AcceptanceReceipt])
          yield receipt
      }
    }

  def release(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]] = catalog.release(ctx, ref)

  def explain(ctx: AccessContext, ref: ReleaseRef, path: RelPath): F[Either[Problem, ArtifactMetadata]] =
    catalog.metadata(ctx, ref, path)

  /** Resolve through an authorized release path; a digest is never a capability.
    * Every emitted chunk rechecks current authority, including credential expiry/revocation.
    */
  def readFile(ctx: AccessContext, ref: ReleaseRef, path: RelPath): F[Either[Problem, AuthorizedFile[F]]] =
    Checked.attempt {
      for
        _ <- catalog.check(ctx, ref.dataset, Action.ReadContent, Some(ref.release)).flatMap(Checked.lift[F, Unit])
        release <- catalog.release(ctx, ref).flatMap(Checked.lift[F, Release])
        file <- Checked.lift[F, Entry.File](release.manifest.byPath.get(path).collect { case f: Entry.File => f }.toRight(Problem.Denied))
        bytes = store.read(file.blob).chunkN(64 * 1024).evalTap(_ =>
          catalog.check(ctx, ref.dataset, Action.ReadContent, Some(ref.release)).flatMap(Checked.lift[F, Unit])).unchunks
      yield AuthorizedFile(file.blob.digest, file.blob.size, bytes)
    }

  def exportFull(ctx: AccessContext, ref: ReleaseRef, target: ExportTarget[F]): F[Either[Problem, ExportReceipt]] =
    Checked.attempt {
      catalog.check(ctx, ref.dataset, Action.ReadContent, Some(ref.release)).flatMap(Checked.lift[F, Unit]) *>
        catalog.release(ctx, ref).flatMap(Checked.lift[F, Release]).flatMap { r =>
          val guarded = new BlobStore[F]:
            def put(bytes: Stream[F, Byte]): F[BlobRef] = F.raiseError(Failure(Problem.Denied))
            def read(blob: BlobRef): Stream[F, Byte] =
              store.read(blob).chunkN(64 * 1024).evalTap(_ => catalog.check(ctx, ref.dataset, Action.ReadContent, Some(ref.release))
                .flatMap(Checked.lift[F, Unit])).unchunks
            def verify(blob: BlobRef): F[Unit] = read(blob).compile.drain
          target.materialize(r.manifest, guarded)
        }
    }

  private def prepareInternal(ctx: AccessContext, draft: Draft): F[Preview] =
    for
      result <- semantics.prepare(draft.manifest)
      (interpretation, assessment) = result
      candidate <- catalog.prepared(ctx, draft.id, draft.generation, interpretation, assessment).flatMap(Checked.lift[F, Candidate])
      before <- draft.base.traverse(id => catalog.release(ctx, ReleaseRef(draft.dataset, id)).flatMap(Checked.lift[F, Release]))
      empty <- Checked.lift[F, Manifest](Manifest.from(Vector.empty))
      changes = before.fold(empty)(_.manifest).diff(candidate.manifest)
      impact = before.fold(Vector.empty[ArtifactImpact])(r => Impact.between(r.interpretation, candidate.interpretation))
      relationships = before.fold(Vector.empty[RelationshipDelta])(r => RelationshipImpact.between(r.interpretation, candidate.interpretation))
    yield Preview(candidate, changes, impact, relationships)
