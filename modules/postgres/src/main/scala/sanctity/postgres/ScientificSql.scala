package sanctity.postgres

import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment, Update}
import doobie.implicits.*
import sanctity.kernel.*

/** Parameterized correlated EXISTS predicates; alias names are compiler-generated only. */
private[postgres] object ScientificSql:
  import JsonCodecs.given
  private def all(parts: Vector[Fragment]): Fragment =
    parts.reduceOption((a, b) => fr"(" ++ a ++ fr") AND (" ++ b ++ fr")").getOrElse(fr"TRUE")
  private def col(alias: String, name: String): Fragment = Fragment.const(s"$alias.$name")

  private def artifact(p: ArtifactPredicate, alias: String): Fragment = p match
    case ArtifactPredicate.All(ps) => all(ps.map(artifact(_, alias)))
    case ArtifactPredicate.Suffix(v) => col(alias, "suffix") ++ fr"= $v"
    case ArtifactPredicate.Entity(n, v) =>
      fr"EXISTS(SELECT 1 FROM scientific_entity e WHERE e.interpretation_id=" ++ col(alias, "interpretation_id") ++
        fr"AND e.path=" ++ col(alias, "path") ++ fr"AND e.name=$n AND e.value=$v)"
    case ArtifactPredicate.MetadataEquals(n, v) =>
      fr"EXISTS(SELECT 1 FROM scientific_value v WHERE v.interpretation_id=" ++ col(alias, "interpretation_id") ++
        fr"AND v.path=" ++ col(alias, "path") ++ fr"AND v.name=$n AND v.text_value=$v)"
    case ArtifactPredicate.MetadataBelow(n, v) =>
      fr"EXISTS(SELECT 1 FROM scientific_value v WHERE v.interpretation_id=" ++ col(alias, "interpretation_id") ++
        fr"AND v.path=" ++ col(alias, "path") ++ fr"AND v.name=$n AND v.number_value < $v)"

  private def participant(p: ParticipantPredicate): Fragment = p match
    case ParticipantPredicate.All(ps) => all(ps.map(participant))
    case ParticipantPredicate.FieldEquals(n, v) =>
      fr"""EXISTS(SELECT 1 FROM participant_value p WHERE p.interpretation_id=a.interpretation_id
        AND p.participant=a.participant AND p.name=$n AND p.lexical_value=$v)"""
    case ParticipantPredicate.FieldAtLeast(n, v) =>
      fr"""EXISTS(SELECT 1 FROM participant_value p WHERE p.interpretation_id=a.interpretation_id
        AND p.participant=a.participant AND p.name=$n AND p.number_value >= $v)"""
    case ParticipantPredicate.HasArtifact(p) =>
      fr"""EXISTS(SELECT 1 FROM scientific_artifact b WHERE b.interpretation_id=a.interpretation_id
        AND b.participant=a.participant AND""" ++ artifact(p, "b") ++ fr")"
    case ParticipantPredicate.HasSession(ps) =>
      fr"""EXISTS(SELECT 1 FROM scientific_artifact s WHERE s.interpretation_id=a.interpretation_id
        AND s.participant=a.participant AND""" ++ all(ps.map { p =>
        fr"""EXISTS(SELECT 1 FROM scientific_artifact b WHERE b.interpretation_id=s.interpretation_id
          AND b.participant=s.participant AND b.session IS NOT DISTINCT FROM s.session AND""" ++ artifact(p, "b") ++ fr")"
      }) ++ fr")"

  def select(at: ReleaseRef, interpretation: Interpretation, query: ScientificQuery): ConnectionIO[Vector[SelectionHit]] =
    for
      _ <- Records.lift(query.validate)
      _ <- Records.lift(Either.cond(interpretation.queryVersion.contains("scientific-query-v1"), (),
        Problem.Unsupported("this interpretation has no qualified scientific query index")))
      rows <- selectionStatement(interpretation, query).query[(String, String, Option[String])].to[Vector]
      hits <- rows.traverse((p, subject, session) => Records.lift(RelPath.from(p)).map(SelectionHit(at, _, subject, session)))
    yield hits

  private def selectionStatement(i: Interpretation, q: ScientificQuery): Fragment =
    fr"""SELECT a.path,a.participant,a.session FROM scientific_artifact a
      WHERE a.interpretation_id=${i.id.value.value} AND (""" ++ participant(q.participants) ++
      fr") AND (" ++ artifact(q.artifacts, "a") ++ fr") ORDER BY a.path"

  /** Package-private qualification seam: EXPLAIN the exact production statement and parameters. */
  def explainSelection(i: Interpretation, q: ScientificQuery): ConnectionIO[String] =
    Records.lift(q.validate) *> (fr"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)" ++ selectionStatement(i, q)).query[String].unique

  def observations(at: ReleaseRef, i: Interpretation, q: ObservationQuery): ConnectionIO[Vector[ObservationHit]] =
    observationStatement(i, q).query[String].to[Vector].flatMap(_.traverse(p => Records.lift(JsonCodecs.decode[ScientificObservation](p)).map(ObservationHit(at, _))))

  def explainObservations(i: Interpretation, q: ObservationQuery): ConnectionIO[String] =
    Records.lift(Either.cond(q.valid, (), Problem.Unsupported("invalid observation query"))) *>
      (fr"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)" ++ observationStatement(i, q)).query[String].unique

  private def observationStatement(i: Interpretation, q: ObservationQuery): Fragment =
    val sources = q.inputs.map(s => fr"source_key = ${Digest.framed("sanctity.released-artifact.v1", s.tokens).value}")
      .reduce((a, b) => fr"(" ++ a ++ fr") OR (" ++ b ++ fr")")
    val unit = q.metric.unit.fold(fr"unit IS NULL")(u => fr"unit=$u")
    val bounds = q.atLeast.toVector.map(v => fr"value >= $v") ++ q.below.toVector.map(v => fr"value < $v")
    (fr"SELECT payload::text FROM scientific_observation WHERE interpretation_id=${i.id.value.value} AND" ++
      all(Vector(fr"metric_name=${q.metric.name}", fr"metric_version=${q.metric.version}", unit, sources) ++ bounds) ++
      fr"ORDER BY ordinal")

  def store(i: Interpretation): ConnectionIO[Unit] =
    val id = i.id.value.value
    val artifacts = i.facts.map(f => (id, f.path.value, f.participant, f.session, f.suffix, f.extension))
    val entities = i.facts.flatMap(f => f.entities.toVector.map((n, v) => (id, f.path.value, n, v)))
    val values = i.artifacts.flatMap(a => a.fields.toVector.map { (n, f) =>
      val text = f.value match
        case MetadataValue.Text(v) => Some(v)
        case _ => None
      val number = f.value match
        case MetadataValue.Number(v) => Some(v)
        case _ => None
      (id, a.path.value, n, text, number)
    })
    val participants = i.participants.flatMap(p => p.fields.toVector.map((n, v) => (id, p.participant, n, v, ScientificNumber.parse(v))))
    Update[(String, String, String, Option[String], String, String)](
      "INSERT INTO scientific_artifact(interpretation_id,path,participant,session,suffix,extension) VALUES(?,?,?,?,?,?)").updateMany(artifacts) *>
      Update[(String, String, String, String)](
        "INSERT INTO scientific_entity(interpretation_id,path,name,value) VALUES(?,?,?,?)").updateMany(entities) *>
      Update[(String, String, String, Option[String], Option[BigDecimal])](
        "INSERT INTO scientific_value(interpretation_id,path,name,text_value,number_value) VALUES(?,?,?,?,?)").updateMany(values) *>
      Update[(String, String, String, String, Option[BigDecimal])](
        "INSERT INTO participant_value(interpretation_id,participant,name,lexical_value,number_value) VALUES(?,?,?,?,?)").updateMany(participants) *>
      Update[(String, Int, String, String, String, Option[String], BigDecimal, String)](
        "INSERT INTO scientific_observation(interpretation_id,ordinal,source_key,metric_name,metric_version,unit,value,payload) VALUES(?,?,?,?,?,?,?,?::jsonb)")
        .updateMany(i.observations.zipWithIndex.map { (o, index) =>
          (id, index, Digest.framed("sanctity.released-artifact.v1", o.source.tokens).value, o.metric.name, o.metric.version, o.metric.unit, o.value, JsonCodecs.encode(o))
        }).void

  def ready(i: Interpretation): ConnectionIO[Unit] =
    for
      observations <- sql"SELECT count(*) FROM scientific_observation WHERE interpretation_id=${i.id.value.value}".query[Long].unique
      _ <- Records.require(observations == i.observations.size, "observation projection is incomplete")
      _ <- if i.queryVersion.isEmpty then ().pure[ConnectionIO] else readyArtifacts(i)
    yield ()

  private def readyArtifacts(i: Interpretation): ConnectionIO[Unit] =
    for
      facts <- sql"SELECT count(*) FROM scientific_artifact WHERE interpretation_id=${i.id.value.value}".query[Long].unique
      entities <- sql"SELECT count(*) FROM scientific_entity WHERE interpretation_id=${i.id.value.value}".query[Long].unique
      values <- sql"SELECT count(*) FROM scientific_value WHERE interpretation_id=${i.id.value.value}".query[Long].unique
      participants <- sql"SELECT count(*) FROM participant_value WHERE interpretation_id=${i.id.value.value}".query[Long].unique
      _ <- Records.require(facts == i.facts.size && entities == i.facts.map(_.entities.size.toLong).sum &&
        values == i.artifacts.map(_.fields.size.toLong).sum && participants == i.participants.map(_.fields.size.toLong).sum,
        "scientific query projection is incomplete")
    yield ()
