package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import io.circe.jawn.JawnParser
import java.nio.ByteBuffer
import sanctity.application.{BlobStore, Checked, SemanticInterpreter}
import sanctity.kernel.*

/** Chooses how a dataset is read from what its content declares.
  *
  * A root `dataset_description.json` declares a BIDS dataset. A root `archive_description.json` declares an archive.
  * The declaration is part of the versioned content, so a release and its export describe themselves. A dataset keeps
  * the kind its first release was accepted with: the repository refuses a later release of the other kind, and an
  * archive that has been curated into BIDS is deposited as its own dataset.
  */
final class DatasetInterpreter[F[_]: Async](store: BlobStore[F], policy: AdmissionPolicy = AdmissionPolicy.Inclusive,
    additional: Vector[DerivativeProfile[F]] = Vector.empty[DerivativeProfile[F]])
    extends SemanticInterpreter[F]:
  private val bids = new RepositoryBidsInterpreter[F](store, policy)
  private val archive = new ArchiveInterpreter[F](store)

  private val derivatives = DerivativeProfile("SanctityHeaderQc", Set(QcDerivativeFormat.Version),
    QcDerivativeFormat.Producer, new QcDerivativeInterpreter[F](store)) +: additional
  require(derivatives.map(_.field).distinct.size == derivatives.size && derivatives.map(_.producer).distinct.size == derivatives.size)
  private val parser = new JawnParser(Some(4 * 1024 * 1024), allowDuplicateKeys = false)

  override def editableCompanions: Set[String] = RepositoryBidsContext.Modalities.flatMap(_.rules.toVector.flatMap(_.editable)).toSet

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] = choose(manifest, None, Vector.empty)
  override def prepare(manifest: Manifest, activity: Activity): F[(Interpretation, Assessment)] = choose(manifest, Some(activity), Vector.empty)
  override def prepare(manifest: Manifest, activity: Activity, sources: Vector[Release]): F[(Interpretation, Assessment)] =
    choose(manifest, Some(activity), sources)

  private def choose(manifest: Manifest, activity: Option[Activity], sources: Vector[Release]): F[(Interpretation, Assessment)] =
    val receipt = activity.flatMap(_.processing)
    def read(interpreter: SemanticInterpreter[F]) =
      activity.fold(interpreter.prepare(manifest))(a => interpreter.prepare(manifest, a, sources))
    manifest.byPath.get(RelPath.from(DatasetInterpreter.BidsDescriptor).toOption.get) match
      case Some(file: Entry.File) if !manifest.files.exists(_.path.value == ArchiveInterpreter.Descriptor) =>
        Checked.lift[F, Unit](Either.cond(file.blob.size <= 4L * 1024 * 1024, (), Problem.LimitExceeded("dataset description exceeds 4 MiB"))) *>
          store.read(file.blob).compile.to(Array).flatMap { bytes =>
            val declaration = parser.parseByteBuffer(ByteBuffer.wrap(bytes)).toOption
            val selected = derivatives.filter(p => declaration.exists(j => j.hcursor.get[String]("DatasetType").contains("derivative") &&
              j.hcursor.downField(p.field).get[String]("Profile").exists(p.versions)))
            (selected, receipt) match
              case (Vector(profile), p) if p.forall(_.producer.name == profile.producer) => read(profile.interpreter)
              case (Vector(), None) => ordinary(manifest)
              case _ => Checked.lift[F, (Interpretation, Assessment)](Left(Problem.Unsupported("derivative declaration and processing recipe must agree")))
          }
      case _ if receipt.nonEmpty => Checked.lift[F, (Interpretation, Assessment)](Left(Problem.Unsupported("processing requires one derivative description")))
      case _ => ordinary(manifest)

  private def ordinary(manifest: Manifest): F[(Interpretation, Assessment)] =
    val root = manifest.files.map(_.path.value).toSet
    val described = root(DatasetInterpreter.BidsDescriptor)
    val archived = root(ArchiveInterpreter.Descriptor)
    if archived && !described then archive.prepare(manifest)
    else bids.prepare(manifest).map { (interpretation, assessment) =>
      val declaration =
        if described && archived then Some(Finding("AmbiguousDatasetKind", Severity.Error, None,
          s"the root has both ${DatasetInterpreter.BidsDescriptor} and ${ArchiveInterpreter.Descriptor}; keep the one that describes this " +
            "collection. A dataset keeps the kind it was accepted with, so an archive curated into BIDS is deposited as its own dataset"))
        else if !described then Some(Finding("UndeclaredDatasetKind", Severity.Error, None,
          s"add a root ${DatasetInterpreter.BidsDescriptor} for a BIDS dataset, or ${ArchiveInterpreter.Descriptor} for an archive"))
        else None
      interpretation -> declaration.fold(assessment)(f => Assessment.from(interpretation, assessment.policy, assessment.findings :+ f, assessment.checks))
    }

object DatasetInterpreter:
  val BidsDescriptor: String = "dataset_description.json"

final case class DerivativeProfile[F[_]](field: String, versions: Set[String], producer: String, interpreter: SemanticInterpreter[F])
