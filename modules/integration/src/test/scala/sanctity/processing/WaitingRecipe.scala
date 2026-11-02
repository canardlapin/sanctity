package sanctity.processing

import cats.effect.IO
import sanctity.application.Checked
import sanctity.kernel.*
import sanctity.objects.ManagedProcess
import java.nio.file.{Files, Path}

/** Real cancellable child for worker-authority tests; it never fabricates outputs. */
final class WaitingRecipe(evidence: ProcessingEvidence) extends LocalRecipe[IO]:
  def supports(contract: InputContract): Boolean = contract.valid
  private[processing] def produceTracked(ctx: AccessContext, input: ReleaseRef, workspace: Path,
      run: Option[RunId], beforeLaunch: ExecutionEvidence => IO[Unit], admit: Option[IO[Unit]] = None): IO[Either[Problem, ProcessingEvidence]] =
    Checked.attempt {
      for
        _ <- IO.blocking(Files.createDirectory(workspace))
        _ <- beforeLaunch(ExecutionEvidence(evidence.producer, evidence.request, evidence.targets))
        _ <- ManagedProcess.run[IO](Vector("/usr/bin/python3", "-c",
          "import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)",
          workspace.resolve("pid").toString), workspace)
        result <- IO.raiseError[ProcessingEvidence](new IllegalStateException("waiting fixture unexpectedly completed"))
      yield result
    }
