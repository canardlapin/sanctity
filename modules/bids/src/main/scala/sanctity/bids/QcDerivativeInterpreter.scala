package sanctity.bids

import bids4s.{BidsJson, BidsMetadataValidation, BidsPath, CheckedTsv}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{BlobStore, Checked, SemanticInterpreter}
import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Explicit, narrow derivative interpreter. Arbitrary derivative formats require another profile. */
final class QcDerivativeInterpreter[F[_]: Async](store: BlobStore[F]) extends SemanticInterpreter[F]:
  private val parser = new JawnParser(maxValueSize = Some(1024 * 1024), allowDuplicateKeys = false)
  private val profile = SemanticProfile("sanctity-header-qc-v2", BuildIdentity.bids4s, BuildIdentity.sanctity,
    "BIDS derivative dataset description and a repository-local header QC table. Checks declared provenance, exact rows, positive counts and timing; no general derivative or image-quality qualification.")
  private def path(s: String): RelPath = RelPath.from(s).toOption.get
  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] = assess(manifest, None, Vector.empty, None)
  override def prepare(manifest: Manifest, activity: Activity): F[(Interpretation, Assessment)] = prepare(manifest, activity, Vector.empty)

  override def prepare(manifest: Manifest, activity: Activity, sources: Vector[Release]): F[(Interpretation, Assessment)] =
    activity.processing.traverse { p =>
      Checked.lift[F, Unit](Either.cond(p.request.size <= 1024 * 1024, (), Problem.LimitExceeded("QC request exceeds 1 MiB"))) *>
        store.read(p.request).take(1024 * 1024 + 1L).compile.to(Array).flatMap { bytes =>
          Checked.lift[F, Json](Either.cond(bytes.length.toLong == p.request.size && Digest.bytes(bytes) == p.request.digest,
            (), Problem.Integrity("QC request differs from retained evidence")).flatMap(_ =>
            parser.parse(new String(bytes, StandardCharsets.UTF_8)).leftMap(_ => Problem.InvalidManifest("invalid QC request"))))
        }
    }.flatMap(request => assess(manifest, activity.processing, sources, request))

  private def assess(manifest: Manifest, receipt: Option[ProcessingEvidence], sources: Vector[Release], request: Option[Json]): F[(Interpretation, Assessment)] =
    for
      _ <- Checked.lift[F, Unit](Either.cond(manifest.files.forall(_.blob.size <= 1024 * 1024) && manifest.files.size <= 3,
        (), Problem.LimitExceeded("QC profile allows exactly three documents, at most 1 MiB each")))
      documents <- manifest.files.traverse { f =>
        store.read(f.blob).compile.to(Array).flatMap { bytes =>
          Checked.lift[F, String](Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toEither
            .leftMap(_ => Problem.InvalidManifest("QC document is not UTF-8"))).map(f.path.value -> _)
        }
      }.map(_.toMap)
      result <- Checked.lift[F, (Interpretation, Assessment)](interpret(manifest, documents, receipt, sources, request))
    yield result

  private def interpret(manifest: Manifest, documents: Map[String, String], receipt: Option[ProcessingEvidence], sources: Vector[Release], request: Option[Json]): Either[Problem, (Interpretation, Assessment)] =
    val findings = Vector.newBuilder[Finding]
    def check(ok: Boolean, code: String, target: String, message: String): Unit =
      if !ok then findings += Finding(code, Severity.Error, Some(path(target)), message)
    check(documents.keySet == QcDerivativeFormat.Files && manifest.directories.isEmpty,
      "QcInventory", "dataset_description.json", "Expected only dataset_description.json, qc.json and qc.tsv")
    val description = documents.get("dataset_description.json").flatMap(s => parser.parse(s).toOption).getOrElse(Json.Null)
    val sidecar = documents.get("qc.json").flatMap(s => parser.parse(s).toOption).getOrElse(Json.Null)
    val d = description.hcursor
    val declared = d.downField("SanctityHeaderQc")
    check(d.get[String]("DatasetType").contains("derivative") && declared.get[String]("Profile").contains(QcDerivativeFormat.Version),
      "QcProfile", "dataset_description.json", "Explicit derivative DatasetType and supported QC profile are required")
    val generated = Json.arr(Json.obj("Name" -> Json.fromString(QcDerivativeFormat.Producer), "Version" -> Json.fromString(QcDerivativeFormat.ProducerVersion)))
    check(d.get[Json]("GeneratedBy").contains(generated), "QcProducer", "dataset_description.json", "Unsupported QC producer identity")
    val rawDescription = documents.getOrElse("dataset_description.json", "")
    BidsJson.parseObject(rawDescription) match
      case Left(_) => check(false, "QcDescription", "dataset_description.json", "Description must be a JSON object")
      case Right(json) => BidsMetadataValidation.datasetDescription(BidsPath("dataset_description.json"), json).foreach { i =>
        findings += Finding("Qc" + i.code.toString, if i.severity == bids4s.BidsIssueSeverity.Error then Severity.Error else Severity.Warning,
          Some(path("dataset_description.json")), i.message)
      }
    val source = for
      dataset <- declared.get[String]("InputDataset").toOption.flatMap(Id.parse[DatasetTag](_).toOption)
      release <- declared.get[String]("InputRelease").toOption.flatMap(Id.parse[ReleaseTag](_).toOption)
      _ <- declared.get[String]("Bundle").toOption.flatMap(Digest.from(_).toOption)
      _ <- declared.get[String]("ProducerCode").toOption.flatMap(Digest.from(_).toOption)
      _ <- declared.get[String]("Invocation").toOption.flatMap(Id.parse[RunTag](_).toOption)
      _ <- declared.get[String]("Runtime").toOption.filter(s => s.nonEmpty && s.length <= 256)
      params <- declared.get[Map[String, String]]("Parameters").toOption if params.isEmpty
    yield ReleaseRef(dataset, release)
    check(source.nonEmpty && source.exists { ref =>
      d.downField("DatasetLinks").get[String]("input").contains(QcDerivativeFormat.sourceUri(ref)) &&
        d.get[Json]("SourceDatasets").contains(Json.arr(Json.obj("URL" -> Json.fromString(QcDerivativeFormat.sourceUri(ref)),
          "Version" -> Json.fromString(ref.release.render))))
    }, "QcSource", "dataset_description.json", "Qualified source release, bundle, software digest and matching dataset links are required")
    val table = CheckedTsv.parse(documents.getOrElse("qc.tsv", ""))
    val rows = table.toOption.toVector.flatMap(_.table.rows).map(_.map(_.getOrElse("n/a")))
    check(table.exists(t => t.table.columns == QcDerivativeFormat.Columns && t.table.rows.nonEmpty && t.table.rows.size <= 100),
      "QcTable", "qc.tsv", "Expected the versioned QC columns and 1–100 rows")
    val paths = rows.flatMap(_.headOption).flatMap(s => RelPath.from(s).toOption)
    check(paths.size == rows.size && paths.distinct.size == paths.size && paths == paths.sortBy(_.value) && paths.forall { p =>
      bids4s.BidsContext.Default.parsePath(BidsPath(p.value)).toOption.exists(n => n.kind == "bold" && Set("nii", "nii.gz")(n.extension))
    }, "QcSources", "qc.tsv", "Rows require distinct sorted BOLD paths in the input bundle")
    val validRows = rows.forall { row =>
      row.size == 7 && row.slice(1, 5).forall(v => v.matches("[1-9][0-9]{0,8}")) &&
        ScientificNumber.parse(row(4)).exists(_ > 1) && (for
          tr <- ScientificNumber.parse(row(5)) if tr > 0
          observed <- ScientificNumber.parse(row(6)) if observed > 0
        yield (tr - observed).abs <= BigDecimal("0.000001").max(tr * BigDecimal("0.00001"))).contains(true)
    }
    check(validRows, "QcValues", "qc.tsv", "QC counts and timing must be positive finite values with agreeing timing")
    check(sidecar == QcDerivativeFormat.sidecar(paths), "QcSidecar", "qc.json", "QC Sources and units must match the table")
    if receipt.isEmpty then findings += Finding("QcDeclaredProvenance", Severity.Warning, Some(path("dataset_description.json")),
      "Imported provenance is a declaration; no local processing receipt or input authorization has been established")
    val bindingValid = receipt.forall { p =>
      p.matches(manifest) && paths == p.targets && p.producer.name == QcDerivativeFormat.Producer && p.producer.version == QcDerivativeFormat.ProducerVersion &&
        p.parameters.isEmpty && declared.focus.contains(QcDerivativeFormat.declaration(p.invocation, p.input, p.bundle, p.producer, p.parameters))
    }
    check(bindingValid, "QcReceipt", "dataset_description.json", "Declared provenance differs from the submitted execution receipt")
    val sourceRelease = receipt.flatMap(p => sources.find(r => p.validateInput(r).isRight))
    check(receipt.isEmpty || sourceRelease.nonEmpty, "QcInputEvidence", "qc.tsv", "Accepted source observations are required to verify returned values")
    val valueChecks = sourceRelease.toVector.flatMap { r =>
      rows.filter(_.size == 7).flatMap { row =>
        RelPath.from(row.head).toOption.flatMap { sourcePath =>
          r.manifest.byPath.get(sourcePath).collect { case f: Entry.File => f }.map { image =>
            val header = r.assessment.checks.find(c => c.target == sourcePath && c.rule.startsWith(bids4s.MriHeaderConsistency.Version + "+"))
            val dims = header.flatMap(_.facts.get("dimensions")).collect { case MetadataValue.Array(ds) => ds.collect { case MetadataValue.Number(n) => n } }.getOrElse(Vector.empty)
            val tr = r.interpretation.byPath.get(sourcePath).flatMap(_.fields.get("RepetitionTime")).map(_.value).collect { case MetadataValue.Number(n) => n }
            val spacing = header.flatMap(_.facts.get("temporalSpacingSeconds")).collect { case MetadataValue.Number(n) => n }
            val expected = dims ++ tr ++ spacing
            val reported = row.tail.flatMap(ScientificNumber.parse)
            val matches = expected.size == 6 && reported.size == 6 && expected.zip(reported).zipWithIndex.forall { case ((a, b), i) =>
              if i < 4 then a == b else (a - b).abs <= BigDecimal("0.000001").max(a.abs * BigDecimal("0.00001"))
            }
            val external = header.toVector.flatMap(_.inputs).map(i => ReleasedArtifact(r.ref, r.interpretation.id, r.assessment.id, i.path, i.blob)).distinct
            val facts = QcDerivativeInterpreter.Metrics.zip(reported).map((m, v) => m.name -> MetadataValue.Number(v)).toMap
            val output = manifest.files.find(_.path.value == "qc.tsv").get
            val findings = if matches then Vector.empty else Vector(Finding("QcInputMismatch", Severity.Error, Some(output.path),
              s"Reported values differ from accepted source observations for ${sourcePath.value}"))
            val source = ReleasedArtifact(r.ref, r.interpretation.id, r.assessment.id, sourcePath, image.blob)
            (source, ContentCheck.from(QcDerivativeInterpreter.ValueRule, output.path, Vector(CheckInput("output", output.path, output.blob)),
              facts, findings, external), reported)
          }
        }
      }
    }
    val observations = valueChecks.flatMap { (source, _, reported) =>
      QcDerivativeInterpreter.Metrics.zip(reported).map((m, v) => ScientificObservation(source, m, v, path("qc.tsv"), QcDerivativeInterpreter.ValueRule))
    }
    val expectedRows = sourceRelease.toVector.flatMap { r => paths.flatMap { p =>
      r.interpretation.byPath.get(p).flatMap(_.fields.get("RepetitionTime")).collect {
        case EffectiveField(MetadataValue.Number(tr), _, _) => Json.obj("path" -> Json.fromString(p.value), "tr" -> Json.fromBigDecimal(tr))
      }
    }}
    check(receipt.isEmpty || request.exists(j => j.hcursor.get[Json]("rows").contains(Json.arr(expectedRows*)) &&
      j.hcursor.get[Json]("columns").contains(Json.arr(QcDerivativeFormat.Columns.map(Json.fromString)*)) &&
      j.hcursor.get[Json]("description").contains(description) && j.hcursor.get[Json]("sidecar").contains(sidecar)),
      "QcRequest", "dataset_description.json", "Retained request differs from accepted inputs or output declarations")
    for
      observedChecks <- valueChecks.traverse(_._2)
      interpretation <- Interpretation.from(manifest.id, profile, Vector.empty, declaredBidsVersion = d.get[String]("BIDSVersion").toOption, observations = observations)
      checks <- receipt.filter(_ => manifest.files.exists(_.path.value == "dataset_description.json")).toVector.traverse { p =>
        val file = manifest.files.find(_.path.value == "dataset_description.json").get
        ContentCheck.from("processing-receipt-v1", file.path, manifest.files.zipWithIndex.map((f, i) => CheckInput(s"output-$i", f.path, f.blob)),
          Map("receipt" -> MetadataValue.Text(p.id.value), "matches" -> MetadataValue.Flag(bindingValid)), Vector.empty)
      }
    yield interpretation -> Assessment.from(interpretation, QcDerivativeFormat.Version, findings.result(), checks ++ observedChecks)

object QcDerivativeInterpreter:
  val ValueRule = "sanctity.header-qc-values.v1"
  val Metrics: Vector[MetricDefinition] = Vector("dim-x", "dim-y", "dim-z", "volumes").map(MetricDefinition(_, "1", None)) ++
    Vector("repetition-time", "header-spacing").map(MetricDefinition(_, "1", Some("s")))
