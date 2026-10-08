package sanctity.dicom

import bids4s.BidsName
import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, Printer}
import io.circe.jawn.JawnParser
import sanctity.application.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, ManagedProcess, NativeDirectorySource}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

final case class ConverterPin(executable: Path, sha256: Digest)
final case class ConversionBatch(inputs: Manifest, outputs: Manifest, receipt: BlobRef, retained: Vector[BlobRef])
final case class DicomMapping(version: String, files: Map[RelPath, RelPath],
    excluded: Map[RelPath, String] = Map.empty, metadata: Map[RelPath, Map[String, Json]] = Map.empty)

/** Conversion and curation are separate. Every output needs a mapping or an explicit exclusion.
  * Original inputs and converter diagnostics remain private managed evidence.
  */
final class DicomImport[F[_]: Async](store: BlobStore[F], ids: IdSource[F], pin: ConverterPin):
  private val printer = Printer.spaces2.copy(sortKeys = true)
  private val parser = new JawnParser(maxValueSize = Some(4 * 1024 * 1024), allowDuplicateKeys = false)
  private val F = Async[F]

  private def textBlob(json: Json): F[BlobRef] =
    store.put(Stream.emits((printer.print(json) + "\n").getBytes(StandardCharsets.UTF_8)).covary[F])

  private def inventory(source: NativeSource[F]): F[Manifest] =
    for
      entries <- source.entries.take(10001).compile.toVector
      _ <- Checked.lift[F, Unit](Either.cond(entries.size <= 10000 && entries.nonEmpty, (), Problem.LimitExceeded("conversion inventory needs 1–10000 entries")))
      received <- entries.traverse {
        case SourceEntry.Directory(path) => F.pure[Entry](Entry.Directory(path))
        case file: SourceEntry.File =>
          for
            id <- ids.next[ArtifactTag]
            blob <- store.put(source.read(file))
            _ <- Checked.lift[F, Unit](Either.cond(blob.size == file.size, (), Problem.Integrity("conversion input changed")))
          yield Entry.File(file.path, id, blob)
      }
      manifest <- Checked.lift[F, Manifest](Manifest.from(received))
    yield manifest

  def convert(source: NativeSource[F], newWorkspace: Path): F[Either[Problem, ConversionBatch]] = Checked.attempt {
    for
      executable <- F.blocking {
        if !Files.isRegularFile(pin.executable) || Files.size(pin.executable) > 100L * 1024 * 1024 then
          throw Failure(Problem.Unsupported("converter executable must be a regular file below 100 MiB"))
        Digest.bytes(Files.readAllBytes(pin.executable))
      }
      _ <- Checked.lift[F, Unit](Either.cond(executable == pin.sha256, (), Problem.Integrity("converter executable differs from its pin")))
      _ <- F.blocking(Files.createDirectory(newWorkspace))
      inputs <- inventory(source)
      _ <- new DirectoryExport[F](newWorkspace.resolve("input")).materialize(inputs, store)
      _ <- F.blocking(Files.createDirectory(newWorkspace.resolve("output")))
      options = Vector("-g", "i", "-b", "y", "-ba", "y", "-z", "n", "-w", "0", "-x", "i",
        "--big-endian", "n", "-c", "", "-f", "%j")
      command = Vector(pin.executable.toAbsolutePath.toString) ++ options ++
        Vector("-o", newWorkspace.resolve("output").toAbsolutePath.toString, newWorkspace.resolve("input").toAbsolutePath.toString)
      result <- ManagedProcess.run[F](command, newWorkspace)
      log <- store.put(Stream.emits(result.output).covary[F])
      _ <- Checked.lift[F, Unit](Either.cond(result.exitCode == 0, (),
        Problem.Operational(s"converter exited ${result.exitCode}; restricted diagnostic digest ${log.digest.value}")))
      outputs <- inventory(new NativeDirectorySource[F](newWorkspace.resolve("output")))
      _ <- Checked.lift[F, Unit](Either.cond(outputs.files.exists(_.path.fileName.endsWith(".nii")), (),
        Problem.Rejected(Vector(Finding("NoImages", Severity.Error, None, "converter produced no NIfTI images")))))
      receipt <- textBlob(Json.obj("version" -> Json.fromInt(1), "kind" -> Json.fromString("DICOM conversion"),
        "source" -> Json.fromString(source.label), "converterSha256" -> Json.fromString(pin.sha256.value),
        "arguments" -> Json.arr(options.map(Json.fromString)*), "inputs" -> manifestJson(inputs),
        "outputs" -> manifestJson(outputs), "diagnostics" -> blobJson(log),
        "inputDisposition" -> Json.fromString("retained-source-evidence"),
        "sourceEvidence" -> source.evidence.fold(Json.Null)(blobJson)))
    yield ConversionBatch(inputs, outputs, receipt, (inputs.files.map(_.blob) ++ outputs.files.map(_.blob) ++
      Vector(log, receipt) ++ source.evidence ++ source.retainedEvidence).distinct)
  }

  def map(batch: ConversionBatch, mapping: DicomMapping): F[Either[Problem, NativeSource[F]]] = Checked.attempt {
    for
      _ <- Checked.lift[F, Unit](Either.cond(mapping.version.trim.nonEmpty && mapping.excluded.values.forall(_.trim.nonEmpty) &&
        mapping.files.keySet.intersect(mapping.excluded.keySet).isEmpty &&
        (mapping.files.keySet ++ mapping.excluded.keySet) == batch.outputs.files.map(_.path).toSet &&
        mapping.files.values.toVector.distinct.size == mapping.files.size &&
        mapping.metadata.keySet.subsetOf(mapping.files.values.toSet), (), Problem.Conflict("mapping must account for every converter output exactly once")))
      files <- batch.outputs.files.filter(f => mapping.files.contains(f.path)).traverse { file =>
        val destination = mapping.files(file.path)
        for
          name <- Checked.lift[F, BidsName](BidsName.parse(destination.fileName).leftMap(e => Problem.Unsupported(e.message)))
          _ <- Checked.lift[F, Unit](Either.cond(Set("T1w", "T2w", "bold", "sbref").contains(name.kind) &&
            Set("json", "nii").contains(name.extension) && name.entities.values.keys.exists(_.short == "sub"),
            (), Problem.Unsupported("initial conversion mapping supports explicit MRI NIfTI/JSON pairs")))
          _ <- Checked.lift[F, Unit](Either.cond(file.path.fileName.endsWith("." + name.extension), (),
            Problem.Unsupported("mapping cannot change a file format")))
          blob <- mapping.metadata.get(destination) match
            case None => F.pure(file.blob)
            case Some(fields) =>
              for
                _ <- Checked.lift[F, Unit](Either.cond(name.extension == "json" && file.blob.size <= 4L * 1024 * 1024,
                  (), Problem.Unsupported("metadata supplementation requires a JSON sidecar below 4 MiB")))
                bytes <- store.read(file.blob).compile.to(Array)
                json <- Checked.lift[F, Json](parser.parse(new String(bytes, StandardCharsets.UTF_8)).leftMap(_ => Problem.Integrity("invalid converter JSON")))
                objectValue <- Checked.lift[F, io.circe.JsonObject](json.asObject.toRight(Problem.Integrity("converter sidecar must be an object")))
                result <- textBlob(Json.fromJsonObject(fields.toVector.foldLeft(objectValue) { case (obj, (name, value)) => obj.add(name, value) }))
              yield result
        yield Entry.File(destination, file.artifact, blob)
      }
      _ <- Checked.lift[F, Unit](Either.cond(files.exists(_.path.fileName.endsWith(".nii")) && files.filter(_.path.fileName.endsWith(".nii")).forall(f =>
        files.exists(_.path.value == f.path.value.stripSuffix(".nii") + ".json")), (), Problem.Conflict("every mapped NIfTI needs its matching sidecar")))
      descriptionPath <- Checked.lift[F, RelPath](RelPath.from("dataset_description.json"))
      description <- textBlob(Json.obj("Name" -> Json.fromString("Curated DICOM import"),
        "BIDSVersion" -> Json.fromString("1.10.0"), "DatasetType" -> Json.fromString("raw")))
      descriptionId <- ids.next[ArtifactTag]
      allFiles = files :+ Entry.File(descriptionPath, descriptionId, description)
      directories = allFiles.flatMap(f => Iterator.iterate(f.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten.toVector).distinct
      manifest <- Checked.lift[F, Manifest](Manifest.from(directories.map(Entry.Directory.apply) ++ allFiles))
      receipt <- textBlob(Json.obj("version" -> Json.fromInt(1), "kind" -> Json.fromString("BIDS mapping"),
        "mappingVersion" -> Json.fromString(mapping.version), "conversionReceipt" -> blobJson(batch.receipt),
        "mappings" -> Json.obj(mapping.files.toVector.map((from, to) => from.value -> Json.fromString(to.value))*),
        "excluded" -> Json.obj(mapping.excluded.toVector.map((path, reason) => path.value -> Json.fromString(reason))*),
        "supplements" -> Json.obj(mapping.metadata.toVector.map((path, fields) => path.value -> Json.obj(fields.toVector*))*),
        "output" -> manifestJson(manifest)))
    yield new BlobSource[F](manifest, store, s"dicom-mapping:${receipt.digest.value}", Some(receipt), batch.retained)
  }

  private def blobJson(blob: BlobRef): Json = Json.obj("sha256" -> Json.fromString(blob.digest.value), "size" -> Json.fromLong(blob.size))
  private def manifestJson(manifest: Manifest): Json = Json.obj("manifest" -> Json.fromString(manifest.id.value.value),
    "files" -> Json.arr(manifest.files.map(f => Json.obj("path" -> Json.fromString(f.path.value),
      "artifact" -> Json.fromString(f.artifact.render), "content" -> blobJson(f.blob)))*))
