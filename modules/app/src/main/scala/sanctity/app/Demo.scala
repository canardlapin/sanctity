package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import sanctity.application.Checked
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}

import java.nio.file.{Files, Path}

object Demo extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val workspace = args match
      case Nil => IO.blocking(Files.createTempDirectory("sanctity-demo-"))
      case path :: Nil => IO.blocking(Files.createDirectory(Path.of(path).toAbsolutePath))
      case _ => IO.raiseError(new IllegalArgumentException("usage: app/run [new-workspace-directory]"))
    workspace.flatMap(execute).as(ExitCode.Success)

  private def execute(root: Path): IO[Unit] =
    def path(value: String): RelPath = RelPath.from(value).fold(p => throw new IllegalArgumentException(p.message), identity)
    def key(value: String): IdempotencyKey = IdempotencyKey.from(value).fold(p => throw new IllegalArgumentException(p.message), identity)
    for
      _ <- SyntheticFixture.create(root.resolve("source"))
      system <- ReferenceSystem.create(root.resolve("storage"))
      repo = system.repository
      ctx = system.context
      imported <- repo.importNative(ctx, system.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))).flatMap(Checked.lift[IO, Preview])
      first <- repo.accept(ctx, imported.candidate.id, key("native-import")).flatMap(Checked.lift[IO, AcceptanceReceipt])
      before <- repo.explain(ctx, first.release, path(SyntheticFixture.firstImage)).flatMap(Checked.lift[IO, ArtifactMetadata])
      edited <- repo.correctMetadata(ctx, first.release, path(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson,
        "Correct shared repetition time in the synthetic example").flatMap(Checked.lift[IO, Preview])
      second <- repo.accept(ctx, edited.candidate.id, key("metadata-correction")).flatMap(Checked.lift[IO, AcceptanceReceipt])
      retry <- repo.accept(ctx, edited.candidate.id, key("metadata-correction")).flatMap(Checked.lift[IO, AcceptanceReceipt])
      _ <- IO.raiseUnless(retry == second)(new IllegalStateException("retry did not return its original receipt"))
      after <- repo.explain(ctx, second.release, path(SyntheticFixture.firstImage)).flatMap(Checked.lift[IO, ArtifactMetadata])
      local <- repo.explain(ctx, second.release, path(SyntheticFixture.secondImage)).flatMap(Checked.lift[IO, ArtifactMetadata])
      oldExport <- repo.exportFull(ctx, first.release, new DirectoryExport[IO](root.resolve("release-1"))).flatMap(Checked.lift[IO, ExportReceipt])
      newExport <- repo.exportFull(ctx, second.release, new DirectoryExport[IO](root.resolve("release-2"))).flatMap(Checked.lift[IO, ExportReceipt])
      report = Vector(
        "Sanctity native lifecycle reference demonstration",
        s"Workspace: $root",
        s"First release: ${first.release.release.render}",
        s"Second release: ${second.release.release.render}",
        s"sub-01 RepetitionTime: ${before.fields("RepetitionTime").value} -> ${after.fields("RepetitionTime").value}",
        s"sub-01 metadata origin: ${after.fields("RepetitionTime").origin.value}",
        s"sub-02 local override retained: ${local.fields("RepetitionTime").value}",
        s"Acquisitions with changed effective values: ${edited.metadataChanges.count(_.fields.exists(_.valueChanged))}",
        s"Acquisitions with changed inheritance evidence: ${edited.metadataChanges.size}",
        s"Authored files changed: ${edited.contentChanges.size}",
        s"Full exports: ${oldExport.files} and ${newExport.files} files, with explicit directories",
        "Acceptance retry returned its original receipt.",
        "Catalog: in-memory reference only; this report is not a recoverable catalog.",
        "Assessment: limited built-in profile; no complete BIDS or scientific qualification."
      ).mkString("\n") + "\n"
      _ <- IO.blocking(Files.writeString(root.resolve("demo-report.txt"), report))
      _ <- IO.println(report)
    yield ()
