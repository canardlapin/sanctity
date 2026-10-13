package sanctity.application

import cats.effect.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

/** A durable draft is the output-collection journal. Resume by draft ID; retry each declared file. */
final class DerivativeSubmissions[F[_]: Async](catalog: Catalog[F], store: BlobStore[F],
    repository: NativeRepository[F], ids: IdSource[F]):
  /** Reuse retained outputs with a new explicit publication decision; never execute a producer. */
  def publish(ctx: AccessContext, intent: PublicationIntent): F[Either[Problem, Draft]] = Checked.attempt {
    for
      run <- catalog.completedRun(ctx, intent.run).flatMap(Checked.lift[F, ProcessingRun])
      receipt <- Checked.lift[F, ProcessingEvidence](intent.validate(ctx, run))
      _ <- input(ctx, receipt)
      _ <- run.retained.traverse_(store.verify)
      empty <- Checked.lift[F, Manifest](Manifest.from(Vector.empty))
      now <- Clock[F].realTimeInstant
      activity = Activity(ActivityKind.Derivative, ctx.principal, "Publish retained completed processing outputs", None, now,
        retained = run.retained, processing = Some(receipt), publication = Some(intent))
      draft <- catalog.draft(ctx, intent.destination, intent.base, empty, activity).flatMap(Checked.lift[F, Draft])
    yield draft
  }

  /** Declare outputs that may not have arrived yet. Unknown bytes are not registered as stored blobs. */
  def begin(ctx: AccessContext, destination: DatasetId, receipt: ProcessingEvidence): F[Either[Problem, Draft]] =
    start(ctx, destination, None, receipt, Vector.empty)

  /** Local producer variant: verify and retain all previously staged outputs before returning the draft. */
  def beginStaged(ctx: AccessContext, destination: DatasetId, receipt: ProcessingEvidence): F[Either[Problem, Draft]] =
    start(ctx, destination, None, receipt, receipt.expected.map(_.blob))

  def beginAt(ctx: AccessContext, destination: ReleaseRef, receipt: ProcessingEvidence): F[Either[Problem, Draft]] =
    start(ctx, destination.dataset, Some(destination.release), receipt, Vector.empty)

  def beginStagedAt(ctx: AccessContext, destination: ReleaseRef, receipt: ProcessingEvidence): F[Either[Problem, Draft]] =
    start(ctx, destination.dataset, Some(destination.release), receipt, receipt.expected.map(_.blob))

  private def start(ctx: AccessContext, destination: DatasetId, base: Option[ReleaseId], receipt: ProcessingEvidence,
      staged: Vector[BlobRef]): F[Either[Problem, Draft]] = Checked.attempt {
    for
      _ <- Checked.lift[F, Unit](Either.cond(receipt.valid && destination != receipt.input.dataset, (), Problem.InvalidManifest("invalid derivative receipt or destination")))
      _ <- input(ctx, receipt)
      _ <- (Vector(receipt.producer.code, receipt.request) ++ staged).distinct.traverse_(store.verify)
      empty <- Checked.lift[F, Manifest](Manifest.from(Vector.empty))
      now <- Clock[F].realTimeInstant
      activity = Activity(ActivityKind.Derivative, ctx.principal, "Collect declared processing outputs", None, now,
        retained = (Vector(receipt.producer.code, receipt.request) ++ staged).distinct, processing = Some(receipt))
      draft <- catalog.draft(ctx, destination, base, empty, activity).flatMap(Checked.lift[F, Draft])
    yield draft
  }

  def collect(ctx: AccessContext, id: DraftId, path: RelPath, bytes: Stream[F, Byte]): F[Either[Problem, Draft]] = Checked.attempt {
    for
      draft <- catalog.readDraft(ctx, id).flatMap(Checked.lift[F, Draft])
      receipt <- Checked.lift[F, ProcessingEvidence](draft.activity.processing.toRight(Problem.Unsupported("draft has no processing receipt")))
      _ <- input(ctx, receipt)
      expected <- Checked.lift[F, OutputFile](receipt.expected.find(_.path == path).toRight(Problem.Unsupported("output was not declared by the producer")))
      updated <- draft.manifest.byPath.get(path) match
        case Some(f: Entry.File) if f.blob == expected.blob => store.verify(f.blob).as(draft)
        case Some(_) => Async[F].raiseError[Draft](Failure(Problem.Conflict("output path already contains different content")))
        case None =>
          for
            blob <- store.put(bytes.take(expected.blob.size + 1).chunkN(64 * 1024).evalTap(_ =>
              catalog.check(ctx, receipt.input.dataset, Action.ReadContent, Some(receipt.input.release)).flatMap(Checked.lift[F, Unit])).unchunks)
            _ <- Checked.lift[F, Unit](Either.cond(blob == expected.blob, (), Problem.Integrity("collected output differs from producer inventory")))
            _ <- input(ctx, receipt)
            before <- draft.base.traverse(r => catalog.release(ctx, ReleaseRef(draft.dataset, r)).flatMap(Checked.lift[F, Release]))
            artifact <- before.flatMap(_.manifest.byPath.get(path)).collect { case f: Entry.File => f.artifact } match
              case Some(a) => Async[F].pure(a)
              case None => ids.next[ArtifactTag]
            parents = Iterator.iterate(path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten.toVector
              .filterNot(draft.manifest.byPath.contains).map(Entry.Directory.apply)
            manifest <- Checked.lift[F, Manifest](Manifest.from(draft.manifest.entries ++ parents :+ Entry.File(path, artifact, blob)))
            revised <- catalog.revise(ctx, id, draft.generation, manifest, draft.activity.copy(actor = ctx.principal)).flatMap(Checked.lift[F, Draft])
          yield revised
    yield updated
  }

  /** Reopen staged output by its declared draft membership, never by a caller-supplied digest. */
  def collectStaged(ctx: AccessContext, id: DraftId, path: RelPath): F[Either[Problem, Draft]] = Checked.attempt {
    for
      draft <- catalog.readDraft(ctx, id).flatMap(Checked.lift[F, Draft])
      file <- Checked.lift[F, OutputFile](draft.activity.processing.flatMap(_.expected.find(_.path == path))
        .toRight(Problem.Unsupported("output is not declared in this draft")))
      result <- collect(ctx, id, path, store.read(file.blob)).flatMap(Checked.lift[F, Draft])
    yield result
  }

  def prepare(ctx: AccessContext, id: DraftId): F[Either[Problem, Preview]] = Checked.attempt {
    for
      draft <- catalog.readDraft(ctx, id).flatMap(Checked.lift[F, Draft])
      receipt <- Checked.lift[F, ProcessingEvidence](draft.activity.processing.toRight(Problem.Unsupported("draft has no processing receipt")))
      _ <- input(ctx, receipt)
      _ <- Checked.lift[F, Unit](Either.cond(receipt.matches(draft.manifest), (), Problem.Conflict("required outputs remain uncollected")))
      preview <- repository.prepare(ctx, id).flatMap(Checked.lift[F, Preview])
    yield preview
  }

  private def input(ctx: AccessContext, receipt: ProcessingEvidence): F[Unit] =
    catalog.check(ctx, receipt.input.dataset, Action.ReadContent, Some(receipt.input.release)).flatMap(Checked.lift[F, Unit]) *>
      catalog.release(ctx, receipt.input).flatMap(Checked.lift[F, Release]).flatMap(r => Checked.lift[F, Unit](receipt.validateInput(r)))
