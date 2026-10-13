package sanctity.processing

import io.circe.Json
import sanctity.bids.QcDerivativeFormat
import sanctity.kernel.*

/** Explicit repository-local image derivative profile, not a claim of general BIDS statistical-map coverage. */
object TemporalStatistics:
  val Profile = "sanctity-temporal-statistics-v1"
  val Producer = "sanctity-temporal-statistics"
  val Contract = InputContract("sanctity.temporal-statistics", "1")
  val MaxTargets = 8
  val Parameters = Map("volumes" -> "all", "ddof" -> "0", "nonfinite" -> "reject", "precision" -> "float64", "algorithm" -> "shifted-welford-v1")
  val Statistics = Vector("mean", "stddev")
  val ValueRule = "sanctity.temporal-map.v1"
  def output(source: RelPath, statistic: String): RelPath =
    val stem = source.value.stripSuffix(".gz").stripSuffix(".nii").stripSuffix("_bold")
    RelPath.from(stem + "_desc-temporal" + (if statistic == "mean" then "Mean" else "Stddev") + "_statmap.nii").toOption.get
  def sidecarPath(source: RelPath, statistic: String): RelPath = RelPath.from(output(source, statistic).value.stripSuffix(".nii") + ".json").toOption.get
  def sidecar(source: RelPath, statistic: String): Json = Json.obj(
    "Sources" -> Json.arr(Json.fromString("bids:input:" + source.value)), "Statistic" -> Json.fromString(statistic),
    "Profile" -> Json.fromString(Profile), "Parameters" -> QcDerivativeFormat.parameters(Parameters),
    "SpatialReference" -> Json.fromString("bids:input:" + source.value),
    "Description" -> Json.fromString("All-volume temporal statistic of scaled signal on the unchanged input grid. No motion correction, censoring, registration or masking."))
  def declaration(run: RunId, input: ReleaseRef, bundle: Digest, producer: ProducerIdentity): Json =
    QcDerivativeFormat.declaration(run, input, bundle, producer, Parameters).mapObject(_.add("Profile", Json.fromString(Profile)))
  def description(run: RunId, input: Release, producer: ProducerIdentity): Json =
    QcDerivativeFormat.description(run, input, producer, Parameters).mapObject(_.remove("SanctityHeaderQc")
      .add("Name", Json.fromString("Temporal mean and population standard deviation"))
      .add("SanctityTemporalStatistics", declaration(run, input.ref, input.activity.bundle.get.id, producer)))
  def request(run: RunId, input: Release, producer: ProducerIdentity): Json =
    val targets = input.activity.bundle.get.readiness.roots
    Json.obj("description" -> description(run, input, producer), "parameters" -> QcDerivativeFormat.parameters(Parameters),
      "rows" -> Json.arr(targets.map { p =>
        val image = input.manifest.files.find(_.path == p).get
        Json.obj("path" -> Json.fromString(p.value), "sha256" -> Json.fromString(image.blob.digest.value),
          "size" -> Json.fromLong(image.blob.size), "outputs" -> Json.arr(Statistics.map { statistic =>
            Json.obj("statistic" -> Json.fromString(statistic), "path" -> Json.fromString(output(p, statistic).value),
              "sidecarPath" -> Json.fromString(sidecarPath(p, statistic).value), "sidecar" -> sidecar(p, statistic))
          }*))
      }*))
  def files(targets: Vector[RelPath]): Set[String] = Set("dataset_description.json") ++ targets.flatMap(p => Statistics.flatMap(s => Vector(output(p, s).value, sidecarPath(p, s).value)))
  def metric(statistic: String): MetricDefinition = MetricDefinition("temporal-" + statistic + ".spatial-mean", "1", None)
