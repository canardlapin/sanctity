package sanctity.app

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import sanctity.application.BlobStore
import sanctity.bids.{BidsInterpreter, MriHeaderChecks}
import sanctity.kernel.*
import java.util.UUID

final class MriHeaderChecksSuite extends FunSuite:
  private def right[A](v: Either[Problem, A]): A = v.fold(p => fail(p.message), identity)
  private def path(s: String) = right(RelPath.from(s))
  private val imagePath = path("sub-01/func/sub-01_task-rest_bold.nii")
  private val header = DwiTestFiles.nifti() ++ Array.fill[Byte](180)(0)
  private val image: Entry.File = Entry.File(imagePath, Id[ArtifactTag](new UUID(0, 1)),
    right(BlobRef.from(Digest.bytes(header), 100L * 1024 * 1024 * 1024)))
  private val manifest = right(Manifest.from(Vector(Entry.Directory(path("sub-01")), Entry.Directory(path("sub-01/func")), image)))
  private val metadata = Vector(ArtifactMetadata(imagePath, Map.empty, Vector.empty, None))
  private val facts = Vector(ArtifactFacts(imagePath, "sub-01", None, "bold", "nii", Map("sub" -> "01", "task" -> "rest")))
  private val interpretation = right(Interpretation.from(manifest.id, SemanticProfile("test", "b", "s", "c"), metadata, facts,
    queryVersion = Some("scientific-query-v1")))
  private def store(readBytes: BlobRef => Stream[IO, Byte]): BlobStore[IO] = new BlobStore[IO]:
    def put(bytes: Stream[IO, Byte]): IO[BlobRef] = IO.raiseError(new UnsupportedOperationException)
    def read(blob: BlobRef): Stream[IO, Byte] = readBytes(blob)
    def verify(blob: BlobRef): IO[Unit] = IO.unit

  test("100 GiB declared arrays use bounded prefixes and close image resources") {
    (for
      released <- Ref.of[IO, Int](0)
      source = store(_ => (Stream.emits(header).covary[IO] ++ Stream.raiseError[IO](new IllegalStateException("read beyond prefix")))
        .onFinalize(released.update(_ + 1)))
      result <- new MriHeaderChecks[IO](source).inspect(manifest, interpretation)
      closed <- released.get
      _ = assertEquals(closed, 1)
      _ = assertEquals(result._1.head.inputs, Vector(CheckInput("image", imagePath, image.blob)))
      _ = assert(!result._1.head.findings.exists(_.severity == Severity.Error))
      _ = assert(result._2(imagePath).isRight)
    yield ()).unsafeRunSync()
  }

  test("invalid raw transform values become evidence and findings without numeric encoding exceptions") {
    val invalid = header.clone()
    java.nio.ByteBuffer.wrap(invalid).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(252, 1.toShort).putFloat(256, Float.NaN)
    val result = new MriHeaderChecks[IO](store(_ => Stream.emits(invalid))).inspect(manifest, interpretation).unsafeRunSync()._1.head
    assert(result.findings.exists(_.code == "MriQuaternion"))
    assert(result.facts("quaternion").toString.contains("NaN"))
  }

  test("operational failure and cancellation release prefix streams") {
    (for
      released <- Ref.of[IO, Int](0)
      error = new java.io.IOException("image unavailable")
      result <- new MriHeaderChecks[IO](store(_ => Stream.raiseError[IO](error).onFinalize(released.update(_ + 1))))
        .inspect(manifest, interpretation).attempt
      _ = assertEquals(result.left.toOption, Some(error))
      entered <- Deferred[IO, Unit]
      source = store(_ => (Stream.eval(entered.complete(())).drain ++ Stream.eval(IO.never[Byte])).onFinalize(released.update(_ + 1)))
      fiber <- new MriHeaderChecks[IO](source).inspect(manifest, interpretation).start
      _ <- entered.get
      _ <- fiber.cancel
      count <- released.get
      _ = assertEquals(count, 2)
    yield ()).unsafeRunSync()
  }

  test("preparation shares one image prefix between MRI and event assessments") {
    val texts = Vector("dataset_description.json" -> """{"Name":"Prefix sharing","BIDSVersion":"1.11.1"}""",
      "task-rest_bold.json" -> """{"TaskName":"rest","RepetitionTime":2}""",
      "task-rest_events.tsv" -> "onset\tduration\n0\t1\n")
    val files = texts.zipWithIndex.map { case ((name, text), i) =>
      val bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      Entry.File(path(name), Id[ArtifactTag](new UUID(0, i.toLong + 2)), right(BlobRef.from(Digest.bytes(bytes), bytes.length.toLong))) -> bytes
    }
    val all = right(Manifest.from(manifest.entries ++ files.map(_._1)))
    val content = files.map((f, bytes) => f.blob -> bytes).toMap
    (for
      reads <- Ref.of[IO, Int](0)
      source = store { blob =>
        if blob == image.blob then Stream.eval(reads.update(_ + 1)).drain ++ Stream.emits(header)
        else Stream.emits(content(blob))
      }
      result <- new BidsInterpreter[IO](source).prepare(all)
      count <- reads.get
      _ = assertEquals(count, 1)
      _ = assert(result._2.admissible, result._2.errors.toString)
      _ = assertEquals(result._2.checks.map(_.target).distinct, Vector(imagePath))
      _ = assertEquals(result._2.checks.size, 2)
    yield ()).unsafeRunSync()
  }
