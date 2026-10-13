package sanctity.bids

import bids4s.{BidsPath, BidsStimulusResolver}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Table-column evidence binds local assets to exact selected event bytes through the manifest. */
private[bids] final class StimulusBindings[F[_]: Async](store: BlobStore[F]):
  def enrich(manifest: Manifest, original: Interpretation): F[(Interpretation, Vector[Finding])] =
    val links = original.relationships.toVector.flatMap(_.relationships)
    val tables = links.filter(_.kind == "bids.task-events").flatMap(_.to).distinct.sortBy(_.value)
    val files = manifest.files.map(f => f.path -> f).toMap
    for
      _ <- Checked.lift[F, Unit](Either.cond(tables.forall(p => files(p).blob.size <= 4L * 1024 * 1024) &&
        tables.map(p => BigInt(files(p).blob.size)).sum <= 32L * 1024 * 1024,
        (), Problem.LimitExceeded("stimulus resolution allows 4 MiB per event table and 32 MiB total")))
      reports <- tables.traverse { p =>
        store.read(files(p).blob).compile.to(Array).map { bytes =>
          val text = Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toEither
          p -> text.map(t => BidsStimulusResolver.resolve(t, files.keys.map(p => BidsPath(p.value)).toSet))
        }
      }
      findings = reports.flatMap { (p, report) => report.fold(
        _ => Vector(Finding("StimulusEncoding", Severity.Error, Some(p), "event table must be UTF-8")),
        _.issues.map(i => Finding("Stimulus" + i.code.toString, Severity.Error, Some(p), s"row ${i.row}: ${i.message}"))) }
      stimulusLinks <- Checked.lift[F, Vector[ScientificRelationship]](reports.flatMap { (p, report) =>
        report.toOption.filter(r => r.issues.isEmpty && r.paths.nonEmpty).map(r =>
          r.paths.traverse(v => RelPath.from(v.value)).map(targets => ScientificRelationship(p, "bids.event-stimuli", targets, None,
            Vector(RelationshipEvidence.TableColumn(p, p, "stim_file", BidsStimulusResolver.Version)))))
      }.sequence)
      assets = stimulusLinks.flatMap(_.to).distinct.filterNot(original.byPath.contains)
        .map(p => ArtifactMetadata(p, Map.empty, Vector.empty, None))
      graph <- Checked.lift[F, RelationshipGraph](RelationshipGraph.from(BidsInterpreter.RelationshipVersion,
        links ++ stimulusLinks, original.artifacts ++ assets, evidenceVersion = 3))
      interpretation <- Checked.lift[F, Interpretation](Interpretation.from(manifest.id, original.profile,
        original.artifacts ++ assets, original.facts, original.participants, original.queryVersion, Some(graph), original.declaredBidsVersion))
    yield interpretation -> findings
