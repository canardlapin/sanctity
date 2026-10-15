package sanctity.bids

import bids4s.{BidsContext, BidsPath, BidsStimulusResolver, CheckedEvents, CheckedTsv}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*
import scala.util.Try

/** Table-only recordings and their optional events. This profile never invents an acquisition clock. */
final class BehaviouralChecks[F[_]: Async](store: BlobStore[F], context: BidsContext):
  import BehaviouralChecks.*

  def enrich(manifest: Manifest, original: Interpretation): F[(Interpretation, Vector[ContentCheck], Vector[Finding])] =
    val files = manifest.files.map(f => f.path -> f).toMap
    val roots = original.facts.filter(f => f.suffix == "beh" && f.extension == "tsv" && inBehaviour(f.path))
    val tablePaths = original.artifacts.map(_.path).filter(p => p.fileName.endsWith("_events.tsv") || p.fileName.endsWith("_beh.tsv"))
      .filter(p => inBehaviour(p) || roots.exists(r => ancestor(p, r.path)))
    val selected = roots.map { root =>
      val candidates = tablePaths.filter(p => p.fileName.endsWith("_events.tsv") && ancestor(p, root.path) &&
        context.parseGeneric(p.fileName).toOption.exists(n => n.entities.values.forall { (k, v) => root.entities.get(k.short).exists(w => equivalent(k.short, v, w)) }))
        .sortBy(p => (p.value.count(_ == '/'), p.value))
      root -> candidates
    }
    val placement = manifest.files.filter(f => inBehaviour(f.path)).flatMap { f =>
      val valid = context.parsePath(BidsPath(f.path.value)).toOption.exists { n =>
        n.datatype.contains("beh") && n.entities.get(bids4s.EntityKey.Subject).exists { sub =>
          val dir = "sub-" + sub + n.entities.get(bids4s.EntityKey.Session).fold("")(s => "/ses-" + s) + "/beh"
          f.path.parent.exists(_.value == dir)
        }
      }
      Option.unless(valid)(Finding("BehaviouralPlacement", Severity.Error, Some(f.path),
        "behavioural profile supports matching subject/session/task beh or events TSV/JSON files in beh/"))
    }
    val ambiguous = selected.collect { case (r, cs) if cs.groupBy(_.parent).values.exists(_.size > 1) =>
      Finding("BehaviouralEventsAmbiguous", Severity.Error, Some(r.path), "multiple applicable events in the same inheritance directory") }
    val usable = selected.filterNot((_, cs) => cs.groupBy(_.parent).values.exists(_.size > 1))
    val chosen = usable.flatMap(_._2.lastOption).toSet
    val orphaned = tablePaths.filter(p => inBehaviour(p) && p.fileName.endsWith("_events.tsv") && !usable.exists(_._2.contains(p)))
      .map(p => Finding("BehaviouralEventsOrphan", Severity.Error, Some(p), "events require an applicable corresponding behavioural table in this profile"))
    val paths = (roots.map(_.path) ++ chosen).distinct.sortBy(_.value)
    for
      _ <- Checked.lift[F, Unit](Either.cond(paths.foldLeft(BigInt(0))((n, p) => n + files(p).blob.size) <= BoundedBidsText.TotalLimit,
        (), Problem.LimitExceeded("behavioural tables exceed 32 MiB total")))
      texts <- paths.traverse(p => BoundedBidsText.read(store, files(p)).map(p -> _)).map(_.toMap)
      checks <- paths.traverse { p =>
        val metadata = original.byPath(p)
        val parsed = texts(p).flatMap(t => CheckedTsv.parse(t).leftMap(e => s"${e.message} at row ${e.row}, column ${e.column}"))
        val syntax = parsed.left.toOption.toVector.map(m => Finding("BehaviouralTable", Severity.Error, Some(p), m))
        val events = if !p.fileName.endsWith("_events.tsv") then Vector.empty else texts(p).toOption.toVector.flatMap(t =>
          CheckedEvents.assess(t).issues.map(i => Finding("BehaviouralEvents" + i.code.toString,
            if i.severity == bids4s.BidsIssueSeverity.Error then Severity.Error else Severity.Warning, Some(p), i.message)))
        val values = parsed.toOption.toVector.flatMap { checked =>
          val table = checked.table
          val column = table.columns.indexOf("response_time")
          val numbers = if column < 0 then Vector.empty else table.rows.zipWithIndex.flatMap { (row, index) =>
            row(column).filter(v => number(v).isEmpty).map(_ => Finding("BehaviouralResponseTime", Severity.Error, Some(p),
              s"response_time must be a bounded decimal or n/a at row ${checked.sourceRows(index)}"))
          }.take(64)
          val dictionaries = table.columns.flatMap(c => metadata.fields.get(c).toVector.flatMap { field =>
            field.value match
              case MetadataValue.Record(_) => Vector.empty
              case _ => Vector(Finding("BehaviouralColumnMetadata", Severity.Error, Some(field.origin), s"column $c metadata must be an object"))
          })
          numbers ++ dictionaries
        }
        val stimuli = texts(p).toOption.map(t => BidsStimulusResolver.resolve(t, files.keys.map(p => BidsPath(p.value)).toSet))
        val stimulusFindings = stimuli.toVector.flatMap(_.issues.map(i => Finding("BehaviouralStimulus" + i.code.toString,
          Severity.Error, Some(p), s"row ${i.row}: ${i.message}")))
        val evidence = (Vector(p) ++ metadata.sources).distinct.sortBy(_.value)
        val facts = Map[String, MetadataValue]("recordingKind" -> MetadataValue.Text(if p.fileName.endsWith("_beh.tsv") then "behavioural" else "events"),
          "timingBasis" -> MetadataValue.Text("unavailable; no recording clock inferred")) ++ parsed.toOption.toVector.flatMap(c =>
          Vector("rowCount" -> MetadataValue.Number(c.table.nrows), "columns" -> MetadataValue.Array(c.table.columns.map(MetadataValue.Text.apply))))
        Checked.lift[F, ContentCheck](ContentCheck.from(Rule, p,
          evidence.zipWithIndex.map((at, i) => CheckInput(if at == p then "table" else s"metadata-$i", at, files(at).blob)), facts,
          (syntax ++ events ++ values ++ stimulusFindings).distinct))
      }
      stimulusReports = paths.flatMap(p => texts(p).toOption.map(t => p -> BidsStimulusResolver.resolve(t, files.keys.map(p => BidsPath(p.value)).toSet)))
      stimulusLinks <- Checked.lift[F, Vector[ScientificRelationship]](stimulusReports.collect { case (p, report) if report.issues.isEmpty && report.paths.nonEmpty =>
        report.paths.traverse(v => RelPath.from(v.value)).map(ps => ScientificRelationship(p, StimulusKind, ps, None,
          Vector(RelationshipEvidence.TableColumn(p, p, "stim_file", BidsStimulusResolver.Version))))
      }.sequence)
      eventLinks = usable.flatMap { (r, cs) => cs.lastOption.map(p => ScientificRelationship(r.path, EventsKind, Vector(p), None,
        Vector(RelationshipEvidence.CompanionSelection(r.path, p, Rule, None,
          context.parseGeneric(p.fileName).toOption.get.entities.values.map((k, v) => k.short -> v).toMap, cs)))) }
      assets = stimulusLinks.flatMap(_.to).distinct.filterNot(original.byPath.contains).map(p => ArtifactMetadata(p, Map.empty, Vector.empty, None))
      graph <- Checked.lift[F, RelationshipGraph](RelationshipGraph.from(GraphVersion,
        original.relationships.toVector.flatMap(_.relationships) ++ eventLinks ++ stimulusLinks, original.artifacts ++ assets, 3))
      profile = original.profile.copy(name = "native-mri-behavioural-v1", coverage = original.profile.coverage + " Strict bounded behavioural tables, optional corresponding events and local stimuli; no behavioural clock qualification.")
      result <- Checked.lift[F, Interpretation](Interpretation.from(manifest.id, profile, original.artifacts ++ assets, original.facts,
        original.participants, original.queryVersion, Some(graph), original.declaredBidsVersion, original.observations))
    yield (result, checks, placement ++ ambiguous ++ orphaned)

object BehaviouralChecks:
  val Rule: String = "sanctity-behavioural-v1+" + CheckedTsv.Version + "+" + CheckedEvents.Version + "+" + BidsStimulusResolver.Version
  val GraphVersion: String = BidsInterpreter.RelationshipVersion + "+behavioural-v1"
  val EventsKind: String = "bids.behavioural-events"
  val StimulusKind: String = "bids.behavioural-stimuli"
  def inBehaviour(p: RelPath): Boolean = p.value.split('/').dropRight(1).contains("beh")
  private def ancestor(p: RelPath, root: RelPath): Boolean = p.parent.forall(d => root.parent.exists(r => r == d || r.value.startsWith(d.value + "/")))
  private def equivalent(key: String, a: String, b: String): Boolean =
    if key == "run" && a.forall(_.isDigit) && b.forall(_.isDigit) then a.dropWhile(_ == '0') == b.dropWhile(_ == '0') else a == b
  private def number(s: String): Option[BigDecimal] =
    Option.when(s.length <= 128 && s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))(s)
      .flatMap(v => Try(BigDecimal(v, java.math.MathContext.UNLIMITED)).toOption)
      .filter(v => v.precision <= 128 && math.abs(v.scale.toLong) <= 308)
