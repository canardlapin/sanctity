package sanctity.bids

import bids4s.CheckedTsv
import cats.syntax.all.*
import sanctity.kernel.*

/** Shared projection of participant, session and scan summaries for selected physical roots. */
private[bids] object SummaryProjection:
  def paths(source: Release, recordings: Vector[ArtifactFacts]): Either[Problem, Vector[RelPath]] =
    val names = Vector("participants.tsv") ++ recordings.map(f => s"${f.participant}/${f.participant}_sessions.tsv") ++
      recordings.map(f => f.participant + f.session.fold("")("/" + _) + "/" + f.participant + f.session.fold("")("_" + _) + "_scans.tsv")
    names.distinct.traverse(RelPath.from).map(_.filter(source.manifest.byPath.contains).sortBy(_.value))

  def project(source: Release, profile: DisclosureProfile, documents: Map[RelPath, String],
      recordings: Vector[ArtifactFacts], paths: Vector[RelPath], emit: (RelPath, String, String, Vector[RelPath]) => Unit,
      json: (RelPath, Map[String, MetadataValue], Vector[RelPath]) => Unit): Either[Problem, Unit] =
    val participants = recordings.map(_.participant).distinct.sorted
    def require(ok: Boolean, detail: String): Either[Problem, Unit] = Either.cond(ok, (), Problem.Unsupported(detail))
    def table(path: RelPath, key: String, mask: Set[String], required: Set[String]): Either[Problem, Unit] =
      for
        parsed <- CheckedTsv.parse(documents(path)).leftMap(e => Problem.Unsupported(s"${path.value}: ${e.message} at row ${e.row}"))
        t = parsed.table
        _ <- require(t.columns.headOption.contains(key) && t.rows.forall(_.head.nonEmpty) &&
          t.rows.map(_.head).distinct.size == t.rows.size, s"${path.value}: required unique $key column must be first")
        _ <- require(mask.subsetOf(t.columns.toSet), s"requested fields are absent from ${path.value}")
        _ <- if key == "filename" then t.rows.traverse_(r => RelPath.from(r.head.get).map(_ => ())) else Right(())
        _ <- require(required.subsetOf(t.rows.flatMap(_.head).toSet), s"${path.value}: selected recordings/sessions lack rows")
        names = Vector(key) ++ mask.toVector.sorted
        selected <- t.select(names).leftMap(e => Problem.Unsupported(e.message))
        filtered <- bids4s.BidsTable.fromRows(names, selected.rows.filter(r => r.head.exists(required))).leftMap(e => Problem.Unsupported(e.message))
        _ <- if key != "participant_id" then Right(()) else
          val original = source.interpretation.participants.map(p => p.participant -> p.fields).toMap
          require(filtered.rows.forall(row => row.head.flatMap(original.get).exists(fields =>
            names.tail.zip(row.tail).forall((name, value) => fields.get(name).contains(value.getOrElse("n/a"))))),
            "participant values differ between the source interpretation and strict TSV; reinterpret before subsetting")
        sidecar <- RelPath.from(path.value.stripSuffix(".tsv") + ".json")
      yield
        emit(path, CheckedTsv.render(filtered), "project-summary", Vector(path))
        source.interpretation.byPath.get(path).foreach { m =>
          val fields = m.fields.filter((k, _) => names.contains(k)).map((k, f) => k -> f.value)
          if fields.nonEmpty then json(sidecar, fields, Vector(path) ++ m.sources)
        }
    for
      participantPath <- RelPath.from("participants.tsv")
      _ <- if paths.contains(participantPath) then table(participantPath, "participant_id", profile.participantFields, participants.toSet)
        else require(profile.participantFields.isEmpty, "requested participant metadata is unavailable").map { _ =>
          emit(participantPath, (Vector("participant_id") ++ participants).mkString("", "\n", "\n"), "project-summary", recordings.map(_.path))
        }
      _ <- paths.filterNot(_ == participantPath).traverse_ { p =>
        if p.fileName.endsWith("_sessions.tsv") then
          val subject = p.value.takeWhile(_ != '/')
          table(p, "session_id", profile.sessionFields, recordings.filter(_.participant == subject).flatMap(_.session).toSet)
        else
          val parent = p.parent.get.value + "/"
          table(p, "filename", profile.scanFields, recordings.filter(f => f.participant + f.session.fold("")("/" + _) + "/" == parent).map(_.path.value.stripPrefix(parent)).toSet)
      }
    yield ()
