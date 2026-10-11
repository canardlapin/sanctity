package sanctity.bids

import bids4s.{BidsContext, CheckedEvents, CheckedTsv, EventTiming}
import cats.syntax.all.*
import io.circe.{Json, Printer}
import io.circe.jawn.JawnParser
import sanctity.application.ViewCompiler
import sanctity.kernel.*

/** One bounded scientific subset profile. Dependencies come from the recorded graph;
  * no acquisition proximity heuristic, stimulus inference or pipeline qualification.
  */
final class MriViewCompiler(bidsContext: BidsContext = BidsContext.Default) extends ViewCompiler:
  private val printer = Printer.spaces2.copy(sortKeys = true)
  private val parser = new JawnParser(maxValueSize = Some(4 * 1024 * 1024), allowDuplicateKeys = false)
  private val referenceFields = Set("IntendedFor", "B0FieldSource", "B0FieldIdentifier")
  private val forbidden = Set("Sources", "RawSources", "AssociatedEmptyRoom", "M0Scan", "AnatomicalLandmarkCoordinates")
  private val supportedKinds = Set("bids.b0-field-source", "bids.intended-for", "bids.dwi-gradients", "bids.task-events")
  private val structural = EventTiming.Fields ++ Set("TaskName", "PhaseEncodingDirection", "TotalReadoutTime")
  private val imageExtensions = Set("nii", "nii.gz")

  private final case class Context(roots: Set[RelPath], closure: RelationshipClosure,
      images: Vector[ArtifactFacts], summaries: Vector[RelPath])

  private def context(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Context] =
    val facts = source.interpretation.facts.map(f => f.path -> f).toMap
    val selected = selection.hits.map(_.path).toSet
    // A suffix query can include local gradients/tables. They are permitted only if
    // covered by the closure of selected primary images; they never create new roots.
    val roots = selected.filter(p => facts.get(p).exists(f => imageExtensions(f.extension) && f.suffix != "epi"))
    def supported(f: ArtifactFacts): Boolean =
      imageExtensions(f.extension) && Set("T1w", "T2w", "bold", "dwi")(f.suffix) &&
        !f.entities.keySet.exists(Set("echo", "part", "chunk", "recording", "sample", "flip", "inv", "mt"))
    for
      _ <- require(source.interpretation.profile.context.contains(BidsInterpreter.describe(bidsContext)),
        "source interpretation context differs from the compiler; explicitly reinterpret before transforming")
      _ <- require(source.interpretation.relationships.exists(_.version == BidsInterpreter.RelationshipVersion),
        "view requires the current explicit relationship profile")
      _ <- require(selection.releases == Vector(source.ref) && roots.nonEmpty,
        "MRI view needs a nonempty selection from exactly one release")
      _ <- require(roots.forall(p => facts.get(p).exists(supported)) && selection.hits.forall(h => facts.get(h.path).exists(f => f.participant == h.participant && f.session == h.session)),
        "select T1w/T2w, single-echo BOLD or DWI NIfTI acquisitions")
      _ <- require((profile.participantFields ++ profile.sessionFields ++ profile.scanFields ++ profile.eventFields.getOrElse(Set.empty))
        .forall(_.matches("[A-Za-z][A-Za-z0-9_]*")) && !profile.participantFields("participant_id") &&
        !profile.sessionFields("session_id") && !profile.scanFields("filename"), "invalid table field mask")
      closure <- source.interpretation.relationshipClosure(roots.toVector)
      _ <- require(closure.relationships.forall(r => supportedKinds(r.kind)), "unsupported relationship in subset closure")
      _ <- require((closure.artifacts ++ closure.metadataDocuments).forall(p => source.manifest.byPath.get(p).exists(_.isInstanceOf[Entry.File])),
        "relationship or metadata input is absent from the source manifest")
      images = closure.artifacts.flatMap(facts.get).filter(f => imageExtensions(f.extension))
      imagePaths = images.map(_.path).toSet
      _ <- require(closure.relationships.forall(r => imagePaths(r.from) && (r.kind match
        case "bids.task-events" => r.to.size == 1 && r.to.head.fileName.endsWith("events.tsv")
        case "bids.dwi-gradients" => r.to.size == 2 && r.to.map(_.fileName.split('.').last).toSet == Set("bval", "bvec")
        case _ => r.to.forall(imagePaths))), "malformed MRI dependency group")
      _ <- require(images.forall(f => supported(f) || (f.suffix == "epi" && !f.entities.keySet.exists(Set("echo", "part", "chunk")))),
        "subset supports only explicit EPI fieldmap dependencies; implicit phase/magnitude families require another profile")
      _ <- require(images.forall(f => roots(f.path) || f.suffix == "epi"), "dependency would add an unselected primary acquisition")
      _ <- require(images.forall(f => selection.hits.exists(_.participant == f.participant)), "dependency crosses the selected participant scope")
      _ <- require(closure.relationships.filter(r => Set("bids.b0-field-source", "bids.intended-for")(r.kind))
        .forall(_.to.forall(p => facts.get(p).exists(_.suffix == "epi"))), "fieldmap source is outside the explicit EPI profile")
      _ <- require(closure.artifacts.forall(p => source.interpretation.byPath(p).datasetRoot.isEmpty), "nested datasets require another subset profile")
      _ <- images.traverse_ { f =>
        val expected = f.suffix match
          case "T1w" | "T2w" => "anat"
          case "bold" => "func"
          case "dwi" => "dwi"
          case _ => "fmap"
        val directory = f.participant + f.session.fold("")("/" + _) + "/" + expected
        require(f.path.parent.exists(_.value == directory), s"unsupported image placement: ${f.path.value}")
      }
      _ <- images.traverse_ { f =>
        val companions = source.interpretation.facts.filter(c => c.participant == f.participant && c.session == f.session &&
          Set("sbref", "physio", "stim", "fieldmap", "phasediff", "phase1", "phase2", "magnitude1", "magnitude2")(c.suffix))
        // Conservatively reject applicable companions outside the recorded vocabulary.
        require(!companions.exists(c => c.entities.forall { (k, v) =>
          f.entities.get(k).forall(w => equivalent(k, v, w))
        }), s"unsupported companion near ${f.path.value}")
      }
      _ <- images.filter(_.suffix == "epi").traverse_ { f =>
        val unsupportedGradients = source.manifest.files.exists { file =>
          bidsContext.parseGeneric(file.path.fileName).toOption.exists(n => n.kind == "epi" && Set("bval", "bvec")(n.extension) &&
            n.entities.values.forall((k, v) => f.entities.get(k.short).exists(w => equivalent(k.short, v, w))) &&
            file.path.parent.forall(d => f.path.value.startsWith(d.value + "/")))
        }
        require(!unsupportedGradients, s"EPI gradients require another subset profile: ${f.path.value}")
      }
      summaries <- summaryPaths(source, images)
      _ <- require(selected.subsetOf(closure.artifacts.toSet ++ summaries),
        "selection contains files outside the supported acquisition closure and summary tables")
    yield Context(roots, closure, images.sortBy(_.path.value), summaries)

  def documents(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Vector[RelPath]] =
    context(source, selection, profile).flatMap { c =>
      val events = c.closure.relationships.filter(_.kind == "bids.task-events").flatMap(_.to)
      RelPath.from("dataset_description.json").map(p => (Vector(p) ++ c.summaries ++ events).distinct.sortBy(_.value))
    }

  def plan(source: Release, selection: FrozenSelection, profile: DisclosureProfile,
      documents: Map[RelPath, String] = Map.empty): Either[Problem, ProjectionPlan] =
    for
      c <- context(source, selection, profile)
      required <- this.documents(source, selection, profile)
      _ <- require(required.forall(documents.contains), "subset planning requires its bounded source documents")
      result <- compile(source, selection, profile, documents, c)
    yield result

  private def compile(source: Release, selection: FrozenSelection, profile: DisclosureProfile,
      documents: Map[RelPath, String], c: Context): Either[Problem, ProjectionPlan] =
    val files = source.manifest.files.map(f => f.path -> f).toMap
    val metadata = source.interpretation.byPath
    val links = c.closure.relationships
    val imagePaths = c.images.map(_.path).toSet
    val retained = Vector.newBuilder[Entry.File]
    val generated = Vector.newBuilder[(RelPath, String)]
    val steps = Vector.newBuilder[ProjectionStep]
    val assessedEvents = links.filter(_.kind == "bids.task-events").flatMap(_.to).distinct
      .map(p => p -> CheckedEvents.assess(documents(p))).toMap
    val renderedEvents = scala.collection.mutable.Map.empty[RelPath, String]
    var generatedBytes = 0L
    var budgetFailure: Option[Problem] = None
    def room: Either[Problem, Unit] = budgetFailure.toLeft(())
    def inputs(paths: Vector[RelPath], role: String): Vector[CheckInput] =
      paths.distinct.sortBy(_.value).map(p => CheckInput(role, p, files(p).blob))
    def emit(path: RelPath, text: String, operation: String, sources: Vector[RelPath]): Unit =
      if budgetFailure.isEmpty then
        val size = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
        if size > 4L * 1024 * 1024 || generatedBytes + size > 32L * 1024 * 1024 || sources.distinct.size > 256 then
          budgetFailure = Some(Problem.LimitExceeded("subset generated documents or input mappings exceed the bounded profile"))
        else
          generatedBytes += size
          generated += path -> text
          steps += ProjectionStep(path, operation, inputs(sources, "source"))
    def copy(from: RelPath, to: RelPath): Unit =
      val file = files(from)
      retained += Entry.File(to, file.artifact, file.blob)
      steps += ProjectionStep(to, "copy", inputs(Vector(from), "content"))
    def json(path: RelPath, fields: Map[String, MetadataValue], sources: Vector[RelPath]): Unit =
      emit(path, printer.print(Json.obj(fields.toVector.map((k, v) => k -> encode(v))*)) + "\n", "project-metadata", sources)
    def effective(path: RelPath): Map[String, MetadataValue] = metadata(path).fields.map((k, f) => k -> f.value)

    val work = for
      _ <- c.images.traverse_ { f =>
        val fields = effective(f.path)
        val needed = fields.keySet.intersect(structural ++ referenceFields)
        val timing = f.suffix != "bold" || (fields.contains("TaskName") && (fields.contains("RepetitionTime") || fields.contains("VolumeTiming")))
        val epi = f.suffix != "epi" || Set("PhaseEncodingDirection", "TotalReadoutTime").subsetOf(fields.keySet)
        for
          _ <- room
          _ <- require(!fields.keySet.exists(forbidden), s"unsupported metadata dependency: ${f.path.value}")
          _ <- require(needed.subsetOf(profile.metadataFields) && timing && epi, s"disclosure mask must preserve required acquisition/reference metadata: ${f.path.value}")
          _ <- require(f.suffix != "dwi" || links.exists(r => r.from == f.path && r.kind == "bids.dwi-gradients"), "DWI gradient group is missing")
          _ <- require(f.suffix != "bold" || f.entities.get("task").contains("rest") || links.exists(r => r.from == f.path && r.kind == "bids.task-events"),
            "task BOLD requires a resolved event table in this subset profile")
          sidecar <- RelPath.from(f.path.value.stripSuffix("." + f.extension) + ".json")
        yield
          copy(f.path, f.path)
          val intended = links.filter(r => r.kind == "bids.intended-for" && r.to.contains(f.path) && imagePaths(r.from))
            .map(r => MetadataValue.Text("bids::" + r.from.value)).distinct.sortBy(_.toString)
          val identifiers = links.filter(r => r.kind == "bids.b0-field-source" && r.to.contains(f.path)).flatMap(_.label).toSet
          val projected = fields.filter((k, _) => profile.metadataFields(k)) - "IntendedFor" - "B0FieldIdentifier"
          val idField = fields.get("B0FieldIdentifier").toVector.flatMap { value =>
            val values = strings(value).filter(identifiers)
            Option.when(values.nonEmpty)("B0FieldIdentifier" -> (value match
              case MetadataValue.Text(_) => MetadataValue.Text(values.head)
              case _ => MetadataValue.Array(values.map(MetadataValue.Text.apply))))
          }.toMap
          val rewritten = projected ++ idField ++ Option.when(intended.nonEmpty)("IntendedFor" -> MetadataValue.Array(intended)).toMap
          json(sidecar, rewritten, (Vector(f.path) ++ metadata(f.path).sources))
      }
      _ <- links.filter(_.kind == "bids.dwi-gradients").traverse_ { r =>
        val f = c.images.find(_.path == r.from).get
        r.to.traverse_ { p =>
          val ext = p.fileName.split('.').last
          for
            _ <- require(Set("bval", "bvec")(ext), "unsupported gradient extension")
            _ <- require(effective(p).filter((k, _) => profile.metadataFields(k)).forall((k, v) => effective(f.path).get(k).contains(v)),
              "localized gradient metadata would conflict with acquisition metadata")
            target <- RelPath.from(f.path.value.stripSuffix("." + f.extension) + "." + ext)
          yield copy(p, target)
        }
      }
      _ <- links.filter(_.kind == "bids.task-events").traverse_ { r =>
        val f = c.images.find(_.path == r.from).get
        for
          _ <- room
          p <- r.to match
            case Vector(p) => Right(p)
            case _ => Left(Problem.Integrity("event relationship must have one table"))
          mask <- profile.eventFields.toRight(Problem.Unsupported("event columns require an explicit disclosure mask"))
          _ <- require(Set("onset", "duration").subsetOf(mask), "event mask must include onset and duration")
          assessed = assessedEvents(p)
          events <- assessed.table.toRight(Problem.Unsupported("event source failed strict assessment"))
          _ <- require(!events.table.columns.exists(Set("stim_file", "HED")) && !effective(p).keySet.exists(Set("HED", "StimulusPresentation")) && !effective(p).values.exists(hasHed),
            "stimulus references and HED need a separate subset profile")
          names = events.table.columns.filter(mask)
          selected <- events.table.select(names).leftMap(e => Problem.Unsupported(e.message))
          target <- RelPath.from(f.path.value.stripSuffix("_bold." + f.extension) + "_events.tsv")
          sidecar <- RelPath.from(target.value.stripSuffix(".tsv") + ".json")
        yield
          if names == events.table.columns then copy(p, target)
          else emit(target, renderedEvents.getOrElseUpdate(p, CheckedTsv.render(selected)), "project-events", Vector(p))
          val fields = effective(p).filter((k, _) => names.contains(k))
          if fields.nonEmpty then json(sidecar, fields, Vector(p) ++ metadata(p).sources)
      }
      _ <- projectSummaries(source, profile, documents, c.images, c.summaries, emit, json)
      description <- RelPath.from("dataset_description.json")
      obj <- parser.parse(documents(description)).leftMap(e => Problem.Unsupported(e.message)).flatMap(j =>
        j.asObject.toRight(Problem.Unsupported("dataset description must be an object")))
      version <- obj("BIDSVersion").flatMap(_.asString).toRight(Problem.Unsupported("source BIDSVersion is unavailable"))
      _ <- require(obj("License").isEmpty || profile.metadataFields("License"), "source License must be explicitly retained by the disclosure mask")
    yield
      val fields = Vector("Name" -> Json.fromString("Authorized scientific view"), "BIDSVersion" -> Json.fromString(version),
        "DatasetType" -> Json.fromString("raw")) ++ obj("License").map("License" -> _)
      emit(description, printer.print(Json.obj(fields*)) + "\n", "describe-dataset", Vector(description))

    work.flatMap(_ => room).flatMap { _ =>
      val kept = retained.result().sortBy(_.path.value)
      val made = generated.result().sortBy(_._1.value)
      val proof = steps.result().sortBy(_.output.value)
      val outputs = kept.map(_.path) ++ made.map(_._1)
      val use = proof.flatMap(s => s.inputs.map(i => i.path -> s.output)).groupMap(_._1)(_._2)
      val dependencyPaths = c.closure.artifacts.toSet
      val decisions = source.manifest.files.map { f =>
        ProjectionDecision(f.path, if c.roots(f.path) then "selected" else if dependencyPaths(f.path) then "dependency"
          else if use.contains(f.path) then "transformation-input" else "excluded", use.getOrElse(f.path, Vector.empty).distinct.sortBy(_.value))
      }
      require(proof.forall(s => s.inputs.nonEmpty && s.inputs.size <= 256) && proof.map(_.inputs.size.toLong).sum <= 100000 &&
        outputs.size <= 10000 && outputs.map(_.collisionKey).distinct.size == outputs.size,
        "subset output has conflicting paths or exceeds 10000 files").map { _ =>
        ProjectionPlan(kept, made, ProjectionEvidence(source.ref, selection.id, profile.id,
          "sanctity-mri-view-v5:" + BidsInterpreter.describe(bidsContext).id.value, made.map(_._1)), proof, decisions)
      }
    }

  private def summaryPaths(source: Release, images: Vector[ArtifactFacts]): Either[Problem, Vector[RelPath]] =
    val names = Vector("participants.tsv") ++ images.map(f => s"${f.participant}/${f.participant}_sessions.tsv") ++
      images.map(f => f.participant + f.session.fold("")("/" + _) + "/" + f.participant + f.session.fold("")("_" + _) + "_scans.tsv")
    names.distinct.traverse(RelPath.from).map(_.filter(source.manifest.byPath.contains).sortBy(_.value))

  private def projectSummaries(source: Release, profile: DisclosureProfile, documents: Map[RelPath, String],
      images: Vector[ArtifactFacts], paths: Vector[RelPath], emit: (RelPath, String, String, Vector[RelPath]) => Unit,
      json: (RelPath, Map[String, MetadataValue], Vector[RelPath]) => Unit): Either[Problem, Unit] =
    val participants = images.map(_.participant).distinct.sorted
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
        filtered <- bids4s.BidsTable.fromRows(names, selected.rows.filter(r => r.head.exists(required)))
          .leftMap(e => Problem.Unsupported(e.message))
        _ <- if key != "participant_id" then Right(()) else
          val original = source.interpretation.participants.map(p => p.participant -> p.fields).toMap
          val agrees = filtered.rows.forall { row =>
            row.head.flatMap(original.get).exists { fields =>
              names.tail.zip(row.tail).forall((name, value) => fields.get(name).contains(value.getOrElse("n/a")))
            }
          }
          require(agrees, "participant values differ between the source interpretation and strict TSV; reinterpret before subsetting")
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
          emit(participantPath, (Vector("participant_id") ++ participants).mkString("", "\n", "\n"), "project-summary", images.map(_.path))
        }
      _ <- paths.filterNot(_ == participantPath).traverse_ { p =>
        if p.fileName.endsWith("_sessions.tsv") then
          val subject = p.value.takeWhile(_ != '/')
          table(p, "session_id", profile.sessionFields, images.filter(_.participant == subject).flatMap(_.session).toSet)
        else
          val parent = p.parent.get.value + "/"
          table(p, "filename", profile.scanFields, images.filter(f => f.participant + f.session.fold("")("/" + _) + "/" == parent).map(_.path.value.stripPrefix(parent)).toSet)
      }
    yield ()

  private def require(condition: Boolean, detail: String): Either[Problem, Unit] =
    Either.cond(condition, (), Problem.Unsupported(detail))
  private def strings(value: MetadataValue): Vector[String] = value match
    case MetadataValue.Text(v) => Vector(v)
    case MetadataValue.Array(vs) => vs.collect { case MetadataValue.Text(v) => v }
    case _ => Vector.empty
  private def hasHed(value: MetadataValue): Boolean = value match
    case MetadataValue.Record(fields) => fields.contains("HED") || fields.values.exists(hasHed)
    case MetadataValue.Array(values) => values.exists(hasHed)
    case _ => false
  private def equivalent(key: String, a: String, b: String): Boolean =
    if Set("run", "echo", "flip", "inv", "split", "chunk")(key) && a.forall(_.isDigit) && b.forall(_.isDigit) then
      a.dropWhile(_ == '0') == b.dropWhile(_ == '0')
    else a == b
  private def encode(value: MetadataValue): Json = value match
    case MetadataValue.Text(v) => Json.fromString(v)
    case MetadataValue.Number(v) => Json.fromBigDecimal(v)
    case MetadataValue.Flag(v) => Json.fromBoolean(v)
    case MetadataValue.Null => Json.Null
    case MetadataValue.Array(v) => Json.fromValues(v.map(encode))
    case MetadataValue.Record(v) => Json.obj(v.toVector.map((n, v) => n -> encode(v))*)
