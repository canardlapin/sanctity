package sanctity.bids

import bids4s.{BidsIssueSeverity, DwiIssue, EventTiming, JsonValue, NiftiHeaderFacts}
import cats.syntax.all.*
import sanctity.kernel.*

/** Acquisition consistency, independent of event-table presence and without timing correction. */
object BoldTimingChecks:
  val Version = "sanctity-bold-timing-v1"
  val Rule = Version + "+" + EventTiming.Version + "+nifti-slice-dimension-v1"
  val Fields: Set[String] = EventTiming.Fields + "SliceEncodingDirection"
  val MaxValues = 100000
  val MaxFindings = 64

  final case class Issue(code: String, field: String, message: String, severity: Severity = Severity.Error)
  final case class Observation(facts: Map[String, MetadataValue], issues: Vector[Issue])

  /** sliceDimension is the NIfTI one-based axis (zero means unspecified). */
  def evaluate(header: Option[NiftiHeaderFacts], sliceDimension: Int,
      fields: Map[String, MetadataValue]): Observation =
    val issues = Vector.newBuilder[Issue]
    var count = 0
    def issue(code: String, field: String, message: String, severity: Severity = Severity.Error): Unit =
      if count < MaxFindings then issues += Issue(code, field, message, severity)
      else if count == MaxFindings then issues += Issue("Limit", field, "timing diagnostics exceed 64 findings")
      count += 1
    def number(value: MetadataValue): Option[BigDecimal] = value match
      case MetadataValue.Number(n) if n.toDouble.isFinite && n.precision <= 128 && math.abs(n.scale.toLong) <= 308 => Some(n)
      case _ => None
    def scalar(name: String, positive: Boolean = false): Option[BigDecimal] = fields.get(name).flatMap { v =>
      val n = number(v).filter(n => if positive then n > 0 else n >= 0)
      if n.isEmpty then issue("Value", name, "expected bounded nonnegative seconds" + (if positive then " greater than zero" else ""))
      n
    }
    def series(name: String): Option[Vector[BigDecimal]] = fields.get(name).flatMap {
      case MetadataValue.Array(vs) if vs.nonEmpty && vs.size <= MaxValues =>
        val parsed = vs.map(v => number(v).filter(_ >= 0))
        if parsed.exists(_.isEmpty) then
          issue("Value", name, "expected nonnegative finite seconds (at most 128 digits and absolute scale 308)")
          None
        else Some(parsed.flatten)
      case MetadataValue.Array(vs) if vs.size > MaxValues =>
        issue("Limit", name, "timing array exceeds 100000 values"); None
      case _ => issue("Value", name, "expected a nonempty timing array"); None
    }
    val volumes = header.filter(h => Set(3, 4)(h.dimensions.size)).map(_.dimensions.lift(3).getOrElse(1L))
    if volumes.isEmpty then issue("Image", "image", "timing assessment requires a parsed 3D or 4D image")
    val context = EventTiming.assess(volumes, fields.view.filterKeys(EventTiming.Fields).mapValues(json).toMap)
    context.issues.foreach(i => issue(i.code.toString, i.field.getOrElse("image"), i.message,
      if i.severity == BidsIssueSeverity.Error then Severity.Error else Severity.Warning))
    val tr = scalar("RepetitionTime", positive = true)
    val times = series("VolumeTiming")
    val slices = series("SliceTiming")
    val frame = scalar("FrameAcquisitionDuration", positive = true)
    val delay = scalar("DelayTime")
    Vector("NumberOfVolumesDiscardedByScanner", "NumberOfVolumesDiscardedByUser").foreach { name =>
      fields.get(name).foreach { value =>
        if !number(value).exists(n => n >= 0 && n.isWhole) then issue("DiscardCount", name, "expected a bounded nonnegative integer; counts do not shift the first stored volume")
      }
    }
    if tr.nonEmpty && fields.contains("FrameAcquisitionDuration") then
      issue("Combination", "FrameAcquisitionDuration", "FrameAcquisitionDuration accompanies VolumeTiming, not RepetitionTime in this profile")
    if delay.exists(d => tr.exists(d >= _)) then issue("Window", "DelayTime", "delay must be shorter than RepetitionTime")
    val gaps = times.toVector.flatMap(_.sliding(2).collect { case Vector(a, b) if b > a => b - a })
    val interval = tr.orElse(gaps.minOption)
    if frame.exists(f => interval.exists(f > _)) then issue("Window", "FrameAcquisitionDuration", "frame duration exceeds a recorded volume interval")
    val sliceWindow = Vector(interval, frame, tr.flatMap(t => delay.map(t - _))).flatten.minOption
    if slices.exists(ss => sliceWindow.exists(end => ss.exists(_ >= end))) then
      issue("Window", "SliceTiming", "slice onsets must precede the acquisition window end under this consistency profile")

    val direction = fields.get("SliceEncodingDirection").flatMap {
      case MetadataValue.Text(s) if Set("i", "i-", "j", "j-", "k", "k-")(s) => Some(s)
      case _ => issue("Direction", "SliceEncodingDirection", "expected i, j, k, i-, j- or k-"); None
    }
    val declaredAxis = direction.map(s => "ijk".indexOf(s.head) + 1)
    val headerAxis = Option.when(sliceDimension >= 1 && sliceDimension <= 3)(sliceDimension)
    val conflict = declaredAxis.exists(a => headerAxis.exists(_ != a))
    if conflict then issue("AxisConflict", "SliceEncodingDirection", "authored slice axis disagrees with NIfTI dim_info slice_dim")
    val axis = declaredAxis.orElse(headerAxis)
    val sliceCount = axis.flatMap(a => header.flatMap(_.dimensions.lift(a - 1)))
    if slices.nonEmpty && axis.isEmpty then issue("AxisUnavailable", "SliceTiming",
      "neither SliceEncodingDirection nor NIfTI slice_dim identifies the slice axis; count/order mapping is unavailable", Severity.Warning)
    if slices.exists(ss => sliceCount.exists(_ != ss.size.toLong)) then
      issue("SliceCount", "SliceTiming", "timing count differs from the identified image slice dimension")
    val ordered = slices.filter(ss => sliceCount.contains(ss.size.toLong) && !conflict &&
      (!fields.contains("SliceEncodingDirection") || direction.nonEmpty))
      .map(ss => if direction.exists(_.endsWith("-")) then ss.reverse else ss)
    val result = issues.result()
    Observation(Map[String, MetadataValue]("headerOnly" -> MetadataValue.Flag(true),
      "consistent" -> MetadataValue.Flag(!result.exists(_.severity == Severity.Error)),
      "timingMode" -> MetadataValue.Text(context.context.mode.toString),
      "headerSliceDimension" -> MetadataValue.Number(sliceDimension),
      "sliceAxisBasis" -> MetadataValue.Text(if declaredAxis.nonEmpty then "SliceEncodingDirection" else if headerAxis.nonEmpty then "NIfTI dim_info" else "unavailable"),
      "sliceMappingAvailable" -> MetadataValue.Flag(ordered.nonEmpty),
      "timeOrigin" -> MetadataValue.Text("first stored volume; discarded-volume counts do not shift event onsets"),
      "endBasis" -> MetadataValue.Text(context.context.endBasis),
      "maxTimingValues" -> MetadataValue.Number(MaxValues)) ++
      volumes.map(n => "volumeCount" -> MetadataValue.Number(BigDecimal(n))) ++
      axis.map(n => "sliceAxis" -> MetadataValue.Text("ijk"(n - 1).toString)) ++
      sliceCount.map(n => "sliceCount" -> MetadataValue.Number(BigDecimal(n))) ++
      ordered.map(ss => "sliceTimesByVoxelIndex" -> MetadataValue.Array(ss.map(MetadataValue.Number.apply))) ++
      context.context.lastVolumeOnset.map(n => "lastVolumeOnsetSeconds" -> MetadataValue.Number(n)) ++
      context.context.comparisonEnd.map(n => "comparisonEndSeconds" -> MetadataValue.Number(n)) ++
      context.context.originShift.map(n => "volumeTimingOriginSeconds" -> MetadataValue.Number(n)), result)

  private[bids] def assess(manifest: Manifest, interpretation: Interpretation,
      headers: Map[RelPath, Either[DwiIssue, NiftiHeaderObservation]]): Either[Problem, Vector[ContentCheck]] =
    val files = manifest.files.map(f => f.path -> f).toMap
    interpretation.facts.filter(f => f.suffix == "bold" && Set("nii", "nii.gz")(f.extension)).traverse { target =>
      val metadata = interpretation.byPath(target.path)
      val selected = metadata.fields.filter((k, _) => Fields(k))
      val observed = headers.get(target.path).flatMap(_.toOption)
      val evaluated = evaluate(observed.map(_.header), observed.fold(0)(_.sliceDimension), selected.map((k, f) => k -> f.value))
      val sources = metadata.sources.distinct.sortBy(_.value)
      for
        _ <- Either.cond(sources.size <= 99, (), Problem.LimitExceeded("BOLD timing check exceeds 99 metadata documents"))
        docs <- sources.traverse(p => files.get(p).toRight(Problem.Integrity("BOLD timing metadata absent from manifest")))
        image <- files.get(target.path).toRight(Problem.Integrity("BOLD timing image absent from manifest"))
        check <- ContentCheck.from(Rule, target.path,
          Vector(CheckInput("image", image.path, image.blob)) ++ docs.zipWithIndex.map((f, n) => CheckInput(s"metadata-$n", f.path, f.blob)),
          evaluated.facts ++ Map("timingMetadata" -> MetadataValue.Record(selected.map((k, f) =>
            k -> MetadataValue.Record(Map("value" -> f.value, "origin" -> MetadataValue.Text(f.origin.value)))))),
          evaluated.issues.map(i => Finding("BoldTiming" + i.code, i.severity,
            Some(selected.get(i.field).fold(target.path)(_.origin)), s"${i.field}: ${i.message}")))
      yield check
    }

  private def json(v: MetadataValue): JsonValue = v match
    case MetadataValue.Number(n) => JsonValue.Num(n.toDouble)
    case MetadataValue.Text(s) => JsonValue.Str(s)
    case MetadataValue.Flag(b) => JsonValue.Bool(b)
    case MetadataValue.Null => JsonValue.Null
    case MetadataValue.Array(vs) => JsonValue.Arr(vs.map(json))
    case MetadataValue.Record(vs) => JsonValue.Obj(vs.map((k, value) => k -> json(value)))
