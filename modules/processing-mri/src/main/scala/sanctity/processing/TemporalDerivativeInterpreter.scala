package sanctity.processing

import bids4s.{BidsJson, BidsMetadataValidation, BidsPath}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.*
import sanctity.bids.{BuildIdentity, QcDerivativeFormat}
import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Structural/source qualification and observed map summaries; does not re-execute the recipe at admission. */
final class TemporalDerivativeInterpreter[F[_]: Async](store: BlobStore[F]) extends SemanticInterpreter[F]:
  private val parser = new JawnParser(maxValueSize = Some(1024 * 1024), allowDuplicateKeys = false)
  private val payload = new NiftiPayload[F](store)
  private val profile = SemanticProfile(TemporalStatistics.Profile, BuildIdentity.bids4s, BuildIdentity.sanctity,
    "Repository-local temporal mean/population-SD maps. Full payload, source grid, request and provenance checks; numerical correctness qualified by independent fixtures, not per-run recomputation.")
  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] = assess(manifest, None, Vector.empty)
  override def prepare(manifest: Manifest, activity: Activity): F[(Interpretation, Assessment)] = assess(manifest, activity.processing, Vector.empty)
  override def prepare(manifest: Manifest, activity: Activity, sources: Vector[Release]): F[(Interpretation, Assessment)] = assess(manifest, activity.processing, sources)

  private def json(blob: BlobRef): F[Json] =
    Checked.lift[F, Unit](Either.cond(blob.size <= 1024 * 1024, (), Problem.LimitExceeded("temporal JSON exceeds 1 MiB"))) *>
      store.read(blob).take(1024 * 1024 + 1L).compile.to(Array).flatMap { bytes =>
        val decoded = Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString).toEither.leftMap(_ => Problem.InvalidManifest("invalid UTF-8"))
        Checked.lift[F, Json](decoded.flatMap(s => parser.parse(s).leftMap(_ => Problem.InvalidManifest("invalid temporal JSON"))))
      }

  private def assess(manifest: Manifest, receipt: Option[ProcessingEvidence], sources: Vector[Release]): F[(Interpretation, Assessment)] =
    for
      _ <- Checked.lift[F, Unit](Either.cond(manifest.files.size <= 1 + 4 * TemporalStatistics.MaxTargets, (), Problem.LimitExceeded("temporal profile exceeds eight series")))
      descriptionFile <- Checked.lift[F, Entry.File](manifest.files.find(_.path.value == "dataset_description.json").toRight(Problem.InvalidManifest("missing derivative description")))
      description <- json(descriptionFile.blob)
      docs <- manifest.files.filter(f => f.path.value.endsWith(".json") && f != descriptionFile).traverse(f => json(f.blob).map(f.path -> _)).map(_.toMap)
      targets <- Checked.lift[F, Vector[RelPath]](docs.values.toVector.traverse { j =>
        j.hcursor.get[Vector[String]]("Sources").toOption.filter(_.size == 1).flatMap(_.headOption).filter(_.startsWith("bids:input:"))
          .toRight(Problem.InvalidManifest("map needs one qualified source path")).flatMap(s => RelPath.from(s.stripPrefix("bids:input:")))
      }.map(_.distinct.sortBy(_.value)))
      _ <- Checked.lift[F, Unit](Either.cond(targets.nonEmpty && targets.size <= TemporalStatistics.MaxTargets &&
        manifest.files.map(_.path.value).toSet == TemporalStatistics.files(targets), (), Problem.InvalidManifest("unexpected temporal map inventory")))
      images <- targets.traverse { source => TemporalStatistics.Statistics.traverse { statistic =>
        val f = manifest.files.find(_.path == TemporalStatistics.output(source, statistic)).get
        payload.inspect(f).map(observed => (source, statistic, f, observed))
      }}.map(_.flatten)
      request <- receipt.traverse(p => json(p.request))
      result <- Checked.lift[F, (Interpretation, Assessment)] {
        val findings = Vector.newBuilder[Finding]
        def check(ok: Boolean, code: String, at: RelPath, message: String): Unit =
          if !ok then findings += Finding(code, Severity.Error, Some(at), message)
        val d = description.hcursor
        val declaration = d.downField("SanctityTemporalStatistics")
        check(d.get[String]("DatasetType").contains("derivative") && declaration.get[String]("Profile").contains(TemporalStatistics.Profile),
          "TemporalProfile", descriptionFile.path, "Expected explicit derivative and temporal-statistics profile")
        BidsJson.parseObject(QcDerivativeFormat.render(description)).foreach { value =>
          BidsMetadataValidation.datasetDescription(BidsPath(descriptionFile.path.value), value).foreach { i =>
            findings += Finding("Temporal" + i.code.toString, if i.severity == bids4s.BidsIssueSeverity.Error then Severity.Error else Severity.Warning,
              Some(descriptionFile.path), i.message)
          }
        }
        val declaredSource = for
          dataset <- declaration.get[String]("InputDataset").toOption.flatMap(Id.parse[DatasetTag](_).toOption)
          release <- declaration.get[String]("InputRelease").toOption.flatMap(Id.parse[ReleaseTag](_).toOption)
          _ <- declaration.get[String]("Bundle").toOption.flatMap(Digest.from(_).toOption)
          _ <- declaration.get[String]("ProducerCode").toOption.flatMap(Digest.from(_).toOption)
          _ <- declaration.get[String]("Invocation").toOption.flatMap(Id.parse[RunTag](_).toOption)
          _ <- declaration.get[String]("Runtime").toOption.filter(s => s.nonEmpty && s.length <= 256)
        yield ReleaseRef(dataset, release)
        check(declaredSource.exists(ref => d.get[Json]("DatasetLinks").contains(Json.obj("input" -> Json.fromString(QcDerivativeFormat.sourceUri(ref)))) &&
          d.get[Json]("SourceDatasets").contains(Json.arr(Json.obj("URL" -> Json.fromString(QcDerivativeFormat.sourceUri(ref)), "Version" -> Json.fromString(ref.release.render))))),
          "TemporalSource", descriptionFile.path, "Source release declaration and links must agree")
        check(declaration.get[Json]("Parameters").contains(QcDerivativeFormat.parameters(TemporalStatistics.Parameters)) &&
          d.get[Json]("GeneratedBy").contains(Json.arr(Json.obj("Name" -> Json.fromString(TemporalStatistics.Producer), "Version" -> Json.fromString("1")))),
          "TemporalRecipe", descriptionFile.path, "Unsupported recipe or parameters")
        val input = receipt.flatMap(p => sources.find(r => p.validateInput(r).isRight && r.activity.bundle.exists(_.readiness.contract == TemporalStatistics.Contract)))
        val bound = receipt.forall(p => p.matches(manifest) && p.targets == targets && p.producer.name == TemporalStatistics.Producer &&
          p.producer.version == "1" && p.parameters == TemporalStatistics.Parameters && input.exists(r =>
            description == TemporalStatistics.description(p.invocation, r, p.producer) && request.contains(TemporalStatistics.request(p.invocation, r, p.producer))))
        check(bound && (receipt.isEmpty || input.nonEmpty), "TemporalReceipt", descriptionFile.path, "Output, input contract or retained request differs from execution evidence")
        if receipt.isEmpty then findings += Finding("TemporalDeclaredProvenance", Severity.Warning, Some(descriptionFile.path),
          "Imported source lineage is a declaration; no local run or current source qualification has been established")
        val observations = Vector.newBuilder[ScientificObservation]
        val metadata = Vector.newBuilder[ArtifactMetadata]
        val facts = Vector.newBuilder[ArtifactFacts]
        val checks = images.traverse { (source, statistic, f, result) =>
          val sidecar = TemporalStatistics.sidecarPath(source, statistic)
          check(docs.get(sidecar).contains(TemporalStatistics.sidecar(source, statistic)), "TemporalSidecar", sidecar, "Map metadata differs from profile/source")
          val name = bids4s.BidsContext.Default.parsePath(BidsPath(source.value)).toOption
          check(name.exists(n => n.kind == "bold" && Set("nii", "nii.gz")(n.extension)), "TemporalSourcePath", sidecar, "Source must be a BOLD image")
          val entities = name.toVector.flatMap(_.entities.values.map((k, v) => k.short -> v)).toMap
          val fields = Map("Statistic" -> MetadataValue.Text(statistic), "Sources" -> MetadataValue.Array(Vector(MetadataValue.Text("bids:input:" + source.value))),
            "Algorithm" -> MetadataValue.Text("shifted-welford-v1"), "Ddof" -> MetadataValue.Number(0))
          metadata += ArtifactMetadata(f.path, fields.map((k, v) => k -> EffectiveField(v, sidecar, Vector(Contribution(sidecar, v)))), Vector(sidecar), None)
          entities.get("sub").foreach { subject =>
            facts += ArtifactFacts(f.path, "sub-" + subject, entities.get("ses").map("ses-" + _), "statmap", "nii",
              entities + ("desc" -> (if statistic == "mean" then "temporalMean" else "temporalStddev")))
          }
          val errors = Vector.newBuilder[Finding]
          def error(ok: Boolean, code: String, message: String): Unit = if !ok then errors += Finding(code, Severity.Error, Some(f.path), message)
          result.left.foreach(p => error(false, "TemporalPayload", p.message))
          result.foreach(o => error(o.header.dimensions.size == 3 && o.header.datatype == 64 && o.scaling.slope == 1 && o.scaling.intercept == 0 &&
            (statistic != "stddev" || o.minimum >= 0), "TemporalMap", "Expected finite unscaled float64 3D map and nonnegative standard deviation"))
          val external = input.toVector.flatMap { r => r.manifest.files.find(_.path == source).map(f => ReleasedArtifact(r.ref, r.interpretation.id, r.assessment.id, source, f.blob)) }
          input.foreach { r =>
            val evidence = r.assessment.checks.find(c => c.target == source && c.rule == NiftiPayload.Rule && c.facts.get("complete").contains(MetadataValue.Flag(true)))
            error(evidence.nonEmpty && result.exists(o => NiftiPayload.geometry(o.header).forall((k, v) => evidence.get.facts.get(k).contains(v))),
              "TemporalGeometry", "Output grid/transforms must match assessed source geometry exactly")
          }
          val metric = TemporalStatistics.metric(statistic)
          val metricValue = result.toOption.map(o => BigDecimal(o.mean.toString))
          external.headOption.foreach { sourceRef => metricValue.foreach { value =>
            val observation = ScientificObservation(sourceRef, metric, value, f.path, TemporalStatistics.ValueRule)
            if observation.valid then observations += observation else error(false, "TemporalNumericRange", "Summary exceeds indexed decimal range")
          }}
          ContentCheck.from(TemporalStatistics.ValueRule, f.path,
            Vector(CheckInput("image", f.path, f.blob), CheckInput("metadata", sidecar, manifest.files.find(_.path == sidecar).get.blob)),
            result.fold(_ => Map.empty[String, MetadataValue], _.facts) ++ metricValue.map(v => metric.name -> MetadataValue.Number(v)), errors.result(), external)
        }
        for
          mapChecks <- checks
          interpreted <- Interpretation.from(manifest.id, profile, metadata.result(), facts.result(), queryVersion = Some("scientific-query-v1"),
            declaredBidsVersion = d.get[String]("BIDSVersion").toOption, observations = observations.result())
          receiptChecks <- receipt.toVector.traverse { p => ContentCheck.from("processing-receipt-v1", descriptionFile.path,
            manifest.files.zipWithIndex.map((f, i) => CheckInput(s"output-$i", f.path, f.blob)),
            Map("receipt" -> MetadataValue.Text(p.id.value), "matches" -> MetadataValue.Flag(bound)), Vector.empty) }
        yield interpreted -> Assessment.from(interpreted, TemporalStatistics.Profile, findings.result(), mapChecks ++ receiptChecks)
      }
    yield result
