package sanctity.processing

import sanctity.kernel.*
import java.nio.file.Path

/** A trusted adapter supplies computation; the common runner owns durable state transitions. */
trait LocalRecipe[F[_]]:
  def supports(contract: InputContract): Boolean
  private[processing] def produceTracked(ctx: AccessContext, input: ReleaseRef, workspace: Path,
      run: Option[RunId], beforeLaunch: ExecutionEvidence => F[Unit]): F[Either[Problem, ProcessingEvidence]]
