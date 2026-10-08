package sanctity.kernel

enum Severity:
  case Warning, Error

final case class Finding(code: String, severity: Severity, path: Option[RelPath], message: String)

enum MetadataValue:
  case Text(value: String)
  case Number(value: BigDecimal)
  case Flag(value: Boolean)
  case Null
  case Array(values: Vector[MetadataValue])
  case Record(fields: Map[String, MetadataValue])

  /** Canonical tokens for identity and equality, not a lossless source encoding. */
  def tokens: Vector[String] = this match
    case Text(value) => Vector("string", value)
    case Number(value) => Vector("number", value.bigDecimal.stripTrailingZeros.toPlainString)
    case Flag(value) => Vector("boolean", value.toString)
    case Null => Vector("null")
    case Array(values) => Vector("array", values.size.toString) ++ values.flatMap(_.tokens)
    case Record(fields) =>
      Vector("object", fields.size.toString) ++ fields.toVector.sortBy(_._1).flatMap((key, value) => Vector(key) ++ value.tokens)

final case class Contribution(source: RelPath, value: MetadataValue)
final case class EffectiveField(value: MetadataValue, origin: RelPath, history: Vector[Contribution])
final case class ArtifactMetadata(
    path: RelPath,
    fields: Map[String, EffectiveField],
    sources: Vector[RelPath],
    datasetRoot: Option[RelPath]
)

final case class SemanticProfile(name: String, libraryIdentity: String, interpreterIdentity: String, coverage: String):
  def tokens: Vector[String] = Vector(name, libraryIdentity, interpreterIdentity, coverage)

final class Interpretation private (
    val id: InterpretationId,
    val content: ManifestId,
    val profile: SemanticProfile,
    val artifacts: Vector[ArtifactMetadata],
    val facts: Vector[ArtifactFacts],
    val participants: Vector[ParticipantFacts],
    val queryVersion: Option[String],
    val relationships: Option[RelationshipGraph]
):
  lazy val byPath: Map[RelPath, ArtifactMetadata] = artifacts.map(a => a.path -> a).toMap
  def relationshipClosure(roots: Vector[RelPath]): Either[Problem, RelationshipClosure] =
    relationships.toRight(Problem.Unsupported("this interpretation has no recorded relationship graph")).flatMap(_.closure(roots, byPath))

object Interpretation:
  def from(content: ManifestId, profile: SemanticProfile, artifacts: Vector[ArtifactMetadata], facts: Vector[ArtifactFacts] = Vector.empty,
      participants: Vector[ParticipantFacts] = Vector.empty, queryVersion: Option[String] = None,
      relationships: Option[RelationshipGraph] = None): Either[Problem, Interpretation] =
    val paths = artifacts.map(_.path).toSet
    if relationships.exists(g => RelationshipGraph.from(g.version, g.relationships, artifacts).isLeft) then
      Left(Problem.InvalidManifest("relationships do not match interpretation evidence"))
    else if (queryVersion.isEmpty && (facts.nonEmpty || participants.nonEmpty)) || paths.size != artifacts.size || facts.map(_.path).distinct.size != facts.size ||
        participants.map(_.participant).distinct.size != participants.size || facts.exists(f => !paths.contains(f.path)) then Left(Problem.InvalidManifest("duplicate semantic artifact"))
    else
      val ordered = artifacts.sortBy(_.path.value)
      val tokens = ordered.flatMap { artifact =>
        Vector("artifact", artifact.path.value, artifact.datasetRoot.fold("")(_.value), artifact.sources.size.toString) ++
          artifact.sources.map(_.value) ++ Vector(artifact.fields.size.toString) ++
          artifact.fields.toVector.sortBy(_._1).flatMap { (key, field) =>
            Vector(key, field.origin.value) ++ field.value.tokens ++ Vector(field.history.size.toString) ++
              field.history.flatMap(c => Vector(c.source.value) ++ c.value.tokens)
          }
      }
      val queryTokens = queryVersion.toVector.flatMap(v => Vector(v, facts.size.toString) ++
        facts.sortBy(_.path.value).flatMap(_.tokens) ++ Vector(participants.size.toString) ++ participants.sortBy(_.participant).flatMap(_.tokens))
      val domain = if relationships.nonEmpty then "sanctity.interpretation.v3"
        else if queryVersion.isEmpty then "sanctity.interpretation.v1" else "sanctity.interpretation.v2"
      val relationshipTokens = relationships.toVector.flatMap(g => Vector(queryVersion.fold("absent")(_ => "present")) ++ g.tokens)
      val id = InterpretationId(Digest.framed(domain, Vector(content.value.value) ++ profile.tokens ++ tokens ++ queryTokens ++ relationshipTokens))
      Right(new Interpretation(id, content, profile, ordered, facts.sortBy(_.path.value), participants.sortBy(_.participant), queryVersion, relationships))

final class Assessment private (
    val id: AssessmentId,
    val content: ManifestId,
    val interpretation: InterpretationId,
    val policy: String,
    val findings: Vector[Finding]
):
  def errors: Vector[Finding] = findings.filter(_.severity == Severity.Error)
  def admissible: Boolean = errors.isEmpty

object Assessment:
  def from(interpretation: Interpretation, policy: String, findings: Vector[Finding]): Assessment =
    val ordered = findings.sortBy(f => (f.path.fold("")(_.value), f.code, f.message, f.severity.ordinal))
    val tokens = Vector(interpretation.content.value.value, interpretation.id.value.value, policy) ++
      ordered.flatMap(f => Vector(f.path.fold("")(_.value), f.code, f.severity.toString, f.message))
    new Assessment(AssessmentId(Digest.framed("sanctity.assessment.v1", tokens)),
      interpretation.content, interpretation.id, policy, ordered)

final case class FieldDelta(name: String, before: Option[EffectiveField], after: Option[EffectiveField]):
  def valueChanged: Boolean = before.map(_.value) != after.map(_.value)
final case class ArtifactImpact(path: RelPath, fields: Vector[FieldDelta])

object Impact:
  def between(before: Interpretation, after: Interpretation): Vector[ArtifactImpact] =
    (before.byPath.keySet ++ after.byPath.keySet).toVector.sortBy(_.value).flatMap { path =>
      val left = before.byPath.get(path).fold(Map.empty[String, EffectiveField])(_.fields)
      val right = after.byPath.get(path).fold(Map.empty[String, EffectiveField])(_.fields)
      val changes = (left.keySet ++ right.keySet).toVector.sorted.flatMap { name =>
        Option.when(left.get(name) != right.get(name))(FieldDelta(name, left.get(name), right.get(name)))
      }
      Option.when(changes.nonEmpty)(ArtifactImpact(path, changes))
    }
