package sanctity.app

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.{Chunk, Stream}
import munit.FunSuite
import sanctity.application.BlobStore
import sanctity.bids.EventContentChecks
import sanctity.kernel.*
import java.nio.charset.StandardCharsets
import java.util.UUID

final class EventContentChecksSuite extends FunSuite:
  private def right[A](v: Either[Problem, A]): A = v.fold(p => fail(p.message), identity)
  private def path(s: String): RelPath = right(RelPath.from(s))
  private val text = "onset\tduration\n0\t1\n".getBytes(StandardCharsets.UTF_8)
  private val image = DwiTestFiles.nifti() ++ Array.fill[Byte](180)(0)

  private def assess(table: Array[Byte] = text, size: Option[Long] = None, images: Int = 1)
      (sourceRead: (String, Array[Byte]) => Stream[IO, Byte]): IO[Vector[ContentCheck]] =
    val tablePath = path("task-test_events.tsv")
    val imagePaths = Vector.tabulate(images)(i => path(s"sub-${i + 1}_task-test_bold.nii"))
    val contents = Vector(tablePath -> table) ++ imagePaths.map(_ -> image)
    val entries = contents.zipWithIndex.map { case ((p, bytes), i) =>
      val length = if p == tablePath then size.getOrElse(bytes.length.toLong) else 100L * 1024 * 1024 * 1024
      Entry.File(p, Id[ArtifactTag](new UUID(0, i.toLong + 1)),
        right(BlobRef.from(Digest.bytes(bytes), length)))
    }
    val manifest = right(Manifest.from(entries))
    val fields = Map("RepetitionTime" -> EffectiveField(MetadataValue.Number(2), tablePath, Vector.empty))
    val metadata = entries.map(f => ArtifactMetadata(f.path, if f.path == tablePath then Map.empty else fields, Vector.empty, None))
    val links = imagePaths.map { p => ScientificRelationship(p, "bids.task-events", Vector(tablePath), None,
      Vector(RelationshipEvidence.CompanionSelection(p, tablePath, "test-v1", None, Map.empty, Vector(tablePath)))) }
    val graph = right(RelationshipGraph.from("test-v1", links, metadata, 2))
    val interpretation = right(Interpretation.from(manifest.id, SemanticProfile("test", "b", "s", "c"), metadata, relationships = Some(graph)))
    val blobs = manifest.files.map(f => f.blob -> (f.path.value, if f.path == tablePath then table else image)).toMap
    val store = new BlobStore[IO]:
      def put(bytes: Stream[IO, Byte]): IO[BlobRef] = IO.raiseError(new UnsupportedOperationException)
      def verify(blob: BlobRef): IO[Unit] = IO.unit
      def read(blob: BlobRef): Stream[IO, Byte] =
        val (name, bytes) = blobs(blob)
        readBytes(name, bytes)
      private def readBytes(name: String, bytes: Array[Byte]): Stream[IO, Byte] = sourceRead(name, bytes)
    new EventContentChecks[IO](store).assess(manifest, interpretation)

  test("shared tables are read once; images are inspected through bounded released prefixes") {
    (for
      tables <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Int](0)
      checks <- assess(images = 2) { (name, bytes) =>
        if name.endsWith(".tsv") then Stream.eval(tables.update(_ + 1)).drain ++ Stream.chunk(Chunk.array(bytes))
        else (Stream.chunk(Chunk.array(bytes)) ++ Stream.raiseError[IO](new IllegalStateException("read beyond header")))
          .onFinalize(closed.update(_ + 1))
      }
      reads <- tables.get
      releases <- closed.get
      _ = assertEquals(reads, 1)
      _ = assertEquals(releases, 2)
      _ = assertEquals(checks.size, 2)
      _ = assert(checks.forall(_.findings.isEmpty))
    yield ()).unsafeRunSync()
  }

  test("oversize event tables are findings and their bytes are never loaded") {
    val checks = assess(size = Some(4L * 1024 * 1024 + 1)) { (name, bytes) =>
      if name.endsWith(".tsv") then Stream.raiseError[IO](new IllegalStateException("oversized table read"))
      else Stream.chunk(Chunk.array(bytes))
    }.unsafeRunSync()
    assert(checks.head.findings.exists(_.code == "EventsLimitExceeded"))
  }

  test("invalid UTF-8 and malformed image headers are content findings") {
    val checks = assess(table = Array[Byte](-61, 40)) { (name, bytes) =>
      if name.endsWith(".tsv") then Stream.chunk(Chunk.array(bytes))
      else Stream.emits(Array[Byte](0, 0, 0, 0))
    }.unsafeRunSync()
    assertEquals(checks.head.findings.filter(_.severity == Severity.Error).map(_.code).toSet, Set("EventsSyntax", "EventsImageHeader"))
  }

  test("storage errors remain operational and release event-table resources") {
    (for
      released <- Ref.of[IO, Boolean](false)
      error = new java.io.IOException("event storage failed")
      result <- assess() { (_, _) => Stream.raiseError[IO](error).onFinalize(released.set(true)) }.attempt
      closed <- released.get
      _ = assertEquals(result.left.toOption, Some(error))
      _ = assert(closed)
    yield ()).unsafeRunSync()
  }

  test("cancelling an event-table read closes it before any image is requested") {
    (for
      entered <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      fiber <- assess() { (name, _) =>
        if name.endsWith(".tsv") then
          (Stream.eval(entered.complete(())).drain ++ Stream.eval(IO.never[Byte])).onFinalize(released.complete(()).void)
        else Stream.raiseError[IO](new IllegalStateException("unexpected image read"))
      }.start
      _ <- entered.get
      _ <- fiber.cancel
      closed <- released.tryGet
      _ = assertEquals(closed, Some(()))
    yield ()).unsafeRunSync()
  }
