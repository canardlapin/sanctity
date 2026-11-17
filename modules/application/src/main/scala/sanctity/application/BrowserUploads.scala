package sanctity.application

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

/** The client supplies paths and bytes. An inventory digest alone never grants access to stored content. */
final class BrowserUploads[F[_]: Async](catalog: Catalog[F], journal: ImportJournal[F], store: BlobStore[F],
    repository: NativeRepository[F], ids: IdSource[F]):
  private val imports = new DurableImports(catalog, journal, store, repository, ids)
  private val label = "browser-upload-v1"
  private def checked[A](f: F[Either[Problem, A]]) = f.flatMap(Checked.lift[F, A])
  private def owned(ctx: AccessContext, id: OperationId): F[ImportOperation] =
    checked(journal.get(ctx, id)).flatMap(op => Checked.lift[F, ImportOperation](
      Either.cond(op.source == label, op, Problem.Denied)))

  def begin(ctx: AccessContext, dataset: DatasetId, key: IdempotencyKey, plan: ImportPlan,
      files: Vector[SourceEntry.File]): F[Either[Problem, ImportOperation]] = Checked.attempt {
    for
      _ <- checked(catalog.check(ctx, dataset, Action.Propose))
      _ <- Checked.lift[F, Unit](Either.cond(files.nonEmpty && files.size <= 10000 &&
        files.forall(f => f.size >= 0 && f.size <= 2L * 1024 * 1024 * 1024 && Digest.from(f.observation).isRight) &&
        files.map(f => BigInt(f.size)).sum <= 8L * 1024 * 1024 * 1024, (),
        Problem.LimitExceeded("upload requires files with SHA-256, at most 2 GiB each and 8 GiB together")))
      _ <- plan.base.traverse_(r => checked(catalog.check(ctx, dataset, Action.ReadMetadata, Some(r))))
      _ <- Checked.lift[F, Unit](Either.cond(files.forall(f => f.path.value.length <= 4096 && f.path.value.count(_ == '/') < 128),
        (), Problem.LimitExceeded("upload paths allow at most 4096 characters and 128 components")))
      directories = files.flatMap(f => Iterator.iterate(f.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten).distinct
      op <- checked(journal.begin(ctx, dataset, key, label, directories.map(SourceEntry.Directory.apply) ++ files, plan))
    yield op
  }

  def get(ctx: AccessContext, id: OperationId): F[Either[Problem, ImportOperation]] = Checked.attempt(owned(ctx, id))

  def put(ctx: AccessContext, id: OperationId, path: RelPath, bytes: Stream[F, Byte]): F[Either[Problem, Unit]] = Checked.attempt {
    for
      op <- owned(ctx, id)
      file <- Checked.lift[F, SourceEntry.File](op.inventory.collectFirst { case f: SourceEntry.File if f.path == path => f }
        .toRight(Problem.NotFound("upload file", path.value)))
      expected <- Checked.lift[F, BlobRef](Digest.from(file.observation).flatMap(BlobRef.from(_, file.size)))
      verified = ViewStream.verified(CurrentAuthority.bytes(bytes, checked(catalog.check(ctx, op.dataset, Action.Propose))),
        expected, Problem.InvalidManifest("uploaded file differs from its declared digest or size"))
      _ <- if op.acquired.exists(f => f.path == path && f.blob == expected) then
        verified.compile.drain
      else checked(journal.claim(ctx, id, 60)).flatMap { lease =>
        val write = for
          artifact <- ids.next[ArtifactTag]
          blob <- store.put(verified)
          _ <- checked(journal.record(ctx, lease, Entry.File(path, artifact, blob)))
        yield ()
        CurrentAuthority.during(checked(journal.renew(ctx, lease, 60)).void)(write).guarantee(journal.surrender(ctx, lease))
      }
    yield ()
  }

  private def staged(op: ImportOperation): F[NativeSource[F]] =
    val expected = op.inventory.collect { case f: SourceEntry.File => f }
    val files = op.acquired.map(f => f.path -> f.blob).toMap
    Checked.lift[F, Unit](Either.cond(expected.forall(f => files.get(f.path).exists(b =>
      b.size == f.size && b.digest.value == f.observation)), (), Problem.Conflict("upload is incomplete"))).as(new NativeSource[F]:
      def label = op.source
      def entries = Stream.emits(op.inventory).covary[F]
      def read(file: SourceEntry.File) = store.read(files(file.path))
    )

  def preview(ctx: AccessContext, id: OperationId): F[Either[Problem, Preview]] = Checked.attempt {
    for
      op <- owned(ctx, id)
      source <- staged(op)
      prepared <- if op.candidate.nonEmpty then Async[F].pure(op) else checked(imports.resume(ctx, id, source))
      candidate <- Checked.lift[F, CandidateId](prepared.candidate.toRight(Problem.Integrity("upload has no candidate")))
      result <- checked(repository.preview(ctx, candidate))
    yield result
  }

  def accept(ctx: AccessContext, id: OperationId): F[Either[Problem, AcceptanceReceipt]] = Checked.attempt {
    for
      op <- owned(ctx, id)
      _ <- checked(catalog.check(ctx, op.dataset, Action.Accept))
      _ <- Checked.lift[F, Unit](Either.cond(op.candidate.nonEmpty, (), Problem.Conflict("prepare the upload before acceptance")))
      source <- staged(op)
      done <- checked(imports.resume(ctx, id, source, publish = true))
      receipt <- Checked.lift[F, AcceptanceReceipt](done.receipt.toRight(Problem.Integrity("upload has no receipt")))
    yield receipt
  }
