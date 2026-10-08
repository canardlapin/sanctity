package sanctity.application

import cats.effect.{Async, Clock}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import sanctity.kernel.*
import scala.concurrent.duration.*

/** Checkpoints verified files and a durable candidate. A retry retains artifact identities.
  * Source observations are compared before and after work; this is not a remote atomic snapshot.
  */
final class DurableImports[F[_]: Async](catalog: Catalog[F], journal: ImportJournal[F],
    store: BlobStore[F], repository: NativeRepository[F], ids: IdSource[F]):
  private val leaseSeconds = 60
  private def checked[A](f: F[Either[Problem, A]]): F[A] = f.flatMap(Checked.lift[F, A])

  private def inventory(source: NativeSource[F]): F[Vector[SourceEntry]] =
    source.entries.take(10001).compile.toVector.flatMap { entries =>
      Checked.lift[F, Vector[SourceEntry]](Either.cond(entries.size <= 10000 &&
        entries.map(_.path).distinct.size == entries.size, entries.sortBy(_.path.value),
        Problem.LimitExceeded("import requires at most 10000 unique source entries")))
    }

  def begin(ctx: AccessContext, dataset: DatasetId, key: IdempotencyKey, source: NativeSource[F]): F[Either[Problem, ImportOperation]] =
    Checked.attempt {
      checked(catalog.check(ctx, dataset, Action.Propose)) *>
        inventory(source).flatMap(entries => checked(journal.begin(ctx, dataset, key, source.label, entries)))
    }

  def resume(ctx: AccessContext, id: OperationId, source: NativeSource[F], publish: Boolean = false): F[Either[Problem, ImportOperation]] =
    Checked.attempt {
      checked(journal.get(ctx, id)).flatMap { initial =>
        if initial.receipt.nonEmpty then Async[F].pure(initial)
        else checked(journal.claim(ctx, id, leaseSeconds)).flatMap { lease =>
          val acquisition = for
            observed <- inventory(source)
            _ <- Checked.lift[F, Unit](Either.cond(initial.source == source.label && observed == initial.inventory,
              (), Problem.Integrity("source inventory changed; start a new operation")))
            current <- checked(journal.get(ctx, id))
            _ <- current.inventory.traverse_ {
              case SourceEntry.Directory(_) => Async[F].unit
              case file: SourceEntry.File =>
                current.acquired.find(_.path == file.path) match
                  case Some(previous) => store.verify(previous.blob)
                  case None =>
                    for
                      artifact <- ids.next[ArtifactTag]
                      blob <- store.put(source.read(file))
                      _ <- Checked.lift[F, Unit](Either.cond(blob.size == file.size, (), Problem.Integrity("source size changed")))
                      _ <- checked(journal.record(ctx, lease, Entry.File(file.path, artifact, blob)))
                    yield ()
            }
            after <- inventory(source)
            _ <- Checked.lift[F, Unit](Either.cond(after == initial.inventory, (), Problem.Integrity("source changed during acquisition")))
            acquired <- checked(journal.get(ctx, id))
            _ <- if acquired.candidate.nonEmpty then Async[F].unit else
              val directories = acquired.inventory.collect { case SourceEntry.Directory(p) => Entry.Directory(p) }
              for
                manifest <- Checked.lift[F, Manifest](Manifest.from(directories ++ acquired.acquired))
                now <- Clock[F].realTimeInstant
                draft <- checked(catalog.draft(ctx, initial.dataset, None, manifest,
                  Activity(ActivityKind.NativeImport, ctx.principal, "Resumable inventoried import", Some(initial.source), now, evidence = source.evidence, retained = source.retainedEvidence)))
                preview <- checked(repository.prepare(ctx, draft.id))
                _ <- checked(journal.bind(ctx, lease, preview.candidate.id))
              yield ()
            prepared <- checked(journal.get(ctx, id))
            _ <- if !publish then Async[F].unit else
              for
                candidate <- Checked.lift[F, CandidateId](prepared.candidate.toRight(Problem.Integrity("missing import candidate")))
                receipt <- checked(repository.accept(ctx, candidate, initial.key, Some(lease)))
                _ <- checked(journal.finish(ctx, lease, receipt))
              yield ()
            result <- checked(journal.get(ctx, id))
          yield result
          val work = if !publish then acquisition else initial.candidate match
            case None => acquisition
            case Some(candidate) => checked(catalog.receipt(ctx, candidate, initial.key)).flatMap {
              case None => acquisition
              case Some(receipt) => checked(journal.finish(ctx, lease, receipt)) *> checked(journal.get(ctx, id))
            }
          val heartbeat = (Async[F].sleep((leaseSeconds / 3).seconds) *> checked(journal.renew(ctx, lease, leaseSeconds))).foreverM[ImportOperation]
          work.race(heartbeat).map(_.merge).guarantee(journal.surrender(ctx, lease))
        }
      }
    }
