package sanctity.postgres

import cats.syntax.all.*
import doobie.{ConnectionIO, Update}
import doobie.implicits.*
import doobie.postgres.implicits.*
import sanctity.application.Failure
import sanctity.kernel.*

import java.util.UUID

private[postgres] object Records:
  import JsonCodecs.given

  def uuid[A](id: Id[A]): UUID = UUID.fromString(id.render)
  def lift[A](value: Either[Problem, A]): ConnectionIO[A] = value.leftMap(Failure.apply).liftTo[ConnectionIO]
  def require(condition: Boolean, message: String): ConnectionIO[Unit] =
    lift(Either.cond(condition, (), Problem.Integrity(message)))

  def storeEvidence(activity: Activity): ConnectionIO[Unit] =
    (activity.retained ++ activity.evidence).distinct.traverse_ { b =>
      sql"INSERT INTO blob(digest,size) VALUES(${b.digest.value},${b.size}) ON CONFLICT DO NOTHING".update.run
    }

  def storeManifest(manifest: Manifest): ConnectionIO[Unit] =
    val id = manifest.id.value.value
    val payload = JsonCodecs.encode(manifest)
    for
      inserted <- sql"""INSERT INTO manifest (id, payload, entry_count)
        VALUES ($id, $payload::jsonb, ${manifest.entries.size}) ON CONFLICT (id) DO NOTHING""".update.run
      _ <- if inserted == 0 then loadManifest(id).void else
        val blobs = manifest.files.map(_.blob).distinct.sortBy(_.digest.value).map(b => (b.digest.value, b.size))
        val entries = manifest.entries.map {
          case Entry.Directory(path) => (id, path.value, "directory", Option.empty[UUID], Option.empty[String], Option.empty[Long])
          case Entry.File(path, artifact, blob) => (id, path.value, "file", Some(uuid(artifact)), Some(blob.digest.value), Some(blob.size))
        }
        Update[(String, Long)]("INSERT INTO blob (digest, size) VALUES (?, ?) ON CONFLICT (digest) DO NOTHING")
          .updateMany(blobs) *>
          Update[(String, String, String, Option[UUID], Option[String], Option[Long])](
            "INSERT INTO manifest_entry (manifest_id,path,kind,artifact,digest,size) VALUES (?,?,?,?,?,?)"
          ).updateMany(entries).void
    yield ()

  def loadManifest(id: String): ConnectionIO[Manifest] =
    sql"SELECT payload::text, entry_count FROM manifest WHERE id = $id".query[(String, Int)].option.flatMap {
      case None => lift(Left(Problem.Integrity("missing manifest")))
      case Some((payload, count)) =>
        for
          manifest <- lift(JsonCodecs.decode[Manifest](payload))
          _ <- require(manifest.id.value.value == id && manifest.entries.size == count, "manifest identity or count mismatch")
        yield manifest
    }

  def storeInterpretation(interpretation: Interpretation): ConnectionIO[Unit] =
    val id = interpretation.id.value.value
    val payload = JsonCodecs.encode(interpretation)
    val fields = interpretation.artifacts.flatMap { artifact =>
      artifact.fields.toVector.sortBy(_._1).map { (name, field) =>
        (id, artifact.path.value, name, JsonCodecs.encode(field.value), field.origin.value, JsonCodecs.encode(field.history))
      }
    }
    val artifacts = interpretation.artifacts.map(a => (id, a.path.value, a.datasetRoot.map(_.value), JsonCodecs.encode(a.sources)))
    for
      inserted <- sql"""INSERT INTO interpretation (id,manifest_id,payload,artifact_count,field_count)
        VALUES ($id,${interpretation.content.value.value},$payload::jsonb,${artifacts.size},${fields.size})
        ON CONFLICT (id) DO NOTHING""".update.run
      _ <- if inserted == 0 then loadInterpretation(id).void else
        Update[(String, String, Option[String], String)](
          "INSERT INTO metadata_artifact (interpretation_id,path,dataset_root,sources) VALUES (?,?,?,?::jsonb)"
        ).updateMany(artifacts) *>
          Update[(String, String, String, String, String, String)](
            "INSERT INTO metadata_field (interpretation_id,path,name,value,origin,history) VALUES (?,?,?,?::jsonb,?,?::jsonb)"
          ).updateMany(fields) *> ScientificSql.store(interpretation)
    yield ()

  def loadInterpretation(id: String): ConnectionIO[Interpretation] =
    sql"""SELECT manifest_id,payload::text,artifact_count,field_count FROM interpretation WHERE id = $id"""
      .query[(String, String, Int, Int)].option.flatMap {
        case None => lift(Left(Problem.Integrity("missing interpretation")))
        case Some((manifest, payload, artifacts, fields)) =>
          for
            interpretation <- lift(JsonCodecs.decode[Interpretation](payload))
            _ <- require(interpretation.id.value.value == id && interpretation.content.value.value == manifest &&
              interpretation.artifacts.size == artifacts && interpretation.artifacts.map(_.fields.size).sum == fields,
              "interpretation identity or count mismatch")
          yield interpretation
      }

  def storeAssessment(assessment: Assessment): ConnectionIO[Unit] =
    val payload = JsonCodecs.encode(assessment)
    sql"""INSERT INTO assessment (id,manifest_id,interpretation_id,admissible,payload)
      VALUES (${assessment.id.value.value},${assessment.content.value.value},${assessment.interpretation.value.value},
        ${assessment.admissible},$payload::jsonb) ON CONFLICT (id) DO NOTHING""".update.run.void

  def loadAssessment(id: String, interpretation: Interpretation): ConnectionIO[Assessment] =
    sql"SELECT payload::text,admissible FROM assessment WHERE id=$id".query[(String, Boolean)].option.flatMap {
      case None => lift(Left(Problem.Integrity("missing assessment")))
      case Some((payload, admissible)) =>
        for
          assessment <- lift(JsonCodecs.assessment(payload, interpretation))
          _ <- require(assessment.id.value.value == id && assessment.admissible == admissible, "assessment identity or readiness mismatch")
        yield assessment
    }

  def indexesReady(manifest: Manifest, interpretation: Interpretation): ConnectionIO[Unit] =
    for
      _ <- ScientificSql.ready(interpretation)
      entries <- sql"SELECT count(*) FROM manifest_entry WHERE manifest_id=${manifest.id.value.value}".query[Long].unique
      artifacts <- sql"SELECT count(*) FROM metadata_artifact WHERE interpretation_id=${interpretation.id.value.value}".query[Long].unique
      fields <- sql"SELECT count(*) FROM metadata_field WHERE interpretation_id=${interpretation.id.value.value}".query[Long].unique
      _ <- require(entries == manifest.entries.size && artifacts == interpretation.artifacts.size &&
        fields == interpretation.artifacts.map(_.fields.size.toLong).sum, "required manifest or semantic projection is incomplete")
    yield ()

  def metadata(interpretationId: String, path: RelPath): ConnectionIO[ArtifactMetadata] =
    sql"""SELECT dataset_root,sources::text FROM metadata_artifact
      WHERE interpretation_id=$interpretationId AND path=${path.value}""".query[(Option[String], String)].option.flatMap {
      case None => lift(Left(Problem.NotFound("interpreted artifact", path.value)))
      case Some((root, sources)) =>
        for
          datasetRoot <- lift(root.traverse(RelPath.from))
          sourcePaths <- lift(JsonCodecs.decode[Vector[RelPath]](sources))
          rows <- sql"""SELECT name,value::text,origin,history::text FROM metadata_field
            WHERE interpretation_id=$interpretationId AND path=${path.value} ORDER BY name"""
            .query[(String, String, String, String)].to[Vector]
          fields <- rows.traverse { (name, value, origin, history) =>
            for
              decoded <- lift(JsonCodecs.decode[MetadataValue](value))
              from <- lift(RelPath.from(origin))
              contributions <- lift(JsonCodecs.decode[Vector[Contribution]](history))
            yield name -> EffectiveField(decoded, from, contributions)
          }
        yield ArtifactMetadata(path, fields.toMap, sourcePaths, datasetRoot)
    }
