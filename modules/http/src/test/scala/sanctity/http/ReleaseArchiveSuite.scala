package sanctity.http

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import sanctity.kernel.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.zip.{ZipException, ZipFile}
import scala.jdk.CollectionConverters.*

/** SHARE2: the archive of one release holds exactly the released tree, verifies after unpacking, and is never a valid
  * archive when reading stopped part-way.
  */
final class ReleaseArchiveSuite extends FunSuite:
  private def path(s: String): RelPath = RelPath.from(s).toOption.get
  private val contents: Map[String, Array[Byte]] = Map(
    "dataset_description.json" -> """{"Name":"Archive fixture","BIDSVersion":"1.10.0"}""".getBytes(UTF_8),
    "README" -> "Synthetic; no human data.\n".getBytes(UTF_8),
    "sub-01/anat/sub-01_T1w.nii" -> Array.tabulate[Byte](200 * 1024)(i => (i * 31 % 251).toByte),
    "sub-01/beh/café notes.tsv" -> "onset\tnote\n0\tfirst\n".getBytes(UTF_8))
  private val directories = Vector("sub-01", "sub-01/anat", "sub-01/beh", "sourcedata", "sourcedata/empty")
  private val manifest: Manifest = Manifest.from(directories.map(d => Entry.Directory(path(d))) ++ contents.toVector.map((name, bytes) =>
    Entry.File(path(name), Id[ArtifactTag](UUID.nameUUIDFromBytes(name.getBytes(UTF_8))), BlobRef.from(Digest.bytes(bytes), bytes.length.toLong).toOption.get))).fold(p => fail(p.toString), identity)
  private val acceptedAt = Instant.parse("2026-11-19T12:00:00Z")
  private val root = "sanctity-00000000-11111111"
  private def read(file: Entry.File): Stream[IO, Byte] = Stream.emits(contents(file.path.value)).covary[IO].chunkLimit(4096).unchunks
  private def archive(reader: Entry.File => Stream[IO, Byte] = read): Stream[IO, Byte] = ReleaseArchive.zip(root, manifest, acceptedAt, reader)
  private def opened[A](bytes: Array[Byte])(use: ZipFile => A): A =
    val file = Files.createTempFile("sanctity-archive-", ".zip")
    try
      Files.write(file, bytes)
      val zip = new ZipFile(file.toFile)
      try use(zip) finally zip.close()
    finally Files.deleteIfExists(file)

  test("the archive holds the released files and directories, including empty ones, and its checksum list verifies") {
    val bytes = archive().compile.to(Array).unsafeRunSync()
    opened(bytes) { zip =>
      val names = zip.entries().asScala.map(_.getName).toVector
      val expected = Vector("SHA256SUMS.txt", s"$root/") ++ directories.map(d => s"$root/$d/") ++ contents.keys.map(n => s"$root/$n")
      assertEquals(names.toSet, expected.toSet)
      assertEquals(names.size, expected.size)
      contents.foreach { (name, original) =>
        assert(java.util.Arrays.equals(zip.getInputStream(zip.getEntry(s"$root/$name")).readAllBytes(), original), name)
      }
      // The list is what `shasum -a 256 -c` reads: digest, two spaces, path. Recompute every digest from the unpacked bytes.
      val listed = new String(zip.getInputStream(zip.getEntry("SHA256SUMS.txt")).readAllBytes(), UTF_8).linesIterator.toVector
      assertEquals(listed, contents.keys.toVector.sorted.map(n => s"${Digest.bytes(contents(n)).value}  $root/$n"))
      listed.foreach { line =>
        val Array(digest, name) = line.split("  ", 2)
        assertEquals(Digest.bytes(zip.getInputStream(zip.getEntry(name)).readAllBytes()).value, digest, name)
      }
    }
  }

  test("the same release always produces the same bytes, whatever the server's time zone") {
    val saved = java.util.TimeZone.getDefault
    try
      val first = archive().compile.to(Array).unsafeRunSync()
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
      val second = archive().compile.to(Array).unsafeRunSync()
      assert(java.util.Arrays.equals(first, second))
    finally java.util.TimeZone.setDefault(saved)
  }

  test("when reading stops part-way the stream fails and what was sent is not a valid archive") {
    val stopped = new RuntimeException("access ended")
    // The second file in archive order fails after its first chunk, as a revoked download does.
    val failing = contents.keys.toVector.sorted.apply(1)
    def reader(file: Entry.File): Stream[IO, Byte] =
      if file.path.value == failing then read(file).take(1024) ++ Stream.raiseError[IO](stopped) else read(file)
    val (outcome, sent) = (for
      seen <- Ref.of[IO, Vector[Byte]](Vector.empty)
      result <- archive(reader).chunks.evalMap(c => seen.update(_ ++ c.toVector)).compile.drain.attempt
      bytes <- seen.get
    yield result -> bytes.toArray).unsafeRunSync()
    assertEquals(outcome.left.toOption.map(_.getMessage), Some("access ended"))
    intercept[ZipException](opened(sent)(_.size()))
  }
