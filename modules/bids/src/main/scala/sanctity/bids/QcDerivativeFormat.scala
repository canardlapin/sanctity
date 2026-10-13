package sanctity.bids

import io.circe.{Json, Printer}
import sanctity.kernel.*

/** Repository-local QC table profile; only dataset-level provenance claims BIDS standard coverage. */
object QcDerivativeFormat:
  val Version = "sanctity-header-qc-v1"
  val Files: Set[String] = Set("dataset_description.json", "qc.json", "qc.tsv")
  val Columns: Vector[String] = Vector("source_file", "dim_x", "dim_y", "dim_z", "volumes", "repetition_time_seconds", "header_spacing_seconds")
  val Producer = "sanctity-header-qc"
  val ProducerVersion = "1"
  val printer: Printer = Printer.spaces2.copy(sortKeys = true)
  def render(value: Json): String = printer.print(value) + "\n"
  def sourceUri(ref: ReleaseRef): String = "urn:uuid:" + ref.release.render
  def parameters(values: Map[String, String]): Json = Json.obj(values.toVector.sortBy(_._1).map((k, v) => k -> Json.fromString(v))*)
  def declaration(run: RunId, ref: ReleaseRef, bundle: Digest, producer: ProducerIdentity, settings: Map[String, String]): Json = Json.obj(
    "Profile" -> Json.fromString(Version), "Invocation" -> Json.fromString(run.render),
    "InputDataset" -> Json.fromString(ref.dataset.render), "InputRelease" -> Json.fromString(ref.release.render),
    "Bundle" -> Json.fromString(bundle.value), "ProducerCode" -> Json.fromString(producer.code.digest.value),
    "Runtime" -> Json.fromString(producer.runtime), "Parameters" -> parameters(settings))
  def description(run: RunId, input: Release, producer: ProducerIdentity, settings: Map[String, String]): Json = Json.obj(
    "Name" -> Json.fromString("MRI header QC summary"), "BIDSVersion" -> Json.fromString("1.11.1"),
    "DatasetType" -> Json.fromString("derivative"),
    "GeneratedBy" -> Json.arr(Json.obj("Name" -> Json.fromString(producer.name), "Version" -> Json.fromString(producer.version))),
    "SourceDatasets" -> Json.arr(Json.obj("URL" -> Json.fromString(sourceUri(input.ref)), "Version" -> Json.fromString(input.ref.release.render))),
    "DatasetLinks" -> Json.obj("input" -> Json.fromString(sourceUri(input.ref))),
    "SanctityHeaderQc" -> declaration(run, input.ref, input.activity.bundle.get.id, producer, settings))
  def sidecar(paths: Vector[RelPath]): Json = Json.obj(
    "Sources" -> Json.arr(paths.sortBy(_.value).map(p => Json.fromString("bids:input:" + p.value))*),
    "Description" -> Json.fromString("Header observations only; no voxel QC, alignment, motion or scanner qualification."),
    "repetition_time_seconds" -> Json.obj("Units" -> Json.fromString("s")),
    "header_spacing_seconds" -> Json.obj("Units" -> Json.fromString("s")))
