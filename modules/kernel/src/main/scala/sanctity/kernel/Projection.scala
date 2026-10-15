package sanctity.kernel

/** Column/field disclosure is explicit; this is not a de-identification certificate.
  * None means event tables are not authorized by this profile.
  */
final case class DisclosureProfile(participantFields: Set[String], metadataFields: Set[String],
    eventFields: Option[Set[String]] = None, sessionFields: Set[String] = Set.empty,
    scanFields: Set[String] = Set.empty, behaviouralFields: Option[Set[String]] = None):
  val id: Digest =
    def fields(values: Set[String]): Vector[String] = Vector(values.size.toString) ++ values.toVector.sorted
    val original = fields(participantFields) ++ fields(metadataFields)
    if behaviouralFields.nonEmpty then
      Digest.framed("sanctity.scientific-view.v3", original ++ Vector(eventFields.fold("absent")(_ => "present")) ++
        eventFields.toVector.flatMap(fields) ++ fields(sessionFields) ++ fields(scanFields) ++
        Vector("behavioural", "present") ++ behaviouralFields.toVector.flatMap(fields))
    else if eventFields.isEmpty && sessionFields.isEmpty && scanFields.isEmpty then
      Digest.framed("sanctity.mri-view.v1", original)
    else Digest.framed("sanctity.mri-view.v2", original ++ Vector(eventFields.fold("absent")(_ => "present")) ++
      eventFields.toVector.flatMap(fields) ++ fields(sessionFields) ++ fields(scanFields))

final case class ProjectionEvidence(
    source: ReleaseRef,
    selection: SelectionId,
    profile: Digest,
    compiler: String,
    generated: Vector[RelPath],
    receipt: Option[ProjectionReceipt] = None
):
  def binding: Digest = Digest.framed("sanctity.projection-spec.v1",
    Vector(source.dataset.render, source.release.render, selection.value.value, profile.value, compiler,
      generated.size.toString) ++ generated.map(_.value).sorted)
  def matches(manifest: Manifest): Boolean = receipt.forall(r => r.specification == binding && r.matches(manifest))

/** An output's exact contributing source bytes. Roles explain why those inputs were used. */
final case class ProjectionStep(output: RelPath, operation: String, inputs: Vector[CheckInput])
final case class ProjectionOutput(step: ProjectionStep, blob: BlobRef)
final case class ProjectionDecision(path: RelPath, disposition: String, outputs: Vector[RelPath])

final class ProjectionReceipt private (
    val id: Digest, val sourceContent: ManifestId, val sourceInterpretation: InterpretationId,
    val output: ManifestId, val specification: Digest, val files: Vector[ProjectionOutput]
):
  def matches(manifest: Manifest): Boolean = output == manifest.id &&
    files.map(f => f.step.output -> f.blob).toMap == manifest.files.map(f => f.path -> f.blob).toMap
  override def equals(other: Any): Boolean = other match
    case r: ProjectionReceipt => id == r.id
    case _ => false
  override def hashCode(): Int = id.hashCode()

object ProjectionReceipt:
  def from(source: Manifest, interpretation: InterpretationId, output: Manifest,
      steps: Vector[ProjectionStep], specification: Digest): Either[Problem, ProjectionReceipt] =
    val files = output.files.map(f => f.path -> f.blob).toMap
    for
      _ <- Either.cond(steps.map(_.output).toSet == files.keySet &&
        steps.forall(_.inputs.forall(i => source.byPath.get(i.path).exists {
          case Entry.File(_, _, blob) => blob == i.blob
          case _ => false
        })), (), Problem.Integrity("projection steps do not bind source and output files"))
      result <- checked(source.id, interpretation, output.id, specification, steps.map(s => ProjectionOutput(s, files(s.output))))
    yield result

  /** Decoder seam: validates structure and recomputes identity; admission rechecks output membership. */
  def checked(source: ManifestId, interpretation: InterpretationId, output: ManifestId, specification: Digest,
      files: Vector[ProjectionOutput]): Either[Problem, ProjectionReceipt] =
    val ordered = files.map(f => f.copy(step = f.step.copy(inputs = f.step.inputs.sortBy(i => (i.role, i.path.value)))))
      .sortBy(_.step.output.value)
    val valid = ordered.nonEmpty && ordered.size <= 10000 && ordered.map(_.step.output).distinct.size == ordered.size &&
      ordered.map(_.step.inputs.size.toLong).sum <= 100000 && ordered.forall { f =>
        val s = f.step
        s.operation.matches("[a-z][a-z0-9.-]{0,99}") && s.inputs.nonEmpty && s.inputs.size <= 256 &&
          s.inputs.distinct.size == s.inputs.size && s.inputs.forall(i => i.role.matches("[a-z][a-z0-9.-]{0,99}")) &&
          (s.operation != "copy" || (s.inputs.size == 1 && s.inputs.head.blob == f.blob))
      }
    val tokens = Vector(source.value.value, interpretation.value.value, output.value.value, specification.value, ordered.size.toString) ++
      ordered.flatMap { f =>
        Vector(f.step.output.value, f.blob.digest.value, f.blob.size.toString, f.step.operation, f.step.inputs.size.toString) ++
          f.step.inputs.flatMap(i => Vector(i.role, i.path.value, i.blob.digest.value, i.blob.size.toString))
      }
    Either.cond(valid, new ProjectionReceipt(Digest.framed("sanctity.projection-receipt.v1", tokens),
      source, interpretation, output, specification, ordered), Problem.InvalidManifest("invalid projection receipt"))

final case class ProjectionPlan(retained: Vector[Entry.File], generated: Vector[(RelPath, String)],
    evidence: ProjectionEvidence, steps: Vector[ProjectionStep] = Vector.empty,
    decisions: Vector[ProjectionDecision] = Vector.empty)
