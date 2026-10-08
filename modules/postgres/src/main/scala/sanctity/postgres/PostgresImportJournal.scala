package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*
import doobie.postgres.implicits.*
import sanctity.application.*
import sanctity.kernel.*

import java.util.UUID

final class PostgresImportJournal[F[_]: Async] private[postgres](
    xa: Transactor[F], catalog: PostgresCatalog[F], ids: IdSource[F]
) extends ImportJournal[F]:
  import Records.{lift, uuid}
  import JsonCodecs.given

  private def run[A](program: ConnectionIO[A]): F[Either[Problem, A]] = OperationsSql.run(xa)(program)

  private def owned(ctx: AccessContext, id: OperationId): ConnectionIO[Unit] =
    for
      dataset <- sql"SELECT dataset_id FROM import_operation WHERE id=${uuid(id)} AND owner=${uuid(ctx.principal)}"
        .query[UUID].option.flatMap(v => lift(v.toRight(Problem.Denied)))
      _ <- catalog.authorize(ctx, dataset, true, Action.Propose)
      _ <- sql"SELECT id FROM import_operation WHERE id=${uuid(id)} FOR UPDATE".query[UUID].unique
    yield ()

  private def load(id: OperationId): ConnectionIO[ImportOperation] =
    for
      row <- sql"""SELECT dataset_id,owner,idempotency_key,source,inventory::text,candidate_id,receipt::text
        FROM import_operation WHERE id=${uuid(id)}""".query[(UUID, UUID, String, String, String, Option[UUID], Option[String])].unique
      (dataset, owner, key, source, inventory, candidate, receipt) = row
      parsedKey <- lift(IdempotencyKey.from(key))
      entries <- lift(JsonCodecs.decode[Vector[SourceEntry]](inventory))
      received <- sql"SELECT payload::text FROM acquired_file WHERE operation_id=${uuid(id)} ORDER BY path".query[String].to[Vector]
      files <- received.traverse(t => lift(JsonCodecs.decode[Entry](t)).flatMap {
        case f: Entry.File => f.pure[ConnectionIO]
        case _ => lift(Left(Problem.Integrity("acquired object is not a file")))
      })
      accepted <- receipt.traverse(t => lift(JsonCodecs.decode[AcceptanceReceipt](t)))
    yield ImportOperation(id, Id[DatasetTag](dataset), Id[PrincipalTag](owner), parsedKey, source, entries, files,
      candidate.map(Id[CandidateTag].apply), accepted)

  def begin(ctx: AccessContext, dataset: DatasetId, key: IdempotencyKey, source: String, inventory: Vector[SourceEntry]): F[Either[Problem, ImportOperation]] =
    ids.next[OperationTag].flatMap { id => run {
      for
        data <- catalog.authorize(ctx, uuid(dataset), true, Action.Propose)
        _ <- lift(Either.cond(inventory.size <= 10000 && inventory.map(_.path).distinct.size == inventory.size &&
          inventory.forall { case SourceEntry.File(_, size, _) => size >= 0; case _ => true }, (),
          Problem.InvalidManifest("invalid operation inventory")))
        payload = JsonCodecs.encode(inventory.sortBy(_.path.value))
        existing <- sql"""SELECT id FROM import_operation WHERE dataset_id=${uuid(dataset)}
          AND owner=${uuid(ctx.principal)} AND idempotency_key=${key.value}""".query[UUID].option
        selected <- existing match
          case Some(existing) =>
            load(Id[OperationTag](existing)).flatMap(op => lift(Either.cond(op.source == source && op.inventory == inventory.sortBy(_.path.value),
              op.id, Problem.Conflict("operation key already identifies a different inventory"))))
          case None =>
            lift(Either.cond(data.head.isEmpty, (), Problem.Conflict("native import requires an empty dataset"))) *>
              sql"""INSERT INTO import_operation(id,dataset_id,owner,idempotency_key,source,inventory)
                VALUES(${uuid(id)},${uuid(dataset)},${uuid(ctx.principal)},${key.value},$source,$payload::jsonb)""".update.run.as(id)
        result <- load(selected)
      yield result
    }}

  def get(ctx: AccessContext, id: OperationId): F[Either[Problem, ImportOperation]] = run(owned(ctx, id) *> load(id))

  def claim(ctx: AccessContext, id: OperationId, seconds: Int): F[Either[Problem, WorkLease]] =
    ids.next[LeaseTag].flatMap { token => run {
      for
        _ <- owned(ctx, id)
        _ <- OperationsSql.duration(seconds)
        until <- OperationsSql.until(seconds)
        claimed <- sql"""UPDATE import_operation SET lease_token=${uuid(token)},lease_until=$until
          WHERE id=${uuid(id)} AND receipt IS NULL AND (lease_until IS NULL OR lease_until <= clock_timestamp())""".update.run
        _ <- lift(Either.cond(claimed == 1, (), Problem.Conflict("operation is complete or already leased")))
      yield WorkLease(id, token, until.toInstant)
    }}

  def renew(ctx: AccessContext, lease: WorkLease, seconds: Int): F[Either[Problem, WorkLease]] = run {
    for
      _ <- owned(ctx, lease.operation)
      _ <- OperationsSql.duration(seconds)
      _ <- OperationsSql.fence(lease)
      until <- OperationsSql.until(seconds)
      _ <- sql"UPDATE import_operation SET lease_until=$until WHERE id=${uuid(lease.operation)}".update.run
    yield lease.copy(expiresAt = until.toInstant)
  }

  def record(ctx: AccessContext, lease: WorkLease, file: Entry.File): F[Either[Problem, Entry.File]] = run {
    for
      _ <- owned(ctx, lease.operation)
      _ <- OperationsSql.fence(lease)
      op <- load(lease.operation)
      _ <- lift(Either.cond(op.candidate.isEmpty && op.inventory.exists {
        case SourceEntry.File(p, size, _) => p == file.path && size == file.blob.size
        case _ => false
      }, (), Problem.Conflict("checkpoint is outside the unprepared inventory")))
      previous = op.acquired.find(_.path == file.path)
      _ <- lift(Either.cond(previous.forall(_.blob == file.blob), (), Problem.Integrity("checkpoint content changed")))
      payload = JsonCodecs.encode[Entry](file)
      _ <- sql"""INSERT INTO acquired_file(operation_id,path,payload)
        VALUES(${uuid(lease.operation)},${file.path.value},$payload::jsonb) ON CONFLICT DO NOTHING""".update.run
    yield previous.getOrElse(file)
  }

  def bind(ctx: AccessContext, lease: WorkLease, candidate: CandidateId): F[Either[Problem, Unit]] = run {
    for
      _ <- owned(ctx, lease.operation)
      _ <- OperationsSql.fence(lease)
      op <- load(lease.operation)
      _ <- lift(Either.cond(op.candidate.forall(_ == candidate) &&
        op.acquired.size == op.inventory.count(_.isInstanceOf[SourceEntry.File]), (), Problem.Conflict("incomplete or already bound import")))
      candidateManifest <- sql"SELECT manifest_id FROM candidate WHERE id=${uuid(candidate)} AND dataset_id=${uuid(op.dataset)} AND base IS NULL"
        .query[String].option.flatMap(v => lift(v.toRight(Problem.Conflict("candidate does not belong to this import"))))
      expected <- lift(Manifest.from(op.inventory.collect { case SourceEntry.Directory(p) => Entry.Directory(p) } ++ op.acquired))
      _ <- lift(Either.cond(expected.id.value.value == candidateManifest, (), Problem.Integrity("candidate differs from verified inventory")))
      _ <- sql"UPDATE import_operation SET candidate_id=${uuid(candidate)} WHERE id=${uuid(lease.operation)}".update.run
    yield ()
  }

  def finish(ctx: AccessContext, lease: WorkLease, receipt: AcceptanceReceipt): F[Either[Problem, Unit]] = run {
    for
      _ <- owned(ctx, lease.operation)
      _ <- OperationsSql.fence(lease)
      op <- load(lease.operation)
      exists <- sql"""SELECT EXISTS(SELECT 1 FROM acceptance_receipt WHERE dataset_id=${uuid(op.dataset)}
        AND principal=${uuid(ctx.principal)} AND candidate_id=${uuid(receipt.candidate)}
        AND release_id=${uuid(receipt.release.release)} AND idempotency_key=${receipt.key.value})""".query[Boolean].unique
      _ <- lift(Either.cond(exists && receipt.release.dataset == op.dataset && op.candidate.contains(receipt.candidate), (),
        Problem.Integrity("receipt does not complete this operation")))
      payload = JsonCodecs.encode(receipt)
      _ <- sql"""UPDATE import_operation SET receipt=$payload::jsonb,lease_token=NULL,lease_until=NULL
        WHERE id=${uuid(lease.operation)}""".update.run
    yield ()
  }

  def surrender(ctx: AccessContext, lease: WorkLease): F[Unit] =
    run(owned(ctx, lease.operation) *> sql"""UPDATE import_operation SET lease_token=NULL,lease_until=NULL
      WHERE id=${uuid(lease.operation)} AND lease_token=${uuid(lease.token)}""".update.run).void
