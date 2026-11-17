package sanctity.bids

import bids4s.{BidsContext, BidsManifest, BidsMetadataResolution, BidsPath}
import cats.effect.Async
import sanctity.application.{BlobStore, ViewCompiler}
import sanctity.kernel.*

trait ModalityRules:
  def editable: Set[String]
  def view(context: BidsContext): ViewCompiler
  def relationships(named: BidsManifest, resolved: Map[BidsPath, BidsMetadataResolution],
      artifacts: Vector[ArtifactMetadata], targets: Vector[ArtifactFacts]): (Vector[ScientificRelationship], Vector[Finding]) =
    val _ = (named, resolved, artifacts, targets)
    (Vector.empty, Vector.empty)
  def assess[F[_]: Async](store: BlobStore[F], context: BidsContext, manifest: Manifest, initial: Interpretation):
      F[(Interpretation, Vector[ContentCheck], Vector[Finding])]
