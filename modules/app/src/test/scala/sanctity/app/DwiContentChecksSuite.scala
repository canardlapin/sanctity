package sanctity.app

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.{Chunk, Stream}
import munit.FunSuite
import sanctity.application.BlobStore
import sanctity.bids.DwiContentChecks
import sanctity.kernel.*
import java.util.UUID

final class DwiContentChecksSuite extends FunSuite:
  private def right[A](v: Either[Problem, A]): A = v.fold(p => fail(p.message), identity)
  private def path(s: String): RelPath = right(RelPath.from(s))
  private def blob(bytes: Array[Byte], size: Option[Long] = None): BlobRef =
    right(BlobRef.from(Digest.bytes(bytes), size.getOrElse(bytes.length.toLong)))

  private def run(imageBytes: Array[Byte], imageSize: Long, gzip: Boolean = false,
      values: Array[Byte] = DwiTestFiles.values.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      (imageRead: BlobRef => Stream[IO, Byte]): IO[Vector[ContentCheck]] =
    val image = path(if gzip then "image.nii.gz" else "image.nii")
    val bp = path("dwi.bval")
    val vp = path("dwi.bvec")
    val vectors = DwiTestFiles.vectors.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val imageBlob = blob(imageBytes, Some(imageSize))
    val b = blob(values)
    val v = blob(vectors)
    val files = Vector(image -> imageBlob, bp -> b, vp -> v)
    val manifest = right(Manifest.from(files.zipWithIndex.map { case ((p, ref), i) =>
      Entry.File(p, Id[ArtifactTag](new UUID(0, i.toLong + 1)), ref)
    }))
    val artifacts = files.map((p, _) => ArtifactMetadata(p, Map.empty, Vector.empty, None))
    val evidence = Vector(bp, vp).map(p =>
      RelationshipEvidence.CompanionSelection(image, p, "test-companion-v1", None, Map.empty, Vector(p)))
    val relation = ScientificRelationship(image, "bids.dwi-gradients", Vector(bp, vp), None, evidence)
    val graph = right(RelationshipGraph.from("test-relations-v1", Vector(relation), artifacts, 2))
    val interpretation = right(Interpretation.from(manifest.id, SemanticProfile("test", "b", "s", "c"),
      artifacts, relationships = Some(graph)))
    val store = new BlobStore[IO]:
      def put(bytes: Stream[IO, Byte]): IO[BlobRef] = IO.raiseError(new UnsupportedOperationException)
      def verify(ref: BlobRef): IO[Unit] = IO.unit
      def read(ref: BlobRef): Stream[IO, Byte] =
        if ref == imageBlob then imageRead(ref)
        else Stream.chunk(Chunk.array(if ref == b then values else vectors))
    new DwiContentChecks[IO](store).assess(manifest, interpretation)

  test("uncompressed inspection consumes at most 540 bytes and releases its stream") {
    (for
      released <- Ref.of[IO, Boolean](false)
      bytes = DwiTestFiles.nifti() ++ Array.fill[Byte](180)(0)
      checks <- run(bytes, 100L * 1024 * 1024 * 1024) { _ =>
        (Stream.chunk(Chunk.array(bytes)) ++ Stream.raiseError[IO](new IllegalStateException("read past header")))
          .onFinalize(released.set(true))
      }
      closed <- released.get
      _ = assert(closed)
      _ = assertEquals(checks.head.findings, Vector.empty)
      _ = assertEquals(checks.head.facts("volumeCount"), MetadataValue.Number(2))
    yield ()).unsafeRunSync()
  }

  test("an oversized gzip filename stops at the compressed-prefix budget") {
    val prefix = Array.fill[Byte](64 * 1024)('a'.toByte)
    Array[Byte](31, -117, 8, 8, 0, 0, 0, 0, 0, 0).copyToArray(prefix)
    val checks = run(prefix, prefix.length.toLong + 1000, gzip = true) { _ =>
      Stream.chunk(Chunk.array(prefix)) ++ Stream.raiseError[IO](new IllegalStateException("read past compressed cap"))
    }.unsafeRunSync()
    assertEquals(checks.head.findings.map(_.code), Vector("DwiLimitExceeded"))
  }

  test("corrupt gzip and invalid UTF-8 are inspectable scientific findings") {
    val corrupt = Array[Byte](1, 2, 3)
    val invalid = Array[Byte](-61, 40)
    val checks = run(corrupt, 3, gzip = true, values = invalid)(_ => Stream.emits(corrupt)).unsafeRunSync()
    assertEquals(checks.head.findings.map(_.code).toSet, Set("DwiHeader", "DwiEncoding"))
    assertEquals(checks.head.inputs.size, 3)
  }

  test("a storage failure remains an operational failure and releases the stream") {
    (for
      closed <- Ref.of[IO, Boolean](false)
      error = new java.io.IOException("store unavailable")
      result <- run(DwiTestFiles.nifti(), 360)(_ =>
        Stream.raiseError[IO](error).onFinalize(closed.set(true))).attempt
      released <- closed.get
      _ = assertEquals(result.left.toOption, Some(error))
      _ = assert(released)
    yield ()).unsafeRunSync()
  }

  test("cancelling blocked header acquisition releases the underlying stream") {
    (for
      started <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      fiber <- run(DwiTestFiles.nifti(), 360)(_ =>
        (Stream.eval(started.complete(())).drain ++ Stream.eval(IO.never[Byte]))
          .onFinalize(released.complete(()).void)).start
      _ <- started.get
      _ <- fiber.cancel
      closed <- released.tryGet
      _ = assertEquals(closed, Some(()))
    yield ()).unsafeRunSync()
  }
