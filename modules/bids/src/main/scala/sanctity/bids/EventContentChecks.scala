package sanctity.bids

import bids4s.{BidsIssueSeverity, CheckedEvents, EventIssue, EventTableAssessment, EventTiming, JsonValue}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** One check per selected BOLD/events relationship; shared text is read and assessed once. */
final class EventContentChecks[F[_]](store: BlobStore[F])(using F: Async[F]):
  private val reader = new NiftiHeaderReader[F](store)
  private val totalLimit = 32L * 1024 * 1024
  private val rule = CheckedEvents.Version + "+" + EventTiming.Version + "+" +
    bids4s.NiftiHeaderFacts.Version + "+" + NiftiHeaderReader.Version

  def assess(manifest: Manifest, interpretation: Interpretation): F[Vector[ContentCheck]] =
    val groups = interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == "bids.task-events")
    val files = manifest.files.map(f => f.path -> f).toMap
    val tables = groups.flatMap(_.to).distinct.flatMap(files.get)
    for
      _ <- Checked.lift[F, Unit](Either.cond(tables.foldLeft(BigInt(0))((n, f) => n + f.blob.size) <= totalLimit,
        (), Problem.LimitExceeded("event profile allows 32 MiB of selected event tables")))
      parsed <- tables.traverse(f => readTable(f).map(f.path -> _)).map(_.toMap)
      checks <- groups.traverse { group =>
        for
          pair <- Checked.lift[F, (Entry.File, Entry.File)]((files.get(group.from), group.to match
            case Vector(p) => files.get(p)
            case _ => None).mapN((_, _)).toRight(Problem.Integrity("event relationship requires one image and one table")))
          (image, table) = pair
          metadata = interpretation.byPath(image.path)
          sources = (metadata.sources ++ interpretation.byPath(table.path).sources).distinct.sortBy(_.value)
          _ <- Checked.lift[F, Unit](Either.cond(sources.size <= 98, (), Problem.LimitExceeded("event check exceeds 98 metadata documents")))
          documents <- Checked.lift[F, Vector[Entry.File]](sources.traverse(p =>
            files.get(p).toRight(Problem.Integrity("event metadata source is absent from manifest"))))
          header <- reader.read(image)
          volumes = header.toOption.filter(h => Set(3, 4)(h.dimensions.size)).map(_.dimensions.lift(3).getOrElse(1L))
          imageFindings = header.fold(
            issue => Vector(Finding("EventsImage" + issue.code.toString, Severity.Error, Some(image.path), issue.message)),
            h => if Set(3, 4)(h.dimensions.size) then Vector.empty else
              Vector(Finding("EventsImageDimensions", Severity.Error, Some(image.path), "event profile requires a 3D or 4D scalar image")))
          selectedFields = metadata.fields.filter((name, _) => EventTiming.Fields(name))
          timing = EventTiming.assess(volumes, selectedFields.map((name, field) => name -> json(field.value)))
          events = parsed(table.path)
          comparisons = events.table.toVector.flatMap(e => EventTiming.compare(e, timing.context))
          tableFindings = (events.issues ++ comparisons).map(issueFinding(table.path, _))
          timingFindings = timing.issues.map { issue =>
            val at = issue.field.flatMap(metadata.fields.get).map(_.origin).getOrElse(image.path)
            issueFinding(at, issue)
          }
          inputs = Vector(CheckInput("image", image.path, image.blob), CheckInput("events", table.path, table.blob)) ++
            documents.zipWithIndex.map((f, i) => CheckInput(s"metadata-$i", f.path, f.blob))
          facts = Map[String, MetadataValue](
            "timingMode" -> MetadataValue.Text(timing.context.mode.toString),
            "endBasis" -> MetadataValue.Text(timing.context.endBasis),
            "timeOrigin" -> MetadataValue.Text("first stored volume; no discarded-volume offset applied"),
            "headerOnly" -> MetadataValue.Flag(true),
            "compressedPrefixLimit" -> MetadataValue.Number(NiftiHeaderReader.CompressedPrefixLimit),
            "timingMetadata" -> MetadataValue.Record(selectedFields.map((name, field) =>
              name -> MetadataValue.Record(Map("value" -> field.value, "origin" -> MetadataValue.Text(field.origin.value)))))) ++
            number("volumeCount", volumes.map(BigDecimal(_))) ++
            number("lastVolumeOnsetSeconds", timing.context.lastVolumeOnset) ++
            number("comparisonEndSeconds", timing.context.comparisonEnd) ++
            number("volumeTimingOriginSeconds", timing.context.originShift) ++
            events.table.toVector.flatMap { e =>
              Vector("eventCount" -> MetadataValue.Number(e.table.nrows),
                "columns" -> MetadataValue.Array(e.table.columns.map(MetadataValue.Text.apply)),
                "missingOnsetCount" -> MetadataValue.Number(e.onsets.count(_.isEmpty)),
                "missingDurationCount" -> MetadataValue.Number(e.durations.count(_.isEmpty))) ++
                number("earliestOnsetSeconds", e.onsets.flatten.minOption) ++
                number("latestOnsetSeconds", e.onsets.flatten.maxOption)
            }
          check <- Checked.lift[F, ContentCheck](ContentCheck.from(rule, image.path, inputs, facts,
            imageFindings ++ tableFindings ++ timingFindings))
        yield check
      }
    yield checks

  private def number(name: String, n: Option[BigDecimal]): Vector[(String, MetadataValue)] =
    n.toVector.map(v => name -> MetadataValue.Number(v))

  private def readTable(file: Entry.File): F[EventTableAssessment] =
    def error(code: bids4s.EventIssueCode, message: String) =
      EventTableAssessment(None, Vector(EventIssue(code, BidsIssueSeverity.Error, message)))
    if file.blob.size > CheckedEvents.MaxCharacters then
      F.pure(error(bids4s.EventIssueCode.LimitExceeded, "event table exceeds 4 MiB"))
    else store.read(file.blob).take(CheckedEvents.MaxCharacters.toLong + 1).compile.to(Array).flatMap { bytes =>
      Checked.lift[F, Unit](Either.cond(bytes.length.toLong == file.blob.size, (),
        Problem.Integrity("event table byte count differs from manifest"))) *> F.delay {
        Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
          .fold(_ => error(bids4s.EventIssueCode.Syntax, "event table is not valid UTF-8"), CheckedEvents.assess)
      }
    }

  private def issueFinding(path: RelPath, issue: EventIssue): Finding =
    val location = issue.row.fold("")(r => s" row $r") + issue.column.fold("")(c => s" column $c")
    val field = issue.field.fold("")(f => s"$f: ")
    Finding("Events" + issue.code.toString, if issue.severity == BidsIssueSeverity.Error then Severity.Error else Severity.Warning,
      Some(path), field + issue.message + location)

  private def json(value: MetadataValue): JsonValue = value match
    case MetadataValue.Number(n) => JsonValue.Num(n.toDouble)
    case MetadataValue.Text(s) => JsonValue.Str(s)
    case MetadataValue.Flag(b) => JsonValue.Bool(b)
    case MetadataValue.Null => JsonValue.Null
    case MetadataValue.Array(vs) => JsonValue.Arr(vs.map(json))
    case MetadataValue.Record(vs) => JsonValue.Obj(vs.map((k, v) => k -> json(v)))
