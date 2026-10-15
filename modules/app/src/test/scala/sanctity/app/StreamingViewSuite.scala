package sanctity.app

import cats.effect.{Async, Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.StreamingTsv
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.io.IOException
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Tests the streaming application protocol, without claiming that an opaque
  * stimulus table is already a supported physiological recording.
  */
abstract class CatalogStreamingViewSuite extends FunSuite:
  override val munitTimeout: Duration = 2.minutes
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private val inputPath = p("stimuli/samples.tsv")
  private val outputPath = p("stimuli/samples.tsv.gz")
  private val description = p("dataset_description.json")
  private val schema = StreamingTsv.Schema(Vector("value", "private"), numeric = Set("value"))
  private val disclosure = DisclosureProfile(Set.empty, Set("value"))
  private val rows = 200000L
  private def checked[A](action: IO[Either[Problem, A]]): IO[A] = action.flatMap(Checked.lift[IO, A])

  private final class Compiler(mode: String = "normal") extends ViewCompiler:
    def documents(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Vector[RelPath]] =
      validate(source, selection, profile).as(Vector.empty)
    private def validate(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Unit] =
      Either.cond(selection.releases == Vector(source.ref) && source.manifest.byPath.contains(inputPath) &&
        profile.metadataFields == Set("value"), (), Problem.Unsupported("test streaming scope"))
    def plan(source: Release, selection: FrozenSelection, profile: DisclosureProfile,
        documents: Map[RelPath, String]): Either[Problem, ProjectionPlan] =
      val _ = documents
      validate(source, selection, profile).map { _ =>
        val files = source.manifest.files.map(f => f.path -> f).toMap
        ProjectionPlan(Vector(files(description)), Vector.empty,
          ProjectionEvidence(source.ref, selection.id, profile.id, "streaming-protocol-test-v1", Vector(outputPath)),
          Vector(ProjectionStep(description, "copy", Vector(CheckInput("content", description, files(description).blob))),
            ProjectionStep(outputPath, "project-table", Vector(CheckInput("table", inputPath, files(inputPath).blob)))))
      }
    override def streams[F[_]: Async](source: Release, selection: FrozenSelection, profile: DisclosureProfile,
        documents: Map[RelPath, String], read: Entry.File => Stream[F, Byte]): Either[Problem, Vector[StreamedViewFile[F]]] =
      val _ = documents
      validate(source, selection, profile).map { _ =>
        val file = source.manifest.byPath(inputPath).asInstanceOf[Entry.File]
        val bytes = if mode == "incomplete" || mode == "empty" then read(file).take(if mode == "empty" then 0 else 26) else
          StreamingTsv.project(read(file), false, schema, profile.metadataFields, true)
        Vector(StreamedViewFile(if mode == "collision" then description else outputPath, bytes))
      }

  private def source(s: RepositorySystem, root: Path): IO[Release] =
    for
      _ <- IO.blocking(Files.exists(root.resolve("source"))).flatMap(exists => if exists then TestFiles.remove(root.resolve("source")) else IO.unit)
      _ <- IO.blocking {
        val source = root.resolve("source")
        Files.createDirectories(source.resolve("stimuli"))
        Files.writeString(source.resolve(description.value), """{"Name":"Streaming protocol fixture","BIDSVersion":"1.11.2","License":"CC0"}""")
        val output = Files.newBufferedWriter(source.resolve(inputPath.value))
        try
          var n = 0L
          while n < rows do
            output.write(n.toString + ".125000000000000000001\tprivate\n")
            n += 1
        finally output.close()
      }
      preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, IdempotencyKey.from("stream-source").toOption.get))
      release <- checked(s.catalog.release(s.context, receipt.release))
      _ = assert(release.manifest.byPath(inputPath).asInstanceOf[Entry.File].blob.size > 4L * 1024 * 1024)
    yield release

  private def prepare(s: RepositorySystem, source: Release, store: BlobStore[IO], mode: String = "normal"): IO[(Dataset, Either[Problem, Preview])] =
    for
      selection <- checked(s.catalog.select(s.context, Vector(source.ref), ScientificQuery()))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      views = new ScientificViews[IO](s.catalog, store, s.repository, new Compiler(mode), IdSource.random[IO])
      result <- views.prepare(s.context, source.ref, selection.id, destination.id, disclosure)
    yield destination -> result

  private def emptyStaging(root: Path): IO[Unit] = IO.blocking {
    val files = Files.list(root.resolve("storage/staging"))
    try assertEquals(files.count(), 0L) finally files.close()
  }

  test("a source above the small-document budget streams into a final receipt, accepts, exports and reimports") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        prepared <- prepare(s, original, s.store)
        (destination, result) = prepared
        preview <- Checked.lift[IO, Preview](result)
        _ = assert(preview.candidate.assessment.admissible)
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, IdempotencyKey.from("stream-view").toOption.get))
        release <- checked(s.catalog.release(s.context, receipt.release))
        proof = release.activity.projection.get.receipt.get
        _ = assert(proof.matches(release.manifest))
        output = release.manifest.byPath(outputPath).asInstanceOf[Entry.File]
        _ = assertEquals(proof.files.find(_.step.output == outputPath).get.blob, output.blob)
        _ = assertEquals(proof.files.find(_.step.output == outputPath).get.step.inputs.head.blob,
          original.manifest.byPath(inputPath).asInstanceOf[Entry.File].blob)
        report <- StreamingTsv.assess(s.store.read(output.blob), true, StreamingTsv.Schema(Vector("value"), numeric = Set("value")))
        _ = assertEquals(report.toOption.get.rows, rows)
        _ = assertEquals(report.toOption.get.invalidValues, 0L)
        _ <- checked(s.repository.exportFull(s.context, release.ref, new DirectoryExport[IO](root.resolve("export"))))
        target <- s.repository.create(s.context, s.dataset.custodian)
        reimported <- checked(s.repository.importNative(s.context, target.id, new NativeDirectorySource[IO](root.resolve("export"))))
        _ = assertEquals(reimported.candidate.manifest.byPath(outputPath).asInstanceOf[Entry.File].blob, output.blob)
        _ = assertEquals(destination.head, None)
      yield ()
    }
  }

  test("an early-terminating compiler cannot publish a view even after put returns a blob") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        _ <- Vector("incomplete", "empty").traverse_ { mode =>
          for
            prepared <- prepare(s, original, s.store, mode)
            (destination, result) = prepared
            _ = assert(result.left.toOption.exists { case Problem.Integrity(m) => m.contains("verified EOF"); case _ => false })
            dataset <- checked(s.catalog.dataset(s.context, destination.id))
            _ = assertEquals(dataset.head, None)
            _ <- emptyStaging(root)
          yield ()
        }
      yield ()
    }
  }

  test("colliding streamed paths fail before a source read or generated write") {
    fixture { (s, root) =>
      val noIO = new BlobStore[IO]:
        def put(bytes: Stream[IO, Byte]): IO[BlobRef] = IO.raiseError(new IOException("must not write"))
        def read(blob: BlobRef): Stream[IO, Byte] = Stream.raiseError[IO](new IOException("must not read"))
        def verify(blob: BlobRef): IO[Unit] = IO.unit
      for
        original <- source(s, root)
        prepared <- prepare(s, original, noIO, "collision")
        result = prepared._2
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.InvalidManifest]))
      yield ()
    }
  }

  test("a sink failure cleans its stage and leaves the destination unpublished") {
    fixture { (s, root) =>
      val failing = new BlobStore[IO]:
        def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes ++ Stream.raiseError[IO](new IOException("injected disk full")))
        def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob)
        def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
      for
        original <- source(s, root)
        prepared <- prepare(s, original, failing)
        (destination, result) = prepared
        _ = assert(result.left.toOption.exists { case Problem.Operational(m) => m.contains("disk full"); case _ => false })
        dataset <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(dataset.head, None)
        _ <- emptyStaging(root)
      yield ()
    }
  }

  test("source revocation during generation closes the stream and leaves no private view") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        principal <- IdSource.random[IO].next[PrincipalTag]
        peer = AccessContext(principal)
        grant <- checked(s.catalog.grant(s.context, original.ref.dataset, principal, Some(original.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        selection <- checked(s.catalog.select(peer, Vector(original.ref), ScientificQuery()))
        destination <- s.repository.create(peer, s.dataset.custodian)
        entered <- Deferred[IO, Unit]
        resume <- Deferred[IO, Unit]
        closed <- Deferred[IO, Unit]
        gated = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob).chunkN(64 * 1024).zipWithIndex.evalTap { (_, n) =>
            if n == 1 then entered.complete(()).void *> resume.get else IO.unit
          }.map(_._1).unchunks.onFinalize(closed.complete(()).void)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
        views = new ScientificViews[IO](s.catalog, gated, s.repository, new Compiler, IdSource.random[IO])
        fiber <- views.preparePrivate(peer, original.ref, selection.id, destination.id, disclosure).start
        _ <- entered.get.timeout(10.seconds)
        _ <- checked(s.catalog.revoke(s.context, original.ref.dataset, grant.id))
        _ <- resume.complete(())
        result <- fiber.joinWithNever.timeout(2.seconds)
        _ = assertEquals(result, Left(Problem.Denied))
        done <- closed.tryGet
        _ = assert(done.nonEmpty)
        dataset <- checked(s.catalog.dataset(peer, destination.id))
        _ = assertEquals(dataset.head, None)
        _ <- emptyStaging(root)
      yield ()
    }
  }

  test("substituted source bytes from an adapter cannot produce a published view") {
    fixture { (s, root) =>
      val substituted = new BlobStore[IO]:
        def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
        def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob).map(b => if b == '1' then '2'.toByte else b)
        def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
      for
        original <- source(s, root)
        prepared <- prepare(s, original, substituted)
        dataset <- checked(s.catalog.dataset(s.context, prepared._1.id))
        _ = assertEquals(dataset.head, None)
        _ = assert(prepared._2.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
        _ <- emptyStaging(root)
      yield ()
    }
  }

  test("cancellation after output has reached disk removes its stage within two seconds") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        entered <- Deferred[IO, Unit]
        closed <- Deferred[IO, Unit]
        gated = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob).chunkN(64 * 1024).zipWithIndex.evalTap { (_, n) =>
            if n == 64 then entered.complete(()).void *> IO.never else IO.unit
          }.map(_._1).unchunks.onFinalize(closed.complete(()).void)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
        selection <- checked(s.catalog.select(s.context, Vector(original.ref), ScientificQuery()))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        views = new ScientificViews[IO](s.catalog, gated, s.repository, new Compiler, IdSource.random[IO])
        fiber <- views.prepare(s.context, original.ref, selection.id, destination.id, disclosure).start
        _ <- entered.get.timeout(10.seconds)
        _ <- IO.blocking {
          val files = Files.list(root.resolve("storage/staging"))
          try assert(files.anyMatch(at => Files.size(at) > 0), "expected generated bytes on disk before cancellation") finally files.close()
        }
        _ <- fiber.cancel.timeout(2.seconds)
        done <- closed.tryGet
        _ = assert(done.nonEmpty)
        dataset <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(dataset.head, None)
        _ <- emptyStaging(root)
      yield ()
    }
  }

final class StreamingViewSuite extends CatalogStreamingViewSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-streaming-view-")))(TestFiles.remove)
      .use(root => ReferenceSystem.create(root.resolve("storage")).flatMap(s => run(s, root))).unsafeRunSync()
