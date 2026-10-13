package sanctity.kernel

sealed trait RunTag
type RunId = Id[RunTag]

/** Descriptor interpreted by a registered, versioned input planner. */
final case class InputContract(name: String, version: String, parameters: Map[String, String] = Map.empty):
  def tokens: Vector[String] = Vector(name, version, parameters.size.toString) ++ parameters.toVector.sorted.flatMap((k, v) => Vector(k, v))
  def id: Digest = Digest.framed("sanctity.input-contract.v1", tokens)
  def valid: Boolean = name.matches("[a-z][a-z0-9.-]{0,99}") && version.matches("[a-zA-Z0-9.-]{1,100}") &&
    parameters.size <= 32 && parameters.forall((k, v) => k.matches("[a-z][a-z0-9.-]{0,99}") && v.length <= 1024 && !v.exists(_.isControl))

enum RequirementStatus:
  case Satisfied, Missing, Ambiguous, Unsupported, Disallowed

final case class InputRequirement(code: String, target: Option[RelPath], status: RequirementStatus,
    evidence: Vector[RelPath], message: String):
  def tokens: Vector[String] = Vector(code, target.fold("")(_.value), status.toString, evidence.size.toString) ++
    evidence.map(_.value) ++ Vector(message)

/** Complete historical explanation, bound to the exact scientific interpretation and assessment. */
final case class InputReadiness(content: ManifestId, interpretation: InterpretationId, assessment: AssessmentId,
    contract: InputContract, roots: Vector[RelPath], requirements: Vector[InputRequirement]):
  def valid: Boolean = contract.valid && roots.nonEmpty && roots.size <= 100 && roots == roots.distinct.sortBy(_.value) &&
    requirements.nonEmpty && requirements.size <= 2000 && requirements.forall(r =>
      r.code.matches("[a-z][a-z0-9.-]{0,99}") && r.evidence.size <= 1000 && r.evidence == r.evidence.distinct.sortBy(_.value) && r.message.length <= 4096)
  def ready: Boolean = valid && requirements.forall(_.status == RequirementStatus.Satisfied)
  def id: Digest = Digest.framed("sanctity.input-readiness.v1", Vector(content.value.value, interpretation.value.value,
    assessment.value.value, contract.id.value, roots.size.toString) ++ roots.map(_.value) ++
    Vector(requirements.size.toString) ++ requirements.flatMap(r => Vector(r.tokens.size.toString) ++ r.tokens))

/** Path-sensitive scientific bytes, independent of repository artifact allocation. */
object ContentTree:
  def identity(manifest: Manifest): Digest = Digest.framed("sanctity.content-tree.v1", manifest.entries.flatMap {
    case Entry.Directory(path) => Vector("directory", path.value)
    case Entry.File(path, _, blob) => Vector("file", path.value, blob.digest.value, blob.size.toString)
  })

/** A processing bundle is an ordinary accepted view carrying this additional contract. */
final case class BundleEvidence(readiness: InputReadiness, tree: Digest, projection: Digest):
  def id: Digest = Digest.framed("sanctity.processing-bundle.v1", Vector(readiness.id.value, tree.value, projection.value))
  def matches(manifest: Manifest, interpretation: Interpretation, assessment: Assessment, projected: Option[ProjectionEvidence]): Boolean =
    readiness.ready && readiness.content == manifest.id && readiness.interpretation == interpretation.id &&
      readiness.assessment == assessment.id && readiness.roots.forall(manifest.byPath.contains) &&
      tree == ContentTree.identity(manifest) && projected.flatMap(_.receipt).exists(_.id == projection)

final case class ProducerIdentity(name: String, version: String, code: BlobRef, runtime: String):
  def valid: Boolean = Vector(name, version, runtime).forall(s => s.nonEmpty && s.length <= 256 && !s.exists(_.isControl))
  def tokens: Vector[String] = Vector(name, version, code.digest.value, code.size.toString, runtime)
final case class OutputFile(path: RelPath, blob: BlobRef)

/** Producer declaration over exact returned bytes. A successful process alone is not admission. */
final case class ProcessingEvidence(invocation: RunId, input: ReleaseRef, bundle: Digest,
    producer: ProducerIdentity, parameters: Map[String, String], expected: Vector[OutputFile], targets: Vector[RelPath], request: BlobRef):
  def valid: Boolean = producer.valid && parameters.size <= 32 && parameters.forall((k, v) =>
    k.matches("[a-z][a-z0-9.-]{0,99}") && v.length <= 1024 && !v.exists(_.isControl)) &&
    targets.nonEmpty && targets.size <= 100 && targets == targets.distinct.sortBy(_.value) &&
    expected.nonEmpty && expected.size <= 1000 && expected.forall(_.blob.size < Long.MaxValue) && expected.map(_.path).distinct.size == expected.size &&
    expected.map(_.path.collisionKey).distinct.size == expected.size
  def id: Digest = Digest.framed("sanctity.processing-receipt.v1", Vector(invocation.render, input.dataset.render,
    input.release.render, bundle.value, request.digest.value, request.size.toString) ++ producer.tokens ++ Vector(parameters.size.toString) ++
    parameters.toVector.sorted.flatMap((k, v) => Vector(k, v)) ++ Vector(targets.size.toString) ++ targets.map(_.value) ++ Vector(expected.size.toString) ++
    expected.sortBy(_.path.value).flatMap(f => Vector(f.path.value, f.blob.digest.value, f.blob.size.toString)))
  def matches(manifest: Manifest): Boolean = valid &&
    expected.map(f => f.path -> f.blob).toMap == manifest.files.map(f => f.path -> f.blob).toMap
  def validateInput(release: Release): Either[Problem, Unit] =
    Either.cond(release.ref == input && release.activity.bundle.exists(b => b.id == bundle &&
      b.matches(release.manifest, release.interpretation, release.assessment, release.activity.projection) &&
      targets.forall(b.readiness.roots.contains)), (),
      Problem.Conflict("processing receipt does not identify an accepted input bundle"))
