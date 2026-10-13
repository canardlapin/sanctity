package sanctity.bids

import bids4s.{DwiIssue, JsonValue, MriHeaderConsistency, NiftiHeaderFacts}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*

/** Header observations and metadata comparison, with no preferred affine or voxel-array reads. */
final class MriHeaderChecks[F[_]](store: BlobStore[F])(using F: Async[F]):
  private val reader = new NiftiHeaderReader[F](store)
  private val rule = MriHeaderConsistency.Version + "+" + NiftiHeaderFacts.Version + "+" + NiftiHeaderReader.Version

  def inspect(manifest: Manifest, interpretation: Interpretation): F[(Vector[ContentCheck], Map[RelPath, Either[DwiIssue, NiftiHeaderFacts]])] =
    val files = manifest.files.map(f => f.path -> f).toMap
    val targets = interpretation.facts.filter(f => Set("nii", "nii.gz")(f.extension) && MriHeaderConsistency.SupportedSuffixes(f.suffix))
    for
      headers <- targets.traverse(f => reader.read(files(f.path)).map(f.path -> _)).map(_.toMap)
      checks <- targets.traverse { target =>
        val image = files(target.path)
        val metadata = interpretation.byPath(target.path)
        val selected = metadata.fields.filter((k, _) => Set("RepetitionTime", "VolumeTiming")(k))
        val header = headers(target.path)
        val findings = header.fold(
          e => Vector(Finding("MriImage" + e.code.toString, Severity.Error, Some(target.path), e.message)),
          h => MriHeaderConsistency.check(h, target.suffix, JsonValue.Obj(selected.map((k, v) => k -> json(v.value))))
            .map(e => Finding("Mri" + e.code.toString, if e.isError then Severity.Error else Severity.Warning,
              Some(target.path), s"${e.field}: ${e.message}")))
        val sources = metadata.sources.distinct.sorted(Ordering.by[RelPath, String](_.value))
        val common = Map[String, MetadataValue]("headerOnly" -> MetadataValue.Flag(true),
          "compressedPrefixLimit" -> MetadataValue.Number(NiftiHeaderReader.CompressedPrefixLimit),
          "repetitionTimeRelativeTolerance" -> number(MriHeaderConsistency.RepetitionTimeRelativeTolerance),
          "repetitionTimeAbsoluteTolerance" -> number(MriHeaderConsistency.RepetitionTimeAbsoluteTolerance),
          "timingMetadata" -> MetadataValue.Record(selected.map((k, f) =>
            k -> MetadataValue.Record(Map("value" -> f.value, "origin" -> MetadataValue.Text(f.origin.value))))))
        for
          _ <- Checked.lift[F, Unit](Either.cond(sources.size <= 99, (), Problem.LimitExceeded("MRI header check exceeds 99 metadata documents")))
          docs <- Checked.lift[F, Vector[Entry.File]](sources.traverse(p => files.get(p).toRight(Problem.Integrity("header metadata absent from manifest"))))
          check <- Checked.lift[F, ContentCheck](ContentCheck.from(rule, target.path,
            Vector(CheckInput("image", image.path, image.blob)) ++ docs.zipWithIndex.map((f, i) => CheckInput(s"metadata-$i", f.path, f.blob)),
            common ++ header.toOption.toVector.flatMap(observations), findings))
        yield check
      }
    yield (checks, headers)

  private def number(n: Double): MetadataValue =
    if n.isFinite then MetadataValue.Number(BigDecimal(n.toString))
    else MetadataValue.Text(if n.isNaN then "NaN" else if n > 0 then "+Infinity" else "-Infinity")
  private def numbers(ns: Vector[Double]): MetadataValue = MetadataValue.Array(ns.map(number))
  private def observations(h: NiftiHeaderFacts): Map[String, MetadataValue] =
    Map("niftiVersion" -> MetadataValue.Number(h.version), "byteOrder" -> MetadataValue.Text(h.byteOrder.toString),
      "dimensions" -> MetadataValue.Array(h.dimensions.map(n => MetadataValue.Number(BigDecimal(n)))),
      "pixelDimensions" -> numbers(h.pixelDimensions), "unitsCode" -> MetadataValue.Number(h.unitsCode),
      "spatialUnit" -> MetadataValue.Text(MriHeaderConsistency.spatialUnit(h)),
      "temporalUnit" -> MetadataValue.Text(MriHeaderConsistency.temporalUnit(h)),
      "qformCode" -> MetadataValue.Number(h.qformCode), "sformCode" -> MetadataValue.Number(h.sformCode),
      "qformDeclared" -> MetadataValue.Flag(h.qformCode > 0), "sformDeclared" -> MetadataValue.Flag(h.sformCode > 0),
      "quaternion" -> numbers(h.quaternion), "qoffset" -> numbers(h.qoffset),
      "sformRows" -> MetadataValue.Array(h.sformRows.map(numbers))) ++
      MriHeaderConsistency.temporalSpacingSeconds(h).map(t => "temporalSpacingSeconds" -> number(t))
  private def json(v: MetadataValue): JsonValue = v match
    case MetadataValue.Number(n) => JsonValue.Num(n.toDouble)
    case MetadataValue.Text(s) => JsonValue.Str(s)
    case MetadataValue.Flag(b) => JsonValue.Bool(b)
    case MetadataValue.Null => JsonValue.Null
    case MetadataValue.Array(vs) => JsonValue.Arr(vs.map(json))
    case MetadataValue.Record(vs) => JsonValue.Obj(vs.map((k, v) => k -> json(v)))
