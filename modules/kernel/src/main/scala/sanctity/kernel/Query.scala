package sanctity.kernel

import scala.util.Try

final case class ArtifactFacts(path: RelPath, participant: String, session: Option[String],
    suffix: String, extension: String, entities: Map[String, String]):
  def tokens: Vector[String] = Vector(path.value, participant, session.getOrElse(""), suffix, extension,
    entities.size.toString) ++ entities.toVector.sorted.flatMap((k, v) => Vector(k, v))

/** Lexical values remain intact. Numeric comparisons do not coerce missing or censored values. */
final case class ParticipantFacts(participant: String, fields: Map[String, String]):
  def tokens: Vector[String] = Vector(participant, fields.size.toString) ++ fields.toVector.sorted.flatMap((k, v) => Vector(k, v))

object ScientificNumber:
  def parse(value: String): Option[BigDecimal] =
    Option.when(value.length <= 100 && value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))(value)
      .flatMap(v => Try(BigDecimal(v)).toOption)

enum ArtifactPredicate:
  case All(conditions: Vector[ArtifactPredicate])
  case Entity(name: String, value: String)
  case Suffix(value: String)
  case MetadataEquals(name: String, value: String)
  case MetadataBelow(name: String, value: BigDecimal)

enum ParticipantPredicate:
  case All(conditions: Vector[ParticipantPredicate])
  case FieldEquals(name: String, value: String)
  case FieldAtLeast(name: String, value: BigDecimal)
  case HasArtifact(condition: ArtifactPredicate)
  /** Every condition must match within one session, including the single session-less group. */
  case HasSession(conditions: Vector[ArtifactPredicate])

final case class ScientificQuery(
    participants: ParticipantPredicate = ParticipantPredicate.All(Vector.empty),
    artifacts: ArtifactPredicate = ArtifactPredicate.All(Vector.empty)
):
  def validate: Either[Problem, Unit] =
    def artifact(p: ArtifactPredicate, depth: Int): Int =
      if depth > 16 then 1000 else p match
        case ArtifactPredicate.All(ps) => 1 + ps.map(artifact(_, depth + 1)).sum
        case _ => 1
    def participant(p: ParticipantPredicate, depth: Int): Int =
      if depth > 16 then 1000 else p match
        case ParticipantPredicate.All(ps) => 1 + ps.map(participant(_, depth + 1)).sum
        case ParticipantPredicate.HasArtifact(p) => 1 + artifact(p, depth + 1)
        case ParticipantPredicate.HasSession(ps) => 1 + ps.map(artifact(_, depth + 1)).sum
        case _ => 1
    Either.cond(participant(participants, 0) + artifact(artifacts, 0) <= 256, (),
      Problem.LimitExceeded("query supports at most 256 nodes and depth 16"))

final case class SelectionHit(at: ReleaseRef, path: RelPath, participant: String, session: Option[String])
final case class SelectionId(value: Digest)

/** Immutable resolved membership. All contributing releases are reauthorized on retrieval. */
final class FrozenSelection private (
    val id: SelectionId, val releases: Vector[ReleaseRef], val query: ScientificQuery, val hits: Vector[SelectionHit]
):
  def page(offset: Int, limit: Int): Either[Problem, Vector[SelectionHit]] =
    Either.cond(offset >= 0 && limit > 0 && limit <= 1000, hits.drop(offset).take(limit),
      Problem.LimitExceeded("selection pages require a nonnegative offset and limit 1–1000"))

