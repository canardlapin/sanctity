package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import fs2.{Chunk, Stream}
import fs2.io.file.{Files as FsFiles, Path as FsPath}
import io.circe.Json
import sanctity.application.Checked
import sanctity.bids.{BuildIdentity, StreamingTsv}
import sanctity.kernel.*
import sanctity.objects.FileBlobStore
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.lang.management.ManagementFactory
import java.util.UUID
import scala.concurrent.duration.*

/** Opt-in resource probe. Inputs and expected answers are generated independently
  * by scripts/check-streaming-tables.py; ordinary tests remain small and offline.
  */
object StreamingTableProbe extends IOApp:
  def run(args: List[String]): IO[ExitCode] = args match
    case List(input, directory) => probe(Path.of(input), Path.of(directory)).as(ExitCode.Success)
    case _ => IO.raiseError(new IllegalArgumentException("StreamingTableProbe INPUT.tsv.gz OUTPUT_DIRECTORY"))

  private def right[A](v: Either[Problem, A]): IO[A] = Checked.lift[IO, A](v)
  private def probe(input: Path, directory: Path): IO[Unit] =
    val schema = StreamingTsv.Schema(Vector("timestamp", "x", "pupil"),
      numeric = Set("timestamp", "x", "pupil"), requiredValues = Set("timestamp"))
    val mask = Set("timestamp", "x")
    val row = "1234567890.000000\t-0.125000000000000000001\tn/a\n".getBytes(UTF_8)
    // A complete number of rows, through both decoder and writer code paths.
    val warmRows: Stream[IO, Byte] = Stream.chunk(Chunk.array(row)).repeat.take(row.length.toLong * 40000)
    for
      _ <- IO.blocking(Files.createDirectories(directory))
      store <- FileBlobStore.open[IO](directory.resolve("store"))
      _ <- StreamingTsv.assess(warmRows, false, schema).flatMap(r => IO.fromEither(r.left.map(StreamingTsv.Rejected.apply)))
      _ <- StreamingTsv.project(warmRows, false, schema, mask, true).compile.drain
      _ <- IO.delay(System.gc())
      _ <- IO.sleep(300.millis)
      _ <- IO.println("STREAMING_READY " + ProcessHandle.current().pid())
      _ <- IO.sleep(500.millis)
      start <- IO.monotonic
      original <- store.put(FsFiles[IO].readAll(FsPath.fromNioPath(input)))
      report <- StreamingTsv.assess(store.read(original), true, schema).flatMap(r => IO.fromEither(r.left.map(StreamingTsv.Rejected.apply)))
      _ <- IO.raiseWhen(report.invalidValues != 0)(new IllegalStateException("stress input has invalid values"))
      generated <- store.put(StreamingTsv.project(store.read(original), true, schema, mask, true))
      _ <- store.read(generated).through(FsFiles[IO].writeAll(FsPath.fromNioPath(directory.resolve("projected.tsv.gz")))).compile.drain
      from <- right(RelPath.from("samples.tsv.gz"))
      to <- right(RelPath.from("projected.tsv.gz"))
      source <- right(Manifest.from(Vector(Entry.File(from, Id[ArtifactTag](new UUID(0L, 1L)), original))))
      output <- right(Manifest.from(Vector(Entry.File(to, Id[ArtifactTag](new UUID(0L, 2L)), generated))))
      meaning <- right(Interpretation.from(source.id,
        SemanticProfile("streaming-table-probe-v1", BuildIdentity.bids4s, BuildIdentity.sanctity, "Bounded table mechanism; no modality qualification"), Vector.empty))
      receipt <- right(ProjectionReceipt.from(source, meaning.id, output,
        Vector(ProjectionStep(to, "project-table", Vector(CheckInput("table", from, original)))),
        Digest.framed("sanctity.streaming-probe.v1", schema.columns ++ mask.toVector.sorted)))
      stop <- IO.monotonic
      result = Json.obj(
        "probe" -> Json.fromString("streaming-table-probe-v1"), "tableRule" -> Json.fromString(StreamingTsv.Version),
        "java" -> Json.fromString(System.getProperty("java.runtime.version")), "build" -> Json.fromString(BuildIdentity.sanctity),
        "availableProcessors" -> Json.fromInt(Runtime.getRuntime.availableProcessors()),
        "heapCommittedBytes" -> Json.fromLong(ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getCommitted),
        "heapUsedBytes" -> Json.fromLong(ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed),
        "rows" -> Json.fromLong(report.rows), "decodedBytes" -> Json.fromLong(report.decodedBytes),
        "invalidValues" -> Json.fromLong(report.invalidValues), "seconds" -> Json.fromDoubleOrNull((stop - start).toNanos.toDouble / 1e9),
        "inputSha256" -> Json.fromString(original.digest.value), "inputBytes" -> Json.fromLong(original.size),
        "outputSha256" -> Json.fromString(generated.digest.value), "outputBytes" -> Json.fromLong(generated.size),
        "receipt" -> Json.fromString(receipt.id.value), "receiptMatchesOutput" -> Json.fromBoolean(receipt.matches(output)))
      _ <- IO.blocking(Files.writeString(directory.resolve("probe.json"), result.spaces2 + "\n"))
      _ <- IO.println("STREAMING_DONE " + directory.resolve("probe.json"))
    yield ()
