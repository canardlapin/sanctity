package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import sanctity.application.{Checked, IdSource}
import sanctity.dicom.{ConverterPin, DicomImport, DicomMapping}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

object DicomDemo extends IOApp:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  def run(args: List[String]): IO[ExitCode] = args match
    case executable :: digest :: source :: output :: Nil =>
      val root = Path.of(output).toAbsolutePath
      for
        _ <- IO.blocking(Files.createDirectory(root))
        pin <- Checked.lift[IO, Digest](Digest.from(digest))
        system <- ReferenceSystem.create(root.resolve("storage"))
        adapter = new DicomImport[IO](system.store, IdSource.random[IO], ConverterPin(Path.of(executable), pin))
        converted <- checked(adapter.convert(new NativeDirectorySource[IO](Path.of(source)), root.resolve("conversion")))
        bad <- adapter.map(converted, DicomMapping("incomplete", Map.empty))
        _ <- IO.raiseUnless(bad.isLeft)(new IllegalStateException("unaccounted output was accepted"))
        mapping <- IO.fromEither(converted.outputs.files.map { file =>
          val extension = if file.path.fileName.endsWith(".nii") then "nii" else "json"
          RelPath.from(s"sub-01/anat/sub-01_T1w.$extension").map(file.path -> _)
        }.foldLeft[Either[Problem, Vector[(RelPath, RelPath)]]](Right(Vector.empty))((acc, next) =>
          for a <- acc; n <- next yield a :+ n).left.map(p => new IllegalArgumentException(p.message)))
        bids <- checked(adapter.map(converted, DicomMapping("synthetic-T1w-v1", mapping.toMap)))
        preview <- checked(system.repository.importNative(system.context, system.dataset.id, bids))
        _ <- IO.raiseUnless(preview.candidate.activity.evidence.nonEmpty)(new IllegalStateException("missing conversion evidence"))
        receipt <- checked(system.repository.accept(system.context, preview.candidate.id, IdempotencyKey.from("converted").toOption.get))
        _ <- checked(system.repository.exportFull(system.context, receipt.release, new DirectoryExport[IO](root.resolve("export"))))
        _ <- IO.println(s"Converted, mapped, assessed, accepted and exported synthetic DICOM. Evidence: ${converted.receipt.digest.value}")
      yield ExitCode.Success
    case _ => IO.raiseError(new IllegalArgumentException("usage: DicomDemo EXECUTABLE SHA256 DICOM_DIRECTORY NEW_WORKSPACE"))
