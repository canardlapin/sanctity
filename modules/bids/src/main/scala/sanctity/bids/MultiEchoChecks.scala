package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*

/** Header agreement is a bounded consistency policy, not registration qualification. */
private[bids] final class MultiEchoChecks[F[_]: Async](store: BlobStore[F]):
  def assess(manifest: Manifest, interpretation: Interpretation,
      headers: Map[RelPath, Either[bids4s.DwiIssue, bids4s.NiftiHeaderFacts]]): F[Vector[ContentCheck]] =
    val files = manifest.files.map(f => f.path -> f).toMap
    val groups = interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == "bids.multi-echo-bold")
      .map(r => (r.to :+ r.from).distinct.sortBy(_.value)).distinct
    val reader = new NiftiHeaderReader[F](store)
    groups.traverse { members =>
      for
        observed <- members.traverse(p => headers.get(p).fold(reader.read(files(p)))(Async[F].pure).map(p -> _))
        valid = observed.flatMap(_._2.toOption)
        same = valid.size == members.size && valid.forall(h => Set(3, 4)(h.dimensions.size)) &&
          valid.map(h => (h.dimensions, h.pixelDimensions.take(h.dimensions.size + 1), h.unitsCode)).distinct.size == 1
        findings = Option.unless(same)(Finding("MultiEchoImageCoherence", Severity.Error, Some(members.head),
          "echo family requires equal scalar dimensions, active pixel dimensions and units; this does not assess registration")).toVector
        check <- Checked.lift[F, ContentCheck](ContentCheck.from("bids-multiecho-headers-v1", members.head,
          members.zipWithIndex.map((p, i) => CheckInput(s"echo-$i", p, files(p).blob)),
          Map("members" -> MetadataValue.Array(members.map(p => MetadataValue.Text(p.value))), "headerOnly" -> MetadataValue.Flag(true)), findings))
      yield check
    }
