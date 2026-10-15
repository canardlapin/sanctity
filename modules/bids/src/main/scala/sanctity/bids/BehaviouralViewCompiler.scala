package sanctity.bids

import bids4s.{BidsTable, CheckedTsv}
import cats.syntax.all.*
import io.circe.{Json, Printer}
import io.circe.jawn.JawnParser
import sanctity.application.ViewCompiler
import sanctity.kernel.*

/** Coherent subsets of table-only recordings, with explicit column disclosure and exact receipts. */
final class BehaviouralViewCompiler extends ViewCompiler:
  private val parser = new JawnParser(maxValueSize = Some(4 * 1024 * 1024), allowDuplicateKeys = false)
  private val printer = Printer.spaces2.copy(sortKeys = true)
  private case class Context(roots: Vector[ArtifactFacts], closure: RelationshipClosure, summaries: Vector[RelPath])
  private def require(ok: Boolean, detail: String): Either[Problem, Unit] = Either.cond(ok, (), Problem.Unsupported(detail))

  private def context(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Context] =
    val selected = selection.hits.map(_.path).toSet
    val roots = source.interpretation.facts.filter(f => selected(f.path) && BehaviouralChecks.inBehaviour(f.path) && f.suffix == "beh" && f.extension == "tsv")
    for
      _ <- require(selection.releases == Vector(source.ref) && roots.nonEmpty, "behavioural view requires selected beh TSV recordings from exactly one release")
      _ <- require(source.interpretation.profile.context.contains(BidsInterpreter.describe(RepositoryBidsContext.Behavioural)) &&
        source.interpretation.relationships.exists(_.version == BehaviouralChecks.GraphVersion), "reinterpret source with the current behavioural profile before transforming")
      _ <- require(profile.behaviouralFields.exists(_.nonEmpty), "behavioural columns require an explicit nonempty disclosure mask")
      closure <- source.interpretation.relationshipClosure(roots.map(_.path))
      _ <- require(closure.relationships.forall(r => Set(BehaviouralChecks.EventsKind, BehaviouralChecks.StimulusKind)(r.kind)), "unsupported behavioural dependency")
      _ <- require(!closure.relationships.exists(_.kind == BehaviouralChecks.EventsKind) || profile.eventFields.exists(fs => Set("onset", "duration").subsetOf(fs)),
        "corresponding events require explicit onset and duration disclosure")
      summaries <- SummaryProjection.paths(source, roots)
      _ <- require(selected.subsetOf(closure.artifacts.toSet ++ summaries), "selection includes files outside selected behavioural recordings and their closure")
    yield Context(roots, closure, summaries)

  def documents(source: Release, selection: FrozenSelection, profile: DisclosureProfile): Either[Problem, Vector[RelPath]] =
    context(source, selection, profile).flatMap { c =>
      val tables = c.roots.map(_.path) ++ c.closure.relationships.filter(_.kind == BehaviouralChecks.EventsKind).flatMap(_.to)
      RelPath.from("dataset_description.json").map(p => (Vector(p) ++ tables ++ c.summaries).distinct.sortBy(_.value))
    }

  def plan(source: Release, selection: FrozenSelection, profile: DisclosureProfile, documents: Map[RelPath, String] = Map.empty): Either[Problem, ProjectionPlan] =
    for
      c <- context(source, selection, profile)
      required <- this.documents(source, selection, profile)
      _ <- require(required.forall(documents.contains), "behavioural view requires all bounded source documents")
      result <- compile(source, selection, profile, documents, c)
    yield result

  private def compile(source: Release, selection: FrozenSelection, profile: DisclosureProfile, documents: Map[RelPath, String], c: Context): Either[Problem, ProjectionPlan] =
    val files = source.manifest.files.map(f => f.path -> f).toMap
    val kept = Vector.newBuilder[Entry.File]
    val made = Vector.newBuilder[(RelPath, String)]
    val proof = Vector.newBuilder[ProjectionStep]
    def emit(path: RelPath, text: String, operation: String, inputs: Vector[RelPath]): Unit =
      made += path -> text
      proof += ProjectionStep(path, operation, inputs.distinct.map(p => CheckInput("source", p, files(p).blob)))
    def copy(from: RelPath, to: RelPath): Unit =
      kept += Entry.File(to, files(from).artifact, files(from).blob)
      proof += ProjectionStep(to, "copy", Vector(CheckInput("content", from, files(from).blob)))
    def json(path: RelPath, fields: Map[String, MetadataValue], inputs: Vector[RelPath]): Unit =
      emit(path, printer.print(Json.obj(fields.toVector.map((k, v) => k -> encode(v))*)) + "\n", "project-metadata", inputs)
    val projected = scala.collection.mutable.Map.empty[RelPath, BidsTable]
    var retainedHed = false
    val tableWork = c.roots.flatMap { r =>
      Vector((r.path, r.path, profile.behaviouralFields.get)) ++ c.closure.relationships.filter(link => link.from == r.path && link.kind == BehaviouralChecks.EventsKind)
        .flatMap(_.to).map { p => (p, RelPath.from(r.path.value.stripSuffix("_beh.tsv") + "_events.tsv").toOption.get, profile.eventFields.get) }
    }
    val work = for
      _ <- tableWork.traverse_ { (from, to, mask) =>
        for
          parsed <- CheckedTsv.parse(documents(from)).leftMap(e => Problem.Unsupported(e.message))
          _ <- require(mask.subsetOf(parsed.table.columns.toSet) && mask.nonEmpty, "requested behavioural/event columns are absent or empty")
          _ <- require(!source.interpretation.byPath(from).fields.keySet.exists(Set("Sources", "RawSources", "DatasetLinks", "IntendedFor", "AssociatedEmptyRoom", "M0Scan")),
            "behavioural view cannot transform unsupported metadata references")
          names = parsed.table.columns.filter(mask)
          table <- parsed.table.select(names).leftMap(e => Problem.Unsupported(e.message))
          sidecar <- RelPath.from(to.value.stripSuffix(".tsv") + ".json")
        yield
          projected(from) = table
          if names == parsed.table.columns then copy(from, to) else emit(to, CheckedTsv.render(table), "project-table", Vector(from))
          val metadata = source.interpretation.byPath(from)
          val fields = metadata.fields.filter((k, _) => names.contains(k) || profile.metadataFields(k)).filterNot((k, _) =>
            parsed.table.columns.contains(k) && !names.contains(k)).map((k, f) => k -> f.value)
          retainedHed ||= names.contains("HED") || fields.values.exists(hasHed)
          if fields.nonEmpty then json(sidecar, fields, Vector(from) ++ metadata.sources)
      }
      _ <- c.closure.relationships.filter(_.kind == BehaviouralChecks.StimulusKind).flatMap(_.to).distinct.traverse_ { asset =>
        val referenced = projected.values.exists { t =>
          val i = t.columns.indexOf("stim_file")
          i >= 0 && t.rows.exists(r => r(i).exists(v => "stimuli/" + v == asset.value))
        }
        if referenced then copy(asset, asset)
        Right(()): Either[Problem, Unit]
      }
      _ <- SummaryProjection.project(source, profile, documents, c.roots, c.summaries, emit, json)
      description <- RelPath.from("dataset_description.json")
      obj <- parser.parse(documents(description)).leftMap(e => Problem.Unsupported(e.message)).flatMap(_.asObject.toRight(Problem.Unsupported("dataset description must be an object")))
      version <- obj("BIDSVersion").flatMap(_.asString).toRight(Problem.Unsupported("source BIDSVersion is unavailable"))
      _ <- require(obj("License").isEmpty || profile.metadataFields("License"), "source License must be explicitly retained")
      _ <- require(!retainedHed || obj("HEDVersion").isEmpty || profile.metadataFields("HEDVersion"),
        "retained HED annotations require explicit source HEDVersion disclosure")
    yield
      val fields = Vector("Name" -> Json.fromString("Authorized behavioural view"), "BIDSVersion" -> Json.fromString(version), "DatasetType" -> Json.fromString("raw")) ++
        obj("License").map("License" -> _) ++ obj("HEDVersion").filter(_ => retainedHed).map("HEDVersion" -> _)
      emit(description, printer.print(Json.obj(fields*)) + "\n", "describe-dataset", Vector(description))
    work.flatMap { _ =>
      val retained = kept.result().sortBy(_.path.value)
      val generated = made.result().sortBy(_._1.value)
      val steps = proof.result().sortBy(_.output.value)
      val outputs = retained.map(_.path) ++ generated.map(_._1)
      require(outputs.size <= 10000 && outputs.map(_.collisionKey).distinct.size == outputs.size &&
        generated.forall(_._2.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= BoundedBidsText.Limit) &&
        generated.foldLeft(BigInt(0))((n, t) => n + t._2.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) <= BoundedBidsText.TotalLimit &&
        steps.forall(s => s.inputs.nonEmpty && s.inputs.size <= 256) && steps.map(_.inputs.size.toLong).sum <= 100000,
        "behavioural view outputs conflict or exceed document/receipt limits").map { _ =>
          val uses = steps.flatMap(s => s.inputs.map(_.path -> s.output)).groupMap(_._1)(_._2)
          val decisions = source.manifest.files.map(f => ProjectionDecision(f.path,
            if c.roots.exists(_.path == f.path) then "selected" else if c.closure.artifacts.contains(f.path) then "dependency"
            else if uses.contains(f.path) then "transformation-input" else "excluded", uses.getOrElse(f.path, Vector.empty).distinct.sortBy(_.value)))
          ProjectionPlan(retained, generated, ProjectionEvidence(source.ref, selection.id, profile.id,
            "sanctity-behavioural-view-v1:" + source.interpretation.profile.context.get.id.value, generated.map(_._1)), steps, decisions)
        }
    }

  private def encode(value: MetadataValue): Json = value match
    case MetadataValue.Text(v) => Json.fromString(v)
    case MetadataValue.Number(v) => Json.fromBigDecimal(v)
    case MetadataValue.Flag(v) => Json.fromBoolean(v)
    case MetadataValue.Null => Json.Null
    case MetadataValue.Array(v) => Json.fromValues(v.map(encode))
    case MetadataValue.Record(v) => Json.obj(v.toVector.map((k, v) => k -> encode(v))*)

  private def hasHed(value: MetadataValue): Boolean = value match
    case MetadataValue.Record(v) => v.contains("HED") || v.values.exists(hasHed)
    case MetadataValue.Array(v) => v.exists(hasHed)
    case _ => false
