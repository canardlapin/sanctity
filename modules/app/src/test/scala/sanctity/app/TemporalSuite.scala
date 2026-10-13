package sanctity.app

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.MriViewCompiler
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import sanctity.processing.*
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

abstract class CatalogTemporalSuite extends FunSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def raw(s: RepositorySystem) = new NativeRepository[IO](s.catalog, s.store, new PayloadBidsInterpreter[IO](s.store), IdSource.random[IO])
  private def derivatives(s: RepositorySystem) = new NativeRepository[IO](s.catalog, s.store, new TemporalDerivativeInterpreter[IO](s.store), IdSource.random[IO])
  private def submissions(s: RepositorySystem) = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives(s), IdSource.random[IO])
  private def producer(s: RepositorySystem) = new TemporalProducer[IO](s.catalog, s.store, raw(s), IdSource.random[IO], TemporalFixture.python)
  private def inputs(s: RepositorySystem) = new ProcessingInputs[IO](s.catalog, raw(s),
    new ScientificViews[IO](s.catalog, s.store, raw(s), new MriViewCompiler, IdSource.random[IO]), new TemporalInputPlanner)
  private def source(s: RepositorySystem, root: Path): IO[Release] =
    for
      _ <- TemporalFixture.install(root)
      preview <- checked(raw(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.findings.toString)
      receipt <- checked(raw(s).accept(s.context, preview.candidate.id, key("temporal-source")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def bundle(s: RepositorySystem, ctx: AccessContext, source: Release): IO[Release] =
    for
      selection <- checked(s.catalog.select(ctx, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
        ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
      destination <- raw(s).create(ctx, s.dataset.custodian)
      prepared <- checked(inputs(s).preparePrivate(ctx, source.ref, selection.id, destination.id,
        DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), TemporalStatistics.Contract))
      receipt <- checked(raw(s).accept(ctx, prepared.candidate.id, key("temporal-bundle")))
      release <- checked(s.catalog.release(ctx, receipt.release))
    yield release
  private def collect(s: RepositorySystem, ctx: AccessContext, draft: Draft, evidence: ProcessingEvidence): IO[Preview] =
    evidence.expected.traverse_(f => checked(submissions(s).collectStaged(ctx, draft.id, f.path))) *> checked(submissions(s).prepare(ctx, draft.id))

  test("temporal maps retain private exact inputs, recover collection, query observations and round-trip") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        researcher <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        grant <- checked(s.catalog.grant(s.context, original.ref.dataset, researcher.principal, Some(original.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        input <- bundle(s, researcher, original)
        destination <- raw(s).create(researcher, s.dataset.custodian)
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, TemporalStatistics.Contract)
        runs = new LocalProcessingRuns[IO](s.catalog, s.store, producer(s))
        evidence <- checked(runs.execute(researcher, spec, key("temporal-run"), root.resolve("run")))
        run <- checked(s.catalog.readRun(researcher, evidence.invocation))
        _ = assertEquals(run.status, RunStatus.Completed)
        _ <- evidence.expected.filter(_.path.value.endsWith(".nii")).traverse_ { f =>
          s.store.read(f.blob).compile.to(Array).map { bytes =>
            val h = bids4s.NiftiHeaderFacts.parse(bytes).toOption.get
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val values = Vector.tabulate(4)(i => b.getDouble(h.dataOffset.toInt + 8 * i))
            val expected = if f.path.value.contains("temporalMean") then TemporalFixture.ExpectedMeans else Vector.fill(4)(TemporalFixture.ExpectedSd)
            values.zip(expected).foreach((a, b) => assertEqualsDouble(a, b, 1e-12))
          }
        }
        draft <- checked(submissions(s).beginStaged(researcher, destination.id, evidence))
        _ <- checked(submissions(s).collectStaged(researcher, draft.id, evidence.expected.head.path))
        partial <- submissions(s).prepare(researcher, draft.id)
        _ = assert(partial.isLeft)
        failure <- submissions(s).collect(researcher, draft.id, evidence.expected.last.path, Stream.raiseError[IO](new java.io.IOException("interrupted")))
        _ = assert(failure.isLeft)
        _ <- TestFiles.remove(root.resolve("run"))
        retry <- checked(new LocalProcessingRuns[IO](s.catalog, s.store, producer(s)).execute(researcher, spec, key("temporal-run"), root.resolve("unused")))
        _ = assertEquals(retry, evidence)
        _ <- IO.blocking(assert(!Files.exists(root.resolve("unused"))))
        candidate <- collect(s, researcher, draft, evidence)
        _ = assert(candidate.candidate.assessment.admissible, candidate.candidate.assessment.findings.toString)
        accepted <- checked(derivatives(s).accept(researcher, candidate.candidate.id, key("temporal-result")))
        maps <- checked(s.catalog.select(researcher, Vector(accepted.release), ScientificQuery(artifacts = ArtifactPredicate.Suffix("statmap"))))
        _ = assertEquals(maps.hits.size, 2)
        observed = candidate.candidate.interpretation.observations.find(_.metric == TemporalStatistics.metric("mean")).get
        _ = assertEquals(observed.value, BigDecimal(23))
        coverage <- checked(new ScientificResults[IO](s.catalog).coverage(researcher, Vector(accepted.release), ObservationQuery(Vector(observed.source), observed.metric)))
        _ = assertEquals(coverage.head.results.size, 1)
        _ <- checked(derivatives(s).exportFull(researcher, accepted.release, new DirectoryExport[IO](root.resolve("export"))))
        importedDataset <- derivatives(s).create(researcher, s.dataset.custodian)
        imported <- checked(derivatives(s).importNative(researcher, importedDataset.id, new NativeDirectorySource[IO](root.resolve("export"))))
        _ = assert(imported.candidate.assessment.admissible, imported.candidate.assessment.findings.toString)
        _ = assertEquals(ContentTree.identity(imported.candidate.manifest), ContentTree.identity(candidate.candidate.manifest))
        _ = assertEquals(imported.candidate.interpretation.artifacts, candidate.candidate.interpretation.artifacts)
        _ = assert(imported.candidate.interpretation.observations.isEmpty)
        _ <- checked(s.catalog.revoke(s.context, original.ref.dataset, grant.id))
        denied <- inputs(s).materialize(researcher, input.ref, new DirectoryExport[IO](root.resolve("revoked")))
        _ = assertEquals(denied, Left(Problem.Denied))
        blockedQuery <- s.catalog.observations(researcher, Vector(accepted.release), ObservationQuery(Vector(observed.source), observed.metric))
        _ = assertEquals(blockedQuery, Left(Problem.Denied))
      yield ()
    }
  }

  test("temporal admission rejects source geometry changes, nonfinite maps and false source declarations") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        input <- bundle(s, s.context, original)
        evidence <- checked(producer(s).produce(s.context, input.ref, root.resolve("tamper")))
        _ <- Vector("geometry", "nonfinite", "source").traverse_ { variant =>
          val target = evidence.expected.find(f => if variant == "source" then f.path.value.endsWith("statmap.json") else f.path.value.endsWith("statmap.nii")).get
          for
            bytes <- s.store.read(target.blob).compile.to(Array)
            modified = if variant == "source" then new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("sub-01_task-rest_bold.nii", "sub-99_task-rest_bold.nii").getBytes(java.nio.charset.StandardCharsets.UTF_8)
              else
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if variant == "geometry" then b.putFloat(268, 999) else b.putDouble(352, Double.NaN)
                bytes
            blob <- s.store.put(Stream.emits(modified).covary[IO])
            changed = evidence.copy(expected = evidence.expected.map(f => if f.path == target.path then f.copy(blob = blob) else f))
            destination <- derivatives(s).create(s.context, s.dataset.custodian)
            draft <- checked(submissions(s).beginStaged(s.context, destination.id, changed))
            _ <- changed.expected.traverse_(f => checked(submissions(s).collectStaged(s.context, draft.id, f.path)))
            prepared <- submissions(s).prepare(s.context, draft.id)
            _ = assert(prepared.isLeft || prepared.exists(p => !p.candidate.assessment.admissible), variant)
            _ <- prepared.toOption.traverse_ { preview => derivatives(s).accept(s.context, preview.candidate.id, key("reject-" + variant)).map(r => assert(r.isLeft)) }
          yield ()
        }
      yield ()
    }
  }

  test("temporal collection enforces current authority and competing acceptance preserves the first complete release") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        researcher <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        grant <- checked(s.catalog.grant(s.context, original.ref.dataset, researcher.principal, Some(original.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        input <- bundle(s, researcher, original)
        first <- checked(producer(s).produce(researcher, input.ref, root.resolve("first")))
        second <- checked(producer(s).produce(researcher, input.ref, root.resolve("second")))
        destination <- derivatives(s).create(researcher, s.dataset.custodian)
        draft <- checked(submissions(s).beginStaged(researcher, destination.id, first))
        other <- checked(submissions(s).beginStaged(researcher, destination.id, second))
        _ <- checked(s.catalog.revoke(s.context, original.ref.dataset, grant.id))
        denied <- submissions(s).collectStaged(researcher, draft.id, first.expected.head.path)
        _ = assertEquals(denied, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, original.ref.dataset, researcher.principal, Some(original.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        a <- collect(s, researcher, draft, first)
        b <- collect(s, researcher, other, second)
        accepted <- checked(derivatives(s).accept(researcher, a.candidate.id, key("winner")))
        conflict <- derivatives(s).accept(researcher, b.candidate.id, key("loser"))
        _ = assert(conflict.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        head <- checked(s.catalog.dataset(researcher, destination.id))
        _ = assertEquals(head.head, Some(accepted.release.release))
      yield ()
    }
  }

  test("shared runner cancels temporal preparation durably and refuses a mismatched recipe") {
    fixture { (s, root) =>
      for
        original <- source(s, root)
        input <- bundle(s, s.context, original)
        destination <- derivatives(s).create(s.context, s.dataset.custodian)
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, TemporalStatistics.Contract)
        reached <- Deferred[IO, Unit]
        paused = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob) *> reached.complete(()).void *> IO.never[Unit]
        worker <- new LocalProcessingRuns[IO](s.catalog, paused, producer(s)).execute(s.context, spec, key("cancel-temporal"), root.resolve("cancel")).start
        _ <- reached.get
        _ <- worker.cancel
        record <- checked(s.catalog.startRun(s.context, spec, key("cancel-temporal")))
        _ = assertEquals(record.status, RunStatus.Interrupted)
        _ <- IO.blocking(assert(!Files.exists(root.resolve("cancel/output"))))
        mismatch <- new LocalProcessingRuns[IO](s.catalog, s.store, producer(s)).execute(s.context,
          spec.copy(recipe = sanctity.bids.MriInputPlanner.Contract), key("wrong-recipe"), root.resolve("wrong"))
        _ = assert(mismatch.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
      yield ()
    }
  }

final class TemporalSuite extends CatalogTemporalSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-temporal-")))(TestFiles.remove).use { root =>
      SyntheticFixture.create(root.resolve("source")) *> ReferenceSystem.create(root.resolve("storage")).flatMap(run(_, root))
    }.unsafeRunSync()
