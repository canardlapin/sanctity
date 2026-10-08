package sanctity.objects

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import sanctity.application.Failure
import sanctity.kernel.Problem
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final case class ProcessResult(exitCode: Int, output: Vector[Byte])

/** Argument vectors never pass through a shell. Pipe cancellation terminates the owned process. */
object ManagedProcess:
  def run[F[_]: Async](arguments: Vector[String], directory: Path,
      timeout: FiniteDuration = 10.minutes, outputLimit: Int = 4 * 1024 * 1024): F[ProcessResult] =
    val F = Async[F]
    def stop(process: Process): F[Unit] = F.blocking {
      val children = process.descendants()
      try children.iterator().asScala.toVector.reverse.foreach(_.destroyForcibly())
      finally children.close()
      if process.isAlive then
        process.destroyForcibly()
        val _ = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    }.void
    Resource.make(F.blocking {
      new ProcessBuilder(arguments.asJava).directory(directory.toFile).redirectErrorStream(true).start()
    })(process => stop(process) *> F.blocking {
      process.getInputStream.close()
      process.getErrorStream.close()
      process.getOutputStream.close()
    }).use { process =>
      // A blocking pipe read cannot be interrupted reliably by Thread.interrupt.
      // Terminating the child unblocks it before the outer resource closes the pipe.
      val read = F.blocking {
        val buffer = new Array[Byte](8192)
        val size = process.getInputStream.read(buffer)
        Option.when(size >= 0)(Chunk.array(buffer, 0, math.max(0, size)))
      }.cancelable(stop(process))
      val output = Stream.repeatEval(read).unNoneTerminate.flatMap(Stream.chunk).take(outputLimit.toLong + 1).compile.toVector
        .flatMap(bytes => if bytes.size > outputLimit then F.raiseError[Vector[Byte]](Failure(Problem.LimitExceeded("process output limit exceeded")))
          else F.pure(bytes))
      output.flatMap(bytes => F.interruptibleMany(process.waitFor()).map(code => ProcessResult(code, bytes)))
    }.timeoutTo(timeout, F.raiseError(Failure(Problem.Operational("external process timed out"))))
