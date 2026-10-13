package sanctity.kernel

/** A scientific input is qualified by both its retained release and interpretation. */
final case class ReleasedArtifact(at: ReleaseRef, interpretation: InterpretationId, assessment: AssessmentId,
    path: RelPath, blob: BlobRef):
  def tokens: Vector[String] = Vector(at.dataset.render, at.release.render, interpretation.value.value,
    assessment.value.value, path.value, blob.digest.value, blob.size.toString)
  def matches(release: Release): Boolean = release.ref == at && release.interpretation.id == interpretation &&
    release.assessment.id == assessment && release.manifest.byPath.get(path).exists {
      case Entry.File(_, _, b) => b == blob
      case _ => false
    }

final case class MetricDefinition(name: String, version: String, unit: Option[String]):
  def valid: Boolean = name.matches("[a-z][a-z0-9.-]{0,99}") && version.matches("[a-zA-Z0-9.-]{1,100}") &&
    unit.forall(u => u.nonEmpty && u.length <= 64 && !u.exists(_.isControl))
  def tokens: Vector[String] = Vector(name, version, unit.nonEmpty.toString) ++ unit.toVector

/** Only assessed observations enter this index; execution declarations remain in the activity. */
final case class ScientificObservation(source: ReleasedArtifact, metric: MetricDefinition, value: BigDecimal,
    document: RelPath, checkRule: String):
  def tokens: Vector[String] = source.tokens ++ metric.tokens ++ Vector(value.bigDecimal.stripTrailingZeros.toPlainString, document.value, checkRule)
  def valid: Boolean = metric.valid && checkRule.nonEmpty && checkRule.length <= 200 && value.precision <= 100 && value.scale >= -100 && value.scale <= 100

final case class ObservationQuery(inputs: Vector[ReleasedArtifact], metric: MetricDefinition,
    atLeast: Option[BigDecimal] = None, below: Option[BigDecimal] = None):
  def valid: Boolean = metric.valid && inputs.nonEmpty && inputs.size <= 100 && inputs.distinct.size == inputs.size &&
    (atLeast.toVector ++ below).forall(n => n.precision <= 100 && n.scale >= -100 && n.scale <= 100)
  def matches(o: ScientificObservation): Boolean = inputs.contains(o.source) && o.metric == metric &&
    atLeast.forall(o.value >= _) && below.forall(o.value < _)
final case class ObservationHit(at: ReleaseRef, observation: ScientificObservation)
final case class DerivativeCoverage(input: ReleasedArtifact, results: Vector[ObservationHit])

object DerivativeEvidence:
  def validate(activity: Activity, interpretation: Interpretation, assessment: Assessment, sources: Vector[Release]): Either[Problem, Unit] =
    val external = assessment.checks.flatMap(_.external)
    val allowed = activity.processing.toVector.map(_.input)
    val bound = external.forall(i => allowed.contains(i.at) && sources.exists(i.matches))
    val observations = interpretation.observations.forall { o =>
      o.valid && assessment.checks.exists(c => c.target == o.document && c.rule == o.checkRule &&
        c.external.contains(o.source) && c.facts.get(o.metric.name).contains(MetadataValue.Number(o.value)))
    }
    Either.cond(bound && observations, (), Problem.Integrity("derivative observations or source evidence are not bound to accepted inputs"))
