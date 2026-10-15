package sanctity.app

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import fs2.{Chunk, Stream}
import munit.FunSuite
import sanctity.bids.StreamingTsv
import sanctity.bids.StreamingTsv.*
import sanctity.objects.FileBlobStore
import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.zip.{CRC32, GZIPOutputStream}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class StreamingTsvSuite extends FunSuite:
  private val schema = Schema(Vector("timestamp", "x", "note"), numeric = Set("timestamp", "x"), requiredValues = Set("timestamp"))
  private def bytes(text: String): Array[Byte] = text.getBytes(UTF_8)
  private def stream(content: Array[Byte], chunk: Int = 7): Stream[IO, Byte] =
    Stream.emits(content.toVector.grouped(chunk).map(v => Chunk.array(v.toArray)).toVector).flatMap(Stream.chunk)
  private def gzip(content: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream
    val gzip = new GZIPOutputStream(out)
    gzip.write(content)
    gzip.close()
    out.toByteArray
  private def read(content: Array[Byte], compressed: Boolean = false, s: Schema = schema, limits: Limits = Limits()): Either[Issue, Report] =
    StreamingTsv.assess(stream(content, 1), compressed, s, limits).unsafeRunSync()
  private def code(result: Either[Issue, Report]): String = result.fold(_.code, r => fail("unexpected completed report: " + r))

  test("headerless projection preserves lexical numbers, missingness, UTF-8 and quoted multiline values across byte boundaries") {
    val original = bytes("1000\t-0.125000000000000000001\t\"caf\u00e9\tline\n\"\"quoted\"\"\"\r\n1001\tn/a\tmissing\n")
    for compressed <- Vector(false, true) do
      val content = if compressed then gzip(original) else original
      val report = read(content, compressed).toOption.get
      assertEquals(report.rows, 2L)
      assertEquals(report.decodedBytes, original.length.toLong)
      assertEquals(report.invalidValues, 0L)
      val output = StreamingTsv.project(stream(content, 1), compressed, schema, Set("x", "note"), false).compile.to(Array).unsafeRunSync()
      assertEquals(new String(output, UTF_8), "-0.125000000000000000001\t\"caf\u00e9\tline\n\"\"quoted\"\"\"\nn/a\tmissing\n")
  }

  test("headers have an exact declared order, and gzip generation is deterministic and fully readable") {
    val s = Schema(Vector("a", "b"), header = true)
    val source = stream(bytes("a\tb\n1\t2\n3\t4"))
    val output = StreamingTsv.project(source, false, s, Set("b"), true)
    val first = output.compile.to(Array).unsafeRunSync()
    val second = output.compile.to(Array).unsafeRunSync()
    assertEquals(first.toVector, second.toVector)
    assertEquals(read(first, true, Schema(Vector("b"), header = true)).toOption.get.rows, 2L)
    assertEquals(code(read(bytes("b\ta\n1\t2\n"), s = s)), "Header")
  }

  test("exact row, decoded byte, encoded byte, field and row-size boundaries are enforced") {
    val s = Schema(Vector("a", "b"))
    val text = bytes("ab\tcd\n")
    val limits = Limits(encodedBytes = 6, decodedBytes = 6, rows = 1, fieldBytes = 2, rowBytes = 6)
    assert(read(text, s = s, limits = limits).isRight)
    assertEquals(code(read(bytes("ab\tcd\nab\tcd\n"), s = s, limits = limits.copy(encodedBytes = 12, decodedBytes = 12))), "Rows")
    assertEquals(code(read(text, s = s, limits = limits.copy(encodedBytes = 5))), "EncodedBytes")
    assertEquals(code(read(text, s = s, limits = limits.copy(decodedBytes = 5))), "DecodedBytes")
    assertEquals(code(read(text, s = s, limits = limits.copy(fieldBytes = 1))), "FieldBytes")
    assertEquals(code(read(text, s = s, limits = limits.copy(rowBytes = 5))), "RowBytes")
    assertEquals(code(read(gzip(text), true, s, limits.copy(encodedBytes = 100, decodedBytes = 5))), "DecodedBytes")
  }

  test("width, empty fields, quotes, control bytes, bare CR and malformed UTF-8 fail explicitly") {
    val s = Schema(Vector("a", "b"))
    Vector("a\n" -> "Width", "a\tb\tc\n" -> "Width", "a\t\n" -> "Empty", "a\t\"open" -> "Quote",
      "a\tb\"c\n" -> "Quote", "a\t\"b\"c\n" -> "Quote", "a\tb\r" -> "Newline", "a\tb\rc\n" -> "Newline",
      "a\t\u0000\n" -> "Control", "" -> "Empty").foreach { (text, expected) =>
      assertEquals(code(read(bytes(text), s = s)), expected, text)
    }
    assertEquals(code(read(Array[Byte]('a'.toByte, 9, -61, 40, 10), s = s)), "Utf8")
  }

  test("numeric diagnostics are bounded, missingness is explicit, and projection cannot publish invalid values") {
    val s = Schema(Vector("onset", "duration"), numeric = Set("onset", "duration"),
      nonnegative = Set("duration"), requiredValues = Set("onset"))
    val text = bytes("n/a\t-1\nNaN\tInfinity\n1e9999999999\t0\n1e-309\tn/a\n-0.00001\t0.00\n")
    val report = read(text, s = s, limits = Limits(diagnostics = 2)).toOption.get
    assertEquals(report.rows, 5L)
    assertEquals(report.invalidValues, 6L)
    assertEquals(report.issues.map(_.code), Vector("Missing", "Nonnegative"))
    val projected = StreamingTsv.project(stream(text), false, s, Set("duration"), false).compile.drain.attempt.unsafeRunSync()
    assert(projected.left.toOption.exists(_.isInstanceOf[Rejected]))
  }

  test("GZIP CRC, size, truncation, DEFLATE corruption, trailing data and concatenated members never complete") {
    val valid = gzip(bytes("1000\t0.1\tvalid\n"))
    val crc = valid.clone(); crc(crc.length - 8) = (crc(crc.length - 8) ^ 1).toByte
    val size = valid.clone(); size(size.length - 4) = (size(size.length - 4) ^ 1).toByte
    val deflate = valid.clone(); deflate(10) = 7
    Vector(crc, size, valid.take(9), valid.dropRight(1), valid.dropRight(8), deflate, valid ++ bytes("0\n"), valid ++ valid, Array.emptyByteArray).foreach { content =>
      assertEquals(code(read(content, true)), "Gzip")
    }
    assert(read(valid, true).isRight)
    assertEquals(code(read(gzip(Array.emptyByteArray), true)), "Empty")
  }

  test("optional GZIP fields and header CRC are validated with a fixed header budget") {
    val original = gzip(bytes("1000\t0.1\tvalid\n"))
    val header = original.take(10)
    header(3) = 30 // extra + name + comment + header CRC
    val extended = header ++ Array[Byte](2, 0, 10, 20) ++ bytes("name\u0000comment\u0000")
    val crc = new CRC32; crc.update(extended)
    val check = Array((crc.getValue & 255).toByte, ((crc.getValue >> 8) & 255).toByte)
    assert(read(extended ++ check ++ original.drop(10), true).isRight)
    assertEquals(code(read(extended ++ Array[Byte](0, 0) ++ original.drop(10), true)), "Gzip")
    header(3) = 8
    assertEquals(code(read(header ++ Array.fill[Byte](16384)('a'.toByte), true)), "Gzip")
    header(3) = 32
    assertEquals(code(read(header ++ original.drop(10), true)), "Gzip")
  }

  test("source I/O failures remain operational failures and close the source") {
    (for
      closed <- Deferred[IO, Unit]
      error = new IOException("source failed")
      source = (stream(bytes("1000\t0.1\tvalid\n")) ++ Stream.raiseError[IO](error)).onFinalize(closed.complete(()).void)
      result <- StreamingTsv.assess(source, false, schema).attempt
      _ = assertEquals(result.left.toOption, Some(error))
      done <- closed.tryGet
      _ = assert(done.nonEmpty)
    yield ()).unsafeRunSync()
  }

  test("duration expiry is a refusal and closes the source") {
    (for
      closed <- Deferred[IO, Unit]
      source = Stream.eval(IO.never[Byte]).onFinalize(closed.complete(()).void)
      result <- StreamingTsv.assess(source, false, schema, Limits(duration = 50.millis))
      _ = assertEquals(code(result), "Duration")
      done <- closed.tryGet
      _ = assert(done.nonEmpty)
    yield ()).unsafeRunSync()
  }

  test("late corruption and cancellation remove staged generated blobs without a successful result") {
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-streaming-store-")))(TestFiles.remove).use { root =>
      for
        store <- FileBlobStore.open[IO](root)
        good = gzip(bytes("1000\t0.1\tvalid\n"))
        bad = good.dropRight(1)
        result <- store.put(StreamingTsv.project(stream(bad), true, schema, Set("x"), true)).attempt
        _ = assert(result.isLeft)
        _ <- IO.blocking {
          val files = Files.list(root.resolve("staging")); try assertEquals(files.count(), 0L) finally files.close()
          val objects = Files.walk(root.resolve("objects")); try assert(!objects.iterator().asScala.exists(Files.isRegularFile(_))) finally objects.close()
        }
        entered <- Deferred[IO, Unit]
        closed <- Deferred[IO, Unit]
        source = (stream(bytes("1000\t0.1\tvalid\n")) ++ Stream.eval(entered.complete(())).drain ++ Stream.eval(IO.never[Byte]))
          .onFinalize(closed.complete(()).void)
        fiber <- store.put(StreamingTsv.project(source, false, schema, Set("x"), false)).start
        _ <- entered.get
        start <- IO.monotonic
        _ <- fiber.cancel.timeout(2.seconds)
        stop <- IO.monotonic
        _ = assert(stop - start < 2.seconds)
        done <- closed.tryGet
        _ = assert(done.nonEmpty)
        _ <- IO.blocking {
          val files = Files.list(root.resolve("staging")); try assertEquals(files.count(), 0L) finally files.close()
        }
      yield ()
    }.unsafeRunSync()
  }

  test("schema and mask refusals happen before reading any input") {
    val source = Stream.raiseError[IO](new IOException("must not read"))
    val report = StreamingTsv.assess(source, false, Schema(Vector("a", "a"))).unsafeRunSync()
    assertEquals(code(report), "Schema")
    val output = StreamingTsv.project(source, false, schema, Set("unknown"), true).compile.drain.attempt.unsafeRunSync()
    assert(output.left.toOption.exists(_.isInstanceOf[Rejected]))
  }
