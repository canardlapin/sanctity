package sanctity.processing

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.*
import sanctity.bids.QcDerivativeFormat
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, ManagedProcess, NativeDirectorySource}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Trusted local demonstration adapter. Child receives an isolated copy and no repository credentials.
  * This is not an arbitrary-code sandbox. Successful outputs are staged before returning a receipt.
  */
final class TemporalProducer[F[_]: Async](catalog: Catalog[F], store: BlobStore[F], repository: NativeRepository[F],
    ids: IdSource[F], python: Path) extends LocalRecipe[F]:
  def supports(contract: InputContract): Boolean = contract == TemporalStatistics.Contract
  private val F = Async[F]
  private val environment = Some(Map("PATH" -> "/usr/bin:/bin", "LC_ALL" -> "C", "PYTHONNOUSERSITE" -> "1", "OPENBLAS_NUM_THREADS" -> "1", "OMP_NUM_THREADS" -> "1"))

  def produce(ctx: AccessContext, input: ReleaseRef, newWorkspace: Path): F[Either[Problem, ProcessingEvidence]] =
    produceTracked(ctx, input, newWorkspace, None, _ => F.unit)

  /** Called by the durable local runner. The callback persists exact request/software before launch. */
  private[processing] def produceTracked(ctx: AccessContext, input: ReleaseRef, newWorkspace: Path,
      run: Option[RunId], beforeLaunch: ExecutionEvidence => F[Unit], admit: Option[F[Unit]] = None): F[Either[Problem, ProcessingEvidence]] = Checked.attempt {
    val authorize = catalog.check(ctx, input.dataset, Action.ReadContent, Some(input.release)).flatMap(Checked.lift[F, Unit]) *> admit.getOrElse(F.unit)
    CurrentAuthority.during(authorize) {
      for
        _ <- catalog.check(ctx, input.dataset, Action.ReadContent, Some(input.release)).flatMap(Checked.lift[F, Unit])
        release <- catalog.release(ctx, input).flatMap(Checked.lift[F, Release])
        bundle <- Checked.lift[F, BundleEvidence](release.activity.bundle.filter(_.matches(release.manifest,
          release.interpretation, release.assessment, release.activity.projection)).toRight(Problem.Unsupported("producer requires an accepted processing bundle")))
        _ <- Checked.lift[F, Unit](Either.cond(supports(bundle.readiness.contract), (), Problem.Unsupported("requires temporal statistics input contract v1")))
        ready <- Checked.lift[F, InputReadiness](new TemporalInputPlanner().assess(release.manifest, release.interpretation, release.assessment,
          bundle.readiness.roots, bundle.readiness.contract))
        _ <- Checked.lift[F, Unit](Either.cond(ready.ready && release.manifest.files.map(f => BigInt(f.blob.size)).sum <= NiftiPayload.MaxBytes,
          (), Problem.Unsupported("input bundle lacks full payload evidence or exceeds 8 GiB materialization budget")))
        _ <- F.blocking(Files.createDirectory(newWorkspace))
        workspace = newWorkspace.toAbsolutePath
        _ <- repository.exportFull(ctx, input, new DirectoryExport[F](workspace.resolve("input"))).flatMap(Checked.lift[F, ExportReceipt])
        code <- F.blocking {
          val resource = getClass.getResourceAsStream("/temporal_statistics.py")
          try resource.readAllBytes() finally resource.close()
        }
        codeBlob <- store.put(CurrentAuthority.bytes(Stream.emits(code).covary[F], authorize))
        _ <- F.blocking(Files.write(workspace.resolve("producer.py"), code))
        executableDigest <- F.blocking {
          if !Files.isRegularFile(python) || Files.size(python) > 100L * 1024 * 1024 then
            throw Failure(Problem.Unsupported("Python executable must be a regular file below 100 MiB"))
          Digest.bytes(Files.readAllBytes(python))
        }
        version <- ManagedProcess.run[F](Vector(python.toAbsolutePath.toString, "-I", workspace.resolve("producer.py").toString, "--identity"), workspace, 30.seconds, 1024, environment)
        _ <- Checked.lift[F, Unit](Either.cond(version.exitCode == 0, (), Problem.Operational("cannot identify Python runtime")))
        producer = ProducerIdentity(TemporalStatistics.Producer, "1", codeBlob,
          new String(version.output.toArray, StandardCharsets.UTF_8).trim + ";sha256:" + executableDigest.value)
        invocation <- run.fold(ids.next[RunTag])(F.pure)
        roots = bundle.readiness.roots
        request = TemporalStatistics.request(invocation, release, producer)
        requestBytes = QcDerivativeFormat.render(request).getBytes(StandardCharsets.UTF_8)
        requestBlob <- store.put(CurrentAuthority.bytes(Stream.emits(requestBytes).covary[F], authorize))
        _ <- F.blocking(Files.write(workspace.resolve("request.json"), requestBytes))
        _ <- beforeLaunch(ExecutionEvidence(producer, requestBlob, roots))
        result <- ManagedProcess.run[F](Vector(python.toAbsolutePath.toString, "-I", workspace.resolve("producer.py").toString,
          workspace.resolve("request.json").toString, workspace.resolve("input").toString, workspace.resolve("output").toString),
          workspace, 30.minutes, 64 * 1024, environment)
        _ <- Checked.lift[F, Unit](Either.cond(result.exitCode == 0, (), Problem.Operational(s"temporal producer exited ${result.exitCode}")))
        _ <- catalog.check(ctx, input.dataset, Action.ReadContent, Some(input.release)).flatMap(Checked.lift[F, Unit])
        source = new NativeDirectorySource[F](workspace.resolve("output"))
        inventory <- source.entries.take(100).compile.toVector
        files = inventory.collect { case f: SourceEntry.File => f }
        _ <- Checked.lift[F, Unit](Either.cond(inventory.size < 100 && files.map(_.path.value).toSet == TemporalStatistics.files(roots) &&
          files.forall(_.size <= NiftiPayload.MaxBytes), (), Problem.Integrity("producer emitted unexpected output inventory")))
        outputs <- inventory.collect { case f: SourceEntry.File => f }.traverse(f => store.put(CurrentAuthority.bytes(source.read(f), authorize)).map(b => OutputFile(f.path, b)))
      yield ProcessingEvidence(invocation, input, bundle.id, producer, TemporalStatistics.Parameters, outputs.sortBy(_.path.value), roots, requestBlob)
  }
  }