object FrozenSelection:
  def from(releases: Vector[ReleaseRef], query: ScientificQuery, hits: Vector[SelectionHit]): Either[Problem, FrozenSelection] =
    for
      _ <- query.validate
      _ <- Either.cond(releases.nonEmpty && releases.size <= 100 && releases.distinct.size == releases.size,
        (), Problem.LimitExceeded("selection requires 1–100 distinct pinned releases"))
      _ <- Either.cond(hits.size <= 1000000 && hits.forall(h => releases.contains(h.at)) &&
        hits.map(h => h.at -> h.path).distinct.size == hits.size, (), Problem.Integrity("invalid selection membership"))
    yield
      val refs = releases.sortBy(r => (r.dataset.render, r.release.render))
      val rows = hits.sortBy(h => (h.at.dataset.render, h.at.release.render, h.path.value))
      // Query syntax has a dedicated length-framed traversal; toString is never an identity contract.
      def ap(p: ArtifactPredicate): Vector[String] = p match
        case ArtifactPredicate.All(ps) => Vector("all", ps.size.toString) ++ ps.flatMap(ap)
        case ArtifactPredicate.Entity(n, v) => Vector("entity", n, v)
        case ArtifactPredicate.Suffix(v) => Vector("suffix", v)
        case ArtifactPredicate.MetadataEquals(n, v) => Vector("text", n, v)
        case ArtifactPredicate.MetadataBelow(n, v) => Vector("below", n, v.bigDecimal.stripTrailingZeros.toPlainString)
      def pp(p: ParticipantPredicate): Vector[String] = p match
        case ParticipantPredicate.All(ps) => Vector("all", ps.size.toString) ++ ps.flatMap(pp)
        case ParticipantPredicate.FieldEquals(n, v) => Vector("field", n, v)
        case ParticipantPredicate.FieldAtLeast(n, v) => Vector("atleast", n, v.bigDecimal.stripTrailingZeros.toPlainString)
        case ParticipantPredicate.HasArtifact(p) => Vector("artifact") ++ ap(p)
        case ParticipantPredicate.HasSession(ps) => Vector("session", ps.size.toString) ++ ps.flatMap(ap)
      val tokens = Vector(refs.size.toString) ++ refs.flatMap(r => Vector(r.dataset.render, r.release.render)) ++
        pp(query.participants) ++ ap(query.artifacts) ++ Vector(rows.size.toString) ++
        rows.flatMap(h => Vector(h.at.dataset.render, h.at.release.render, h.path.value, h.participant, h.session.getOrElse("")))
      new FrozenSelection(SelectionId(Digest.framed("sanctity.selection.v1", tokens)), refs, query, rows)

object ReferenceQuery:
  def evaluate(at: ReleaseRef, interpretation: Interpretation, query: ScientificQuery): Either[Problem, Vector[SelectionHit]] =
    for
      _ <- query.validate
      _ <- Either.cond(interpretation.queryVersion.contains("scientific-query-v1"), (),
        Problem.Unsupported("this interpretation has no qualified scientific query index"))
    yield
      def artifact(f: ArtifactFacts, p: ArtifactPredicate): Boolean = p match
        case ArtifactPredicate.All(ps) => ps.forall(artifact(f, _))
        case ArtifactPredicate.Entity(n, v) => f.entities.get(n).contains(v)
        case ArtifactPredicate.Suffix(v) => f.suffix == v
        case ArtifactPredicate.MetadataEquals(n, v) =>
          interpretation.byPath.get(f.path).flatMap(_.fields.get(n)).exists(_.value == MetadataValue.Text(v))
        case ArtifactPredicate.MetadataBelow(n, v) =>
          interpretation.byPath.get(f.path).flatMap(_.fields.get(n)).exists(_.value match
            case MetadataValue.Number(found) => found < v
            case _ => false)
      val fields = interpretation.participants.map(p => p.participant -> p.fields).toMap
      val groups = interpretation.facts.groupBy(_.participant)
      def participant(id: String, rows: Vector[ArtifactFacts], p: ParticipantPredicate): Boolean = p match
        case ParticipantPredicate.All(ps) => ps.forall(participant(id, rows, _))
        case ParticipantPredicate.FieldEquals(n, v) => fields.get(id).flatMap(_.get(n)).contains(v)
        case ParticipantPredicate.FieldAtLeast(n, v) => fields.get(id).flatMap(_.get(n)).flatMap(ScientificNumber.parse).exists(_ >= v)
        case ParticipantPredicate.HasArtifact(p) => rows.exists(artifact(_, p))
        case ParticipantPredicate.HasSession(ps) => rows.groupBy(_.session).values.exists(s => ps.forall(p => s.exists(artifact(_, p))))
      interpretation.facts.filter(f => participant(f.participant, groups(f.participant), query.participants) &&
        artifact(f, query.artifacts)).map(f => SelectionHit(at, f.path, f.participant, f.session))
