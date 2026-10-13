package sanctity.processing

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import sanctity.application.*
import sanctity.bids.{MriInputPlanner, QcDerivativeFormat}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, ManagedProcess, NativeDirectorySource}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Trusted local demonstration adapter. Child receives an isolated copy and no repository credentials.
  * This is not an arbitrary-code sandbox. Successful outputs are staged before returning a receipt.
  */
final class MriQcProducer[F[_]: Async](catalog: Catalog[F], store: BlobStore[F], repository: NativeRepository[F],
    ids: IdSource[F], python: Path) extends LocalRecipe[F]:
  def supports(contract: InputContract): Boolean = contract.name == MriInputPlanner.Contract.name && Set("1", "2", "3")(contract.version)
  private val F = Async[F]
  private val environment = Some(Map("PATH" -> "/usr/bin:/bin", "LC_ALL" -> "C", "PYTHONNOUSERSITE" -> "1"))

  def produce(ctx: AccessContext, input: ReleaseRef, newWorkspace: Path): F[Either[Problem, ProcessingEvidence]] =
    produceTracked(ctx, input, newWorkspace, None, _ => F.unit)

  /** Called by the durable local runner. The callback persists exact request/software before launch. */
  private[processing] def produceTracked(ctx: AccessContext, input: ReleaseRef, newWorkspace: Path,
      run: Option[RunId], beforeLaunch: ExecutionEvidence => F[Unit]): F[Either[Problem, ProcessingEvidence]] = Checked.attempt {
    for
      _ <- catalog.check(ctx, input.dataset, Action.ReadContent, Some(input.release)).flatMap(Checked.lift[F, Unit])
      release <- catalog.release(ctx, input).flatMap(Checked.lift[F, Release])
      bundle <- Checked.lift[F, BundleEvidence](release.activity.bundle.filter(_.matches(release.manifest,
        release.interpretation, release.assessment, release.activity.projection)).toRight(Problem.Unsupported("producer requires an accepted processing bundle")))
      _ <- Checked.lift[F, Unit](Either.cond(supports(bundle.readiness.contract), (), Problem.Unsupported("producer requires MRI header QC input contract v1, v2 or v3")))
      _ <- F.blocking(Files.createDirectory(newWorkspace))
      workspace = newWorkspace.toAbsolutePath
      _ <- repository.exportFull(ctx, input, new DirectoryExport[F](workspace.resolve("input"))).flatMap(Checked.lift[F, ExportReceipt])
      code <- F.blocking {
        val resource = getClass.getResourceAsStream("/header_qc.py")
        try resource.readAllBytes() finally resource.close()
      }
      codeBlob <- store.put(Stream.emits(code).covary[F])
      _ <- F.blocking(Files.write(workspace.resolve("producer.py"), code))
      executableDigest <- F.blocking {
        if !Files.isRegularFile(python) || Files.size(python) > 100L * 1024 * 1024 then
          throw Failure(Problem.Unsupported("Python executable must be a regular file below 100 MiB"))
        Digest.bytes(Files.readAllBytes(python))
      }
      version <- ManagedProcess.run[F](Vector(python.toAbsolutePath.toString, "--version"), workspace, 30.seconds, 1024, environment)
      _ <- Checked.lift[F, Unit](Either.cond(version.exitCode == 0, (), Problem.Operational("cannot identify Python runtime")))
      producer = ProducerIdentity(QcDerivativeFormat.Producer, QcDerivativeFormat.ProducerVersion, codeBlob,
        new String(version.output.toArray, StandardCharsets.UTF_8).trim + ";sha256:" + executableDigest.value)
      invocation <- run.fold(ids.next[RunTag])(F.pure)
      roots = bundle.readiness.roots.filter(p => release.interpretation.facts.exists(f => f.path == p && f.suffix == "bold"))
      rows <- Checked.lift[F, Vector[Json]](roots.traverse { p =>
        release.interpretation.byPath.get(p).flatMap(_.fields.get("RepetitionTime")).map(_.value) match
          case Some(MetadataValue.Number(tr)) => Right(Json.obj("path" -> Json.fromString(p.value), "tr" -> Json.fromBigDecimal(tr)))
          case _ => Left(Problem.Integrity("ready bundle lost its timing evidence"))
      })
      request = Json.obj("rows" -> Json.arr(rows*), "columns" -> Json.arr(QcDerivativeFormat.Columns.map(Json.fromString)*),
        "description" -> QcDerivativeFormat.description(invocation, release, producer, Map.empty),
        "sidecar" -> QcDerivativeFormat.sidecar(roots))
      requestBytes = QcDerivativeFormat.render(request).getBytes(StandardCharsets.UTF_8)
      requestBlob <- store.put(Stream.emits(requestBytes).covary[F])
      _ <- F.blocking(Files.write(workspace.resolve("request.json"), requestBytes))
      _ <- beforeLaunch(ExecutionEvidence(producer, requestBlob, roots))
      result <- ManagedProcess.run[F](Vector(python.toAbsolutePath.toString, "-I", workspace.resolve("producer.py").toString,
        workspace.resolve("request.json").toString, workspace.resolve("input").toString, workspace.resolve("output").toString),
        workspace, 2.minutes, 64 * 1024, environment)
      _ <- Checked.lift[F, Unit](Either.cond(result.exitCode == 0, (), Problem.Operational(s"QC producer exited ${result.exitCode}")))
      _ <- catalog.check(ctx, input.dataset, Action.ReadContent, Some(input.release)).flatMap(Checked.lift[F, Unit])
      source = new NativeDirectorySource[F](workspace.resolve("output"))
      inventory <- source.entries.take(5).compile.toVector
      _ <- Checked.lift[F, Unit](Either.cond(inventory.size == 3 && inventory.forall {
        case SourceEntry.File(p, size, _) => QcDerivativeFormat.Files(p.value) && size <= 1024 * 1024
        case _ => false
      }, (), Problem.Integrity("producer emitted unexpected output inventory")))
      outputs <- inventory.collect { case f: SourceEntry.File => f }.traverse(f => store.put(source.read(f)).map(b => OutputFile(f.path, b)))
    yield ProcessingEvidence(invocation, input, bundle.id, producer, Map.empty, outputs.sortBy(_.path.value), roots, requestBlob)
  }
