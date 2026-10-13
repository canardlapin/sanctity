package sanctity.processing

import cats.effect.{Async, Ref}
import cats.effect.kernel.Outcome
import cats.effect.syntax.all.*
import cats.syntax.all.*
import sanctity.application.*
import sanctity.kernel.*
import java.nio.file.Path

/** Durable single-host execution. An uncertain run requires an explicit decision; no automatic rerun. */
class LocalProcessingRuns[F[_]: Async](catalog: Catalog[F], store: BlobStore[F], producer: LocalRecipe[F]):
  private val F = Async[F]

  def execute(ctx: AccessContext, spec: RunSpec, key: IdempotencyKey, workspace: Path): F[Either[Problem, ProcessingEvidence]] = Checked.attempt {
    for
      _ <- Checked.lift[F, Unit](Either.cond(producer.supports(spec.recipe), (), Problem.Unsupported("local adapter does not support this recipe")))
      run <- catalog.startRun(ctx, spec, key).flatMap(Checked.lift[F, ProcessingRun])
      result <- run.status match
        case RunStatus.Completed => Checked.lift[F, ProcessingEvidence](run.result.toRight(Problem.Integrity("completed run lacks outputs")))
        case RunStatus.Planned =>
          for
            claimed <- catalog.advanceRun(ctx, run.id, run.generation, RunUpdate(RunStatus.Preparing)).flatMap(Checked.lift[F, ProcessingRun])
            current <- Ref.of[F, ProcessingRun](claimed)
            result <- perform(ctx, current, workspace).guaranteeCase {
              case Outcome.Succeeded(_) => F.unit
              case Outcome.Errored(_) => finish(ctx, current, RunStatus.Failed, "Local execution failed; inspect retained request and workspace")
              case Outcome.Canceled() => finish(ctx, current, RunStatus.Interrupted, "Local execution was cancelled")
            }
          yield result
        case _ => F.raiseError[ProcessingEvidence](Failure(Problem.Conflict("run is active, failed or uncertain; inspect it and use an explicit new request key to retry")))
    yield result
  }

  def interrupt(ctx: AccessContext, id: RunId, generation: Long, reason: String): F[Either[Problem, ProcessingRun]] = Checked.attempt {
    for
      current <- catalog.readRun(ctx, id).flatMap(Checked.lift[F, ProcessingRun])
      next <- catalog.advanceRun(ctx, id, generation, RunUpdate(RunStatus.Interrupted, current.execution, detail = Some(reason)))
        .flatMap(Checked.lift[F, ProcessingRun])
    yield next
  }

  private def perform(ctx: AccessContext, state: Ref[F, ProcessingRun], workspace: Path): F[ProcessingEvidence] =
    for
      initial <- state.get
      result <- producer.produceTracked(ctx, initial.spec.input, workspace, Some(initial.id), execution =>
        execution.retained.traverse_(store.verify) *> change(ctx, state, RunUpdate(RunStatus.Executing, Some(execution))).void)
        .flatMap(Checked.lift[F, ProcessingEvidence])
      _ <- result.expected.traverse_(f => store.verify(f.blob))
      current <- state.get
      _ <- change(ctx, state, RunUpdate(RunStatus.Completed, current.execution, Some(result)))
    yield result

  private def change(ctx: AccessContext, state: Ref[F, ProcessingRun], update: RunUpdate): F[ProcessingRun] =
    F.uncancelable { _ =>
      for
        current <- state.get
        next <- catalog.advanceRun(ctx, current.id, current.generation, update).flatMap(Checked.lift[F, ProcessingRun])
        _ <- state.set(next)
      yield next
    }

  private def finish(ctx: AccessContext, state: Ref[F, ProcessingRun], status: RunStatus, reason: String): F[Unit] =
    state.get.flatMap(r => catalog.advanceRun(ctx, r.id, r.generation, RunUpdate(status, r.execution, detail = Some(reason))).void)

