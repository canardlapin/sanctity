package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import sanctity.application.Failure
import sanctity.kernel.Problem
import sanctity.objects.ManagedProcess
import java.nio.file.Files
import scala.concurrent.duration.*

final class ProcessSuite extends FunSuite:
  test("cancelling a converter process terminates it and releases the process resource") {
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-process-test-")))(TestFiles.remove).use { root =>
      val pidFile = root.resolve("pid")
      val command = Vector("python3", "-c", "import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(30)", pidFile.toString)
      def ready: IO[Unit] = IO.blocking(Files.exists(pidFile)).flatMap(if _ then IO.unit else IO.sleep(20.millis) *> ready)
      for
        fiber <- ManagedProcess.run[IO](command, root).start
        _ <- ready.timeout(5.seconds)
        pid <- IO.blocking(Files.readString(pidFile).toLong)
        _ <- fiber.cancel
        alive <- IO.blocking(java.lang.ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false))
        _ = assert(!alive)
      yield ()
    }.unsafeRunSync()
  }

  test("converter output and runtime limits fail explicitly") {
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-process-test-")))(TestFiles.remove).use { root =>
      for
        output <- ManagedProcess.run[IO](Vector("python3", "-c", "print('x'*10000)"), root, outputLimit = 100).attempt
        _ = assert(output.left.toOption.exists { case Failure(_: Problem.LimitExceeded) => true; case _ => false })
        timeout <- ManagedProcess.run[IO](Vector("python3", "-c", "import time;time.sleep(30)"), root, timeout = 100.millis).attempt
        _ = assert(timeout.left.toOption.exists { case Failure(_: Problem.Operational) => true; case _ => false })
      yield ()
    }.unsafeRunSync()
  }
