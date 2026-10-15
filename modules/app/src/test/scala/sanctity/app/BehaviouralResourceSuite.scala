package sanctity.app

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import sanctity.application.BlobStore
import sanctity.bids.{BehaviouralChecks, RepositoryBidsContext}
import sanctity.kernel.*
import java.nio.charset.StandardCharsets
import java.util.UUID

final class BehaviouralResourceSuite extends FunSuite:
  private def right[A](v: Either[Problem, A]): A = v.fold(p => fail(p.message), identity)
  private def p(s: String): RelPath = right(RelPath.from(s))
  private val path = p("sub-01/beh/sub-01_task-choice_beh.tsv")
  private val bytes = "response_time\n0.125\n".getBytes(StandardCharsets.UTF_8)
  private def assess(content: Array[Byte] = bytes, declared: Option[Long] = None)
      (sourceRead: BlobRef => Stream[IO, Byte]): IO[(Interpretation, Vector[ContentCheck], Vector[Finding])] =
    val file = Entry.File(path, Id[ArtifactTag](new UUID(0, 1)), right(BlobRef.from(Digest.bytes(content), declared.getOrElse(content.length.toLong))))
    val manifest = right(Manifest.from(Vector(Entry.Directory(p("sub-01")), Entry.Directory(p("sub-01/beh")), file)))
    val meaning = right(Interpretation.from(manifest.id, SemanticProfile("test", "b", "s", "c"),
      Vector(ArtifactMetadata(path, Map.empty, Vector.empty, None)),
      Vector(ArtifactFacts(path, "sub-01", None, "beh", "tsv", Map("sub" -> "01", "task" -> "choice"))), queryVersion = Some("scientific-query-v1")))
    val store = new BlobStore[IO]:
      def put(bytes: Stream[IO, Byte]): IO[BlobRef] = IO.raiseError(new UnsupportedOperationException)
      def verify(blob: BlobRef): IO[Unit] = IO.unit
      def read(blob: BlobRef): Stream[IO, Byte] = sourceRead(blob)
    new BehaviouralChecks[IO](store, RepositoryBidsContext.Behavioural).enrich(manifest, meaning)

  test("an oversized single table is a finding and its bytes are never read") {
    val (_, checks, _) = assess(declared = Some(4L * 1024 * 1024 + 1))(_ =>
      Stream.raiseError[IO](new IllegalStateException("oversized table was read"))).unsafeRunSync()
    assert(checks.head.findings.exists(_.code == "BehaviouralTable"))
  }
  test("invalid UTF-8 remains a scientific finding") {
    val bad = Array[Byte](-61, 40)
    val (_, checks, _) = assess(bad)(_ => Stream.emits(bad)).unsafeRunSync()
    assert(checks.head.findings.exists(f => f.code == "BehaviouralTable" && f.message.contains("UTF-8")))
  }
  test("substituted managed bytes remain an integrity failure") {
    val result = assess()(_ => Stream.emits("wrong".getBytes(StandardCharsets.UTF_8))).attempt.unsafeRunSync()
    assert(result.left.toOption.exists(_.isInstanceOf[sanctity.application.Failure]))
  }
  test("storage failure releases the table and never becomes scientific success") {
    (for
      released <- Ref.of[IO, Boolean](false)
      error = new java.io.IOException("table storage failed")
      result <- assess()(_ => Stream.raiseError[IO](error).onFinalize(released.set(true))).attempt
      closed <- released.get
      _ = assertEquals(result.left.toOption, Some(error))
      _ = assert(closed)
    yield ()).unsafeRunSync()
  }
  test("cancellation releases the table stream") {
    (for
      entered <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      fiber <- assess()(_ => (Stream.eval(entered.complete(())).drain ++ Stream.eval(IO.never[Byte]))
        .onFinalize(released.complete(()).void)).start
      _ <- entered.get
      _ <- fiber.cancel
      done <- released.tryGet
      _ = assertEquals(done, Some(()))
    yield ()).unsafeRunSync()
  }
