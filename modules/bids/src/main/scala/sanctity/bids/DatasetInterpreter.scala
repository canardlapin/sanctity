package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, SemanticInterpreter}
import sanctity.kernel.*

/** Chooses how a dataset is read from what its content declares.
  *
  * A root `dataset_description.json` declares a BIDS dataset. A root `archive_description.json` declares an archive.
  * The declaration is part of the versioned content, so a release and its export describe themselves, and changing a
  * collection from one kind to the other is an ordinary new release.
  */
final class DatasetInterpreter[F[_]: Async](store: BlobStore[F], policy: AdmissionPolicy = AdmissionPolicy.Inclusive)
    extends SemanticInterpreter[F]:
  private val bids = new RepositoryBidsInterpreter[F](store, policy)
  private val archive = new ArchiveInterpreter[F](store)

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    val root = manifest.files.map(_.path.value).toSet
    val described = root(DatasetInterpreter.BidsDescriptor)
    val archived = root(ArchiveInterpreter.Descriptor)
    if archived && !described then archive.prepare(manifest)
    else bids.prepare(manifest).map { (interpretation, assessment) =>
      val declaration =
        if described && archived then Some(Finding("AmbiguousDatasetKind", Severity.Error, None,
          s"the root has both ${DatasetInterpreter.BidsDescriptor} and ${ArchiveInterpreter.Descriptor}; keep the one that describes this collection"))
        else if !described then Some(Finding("UndeclaredDatasetKind", Severity.Error, None,
          s"add a root ${DatasetInterpreter.BidsDescriptor} for a BIDS dataset, or ${ArchiveInterpreter.Descriptor} for an archive"))
        else None
      interpretation -> declaration.fold(assessment)(f => Assessment.from(interpretation, assessment.policy, assessment.findings :+ f, assessment.checks))
    }

object DatasetInterpreter:
  val BidsDescriptor: String = "dataset_description.json"
