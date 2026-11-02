package sanctity.app

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import sanctity.application.{CurrentAuthority, Failure}
import sanctity.kernel.Problem
import sanctity.objects.ManagedProcess
import java.nio.file.Files
import scala.concurrent.duration.*

final class ContinuousAuthoritySuite extends FunSuite:
  private def denied: IO[Unit] = IO.raiseError(Failure(Problem.Denied))

  test("controlled clock: revocation cancels execution at the next heartbeat") {
    TestControl.executeEmbed(for
      allowed <- Ref.of[IO, Boolean](true)
      checks <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
      stopped <- Ref.of[IO, Boolean](false)
      authorize = IO.monotonic.flatMap(t => checks.update(_ :+ t)) *> allowed.get.flatMap(if _ then IO.unit else denied)
      fiber <- CurrentAuthority.during(authorize)(IO.never[Unit].onCancel(stopped.set(true))).attempt.start
      _ <- IO.sleep(250.millis) *> allowed.set(false)
      result <- fiber.joinWithNever
      at <- IO.monotonic
      times <- checks.get
      canceled <- stopped.get
      _ = assertEquals(result, Left(Failure(Problem.Denied)))
      _ = assertEquals(at, 1.second)
      _ = assertEquals(times, Vector(0.seconds, 1.second))
      _ = assert(canceled)
    yield ()).unsafeRunSync()
  }

  test("controlled clock: unavailable renewal cannot extend the last five-second permit") {
    TestControl.executeEmbed(for
      checks <- Ref.of[IO, Int](0)
      stopped <- Ref.of[IO, Boolean](false)
      authorize = checks.getAndUpdate(_ + 1).flatMap(n => if n < 2 then IO.unit else IO.never[Unit])
      result <- CurrentAuthority.during(authorize)(IO.never[Unit].onCancel(stopped.set(true))).attempt
      at <- IO.monotonic
      canceled <- stopped.get
      _ = assertEquals(at, 6.seconds) // Last admitted check began at t=1.
      _ = assert(result.left.toOption.exists { case Failure(_: Problem.Operational) => true; case _ => false })
      _ = assert(canceled)
    yield ()).unsafeRunSync()
  }

  test("controlled clock: denied initial admission starts no work; successful work releases its monitor") {
    TestControl.executeEmbed(for
      started <- Ref.of[IO, Boolean](false)
      refused <- CurrentAuthority.during(denied)(started.set(true)).attempt
      ran <- started.get
      checks <- Ref.of[IO, Int](0)
      result <- CurrentAuthority.during(checks.update(_ + 1))(IO.sleep(2500.millis).as(42))
      before <- checks.get
      _ <- IO.sleep(10.seconds)
      after <- checks.get
      _ = assert(refused.isLeft && !ran)
      _ = assertEquals(result, 42)
      _ = assertEquals(before, 3)
      _ = assertEquals(after, before)
    yield ()).unsafeRunSync()
  }

  test("oversized upstream chunks are split; revocation admits no further bytes") {
    (for
      allowed <- Ref.of[IO, Boolean](true)
      received <- Ref.of[IO, Long](0)
      authorize = allowed.get.flatMap(if _ then IO.unit else denied)
      result <- CurrentAuthority.bytes(Stream.emits(Array.fill[Byte](200000)(1)).covary[IO], authorize)
        .chunks.evalMap(c => received.update(_ + c.size) *> allowed.set(false)).compile.drain.attempt
      count <- received.get
      _ = assertEquals(count, 65536L)
      _ = assert(result.left.toOption.exists { case Failure(Problem.Denied) => true; case _ => false })
    yield ()).unsafeRunSync()
  }

  test("real external process is killed when its execution authority is revoked") {
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-heartbeat-")))(TestFiles.remove).use { root =>
      val pidFile = root.resolve("pid")
      def ready: IO[Unit] = IO.blocking(Files.exists(pidFile)).flatMap(if _ then IO.unit else IO.sleep(10.millis) *> ready)
      for
        allowed <- Ref.of[IO, Boolean](true)
        authorize = allowed.get.flatMap(if _ then IO.unit else denied)
        worker <- CurrentAuthority.during(authorize)(ManagedProcess.run[IO](Vector("/usr/bin/python3", "-c",
          "import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(30)", pidFile.toString), root)).attempt.start
        _ <- ready.timeout(5.seconds)
        pid <- IO.blocking(Files.readString(pidFile).toLong)
        revoked <- IO.monotonic
        _ <- allowed.set(false)
        result <- worker.joinWithNever.timeout(5.seconds).guarantee(worker.cancel)
        stopped <- IO.monotonic
        alive <- IO.blocking(java.lang.ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false))
        _ = assert(result.isLeft && !alive)
        _ = assert(stopped - revoked <= CurrentAuthority.ExecutionBound)
      yield ()
    }.unsafeRunSync()
  }

  test("controlled clock: uninterruptible authority lookup cannot extend a permit") {
    TestControl.executeEmbed(for
      checks <- Ref.of[IO, Int](0)
      stopped <- Ref.of[IO, Boolean](false)
      authorize = checks.getAndUpdate(_ + 1).flatMap(n => if n == 0 then IO.unit else IO.uncancelable(_ => IO.sleep(20.seconds)))
      result <- CurrentAuthority.during(authorize)(IO.never[Unit].onCancel(stopped.set(true))).attempt
      at <- IO.monotonic
      canceled <- stopped.get
      _ = assertEquals(at, 5.seconds)
      _ = assert(result.isLeft && canceled)
      _ <- IO.sleep(25.seconds)
      count <- checks.get
      _ = assertEquals(count, 2)
    yield ()).unsafeRunSync()
  }
