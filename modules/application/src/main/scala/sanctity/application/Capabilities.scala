package sanctity.application

import cats.MonadThrow
import cats.effect.Sync
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

import java.util.UUID

final case class Failure(problem: Problem) extends RuntimeException(problem.message)

object Checked:
  def lift[F[_]: MonadThrow, A](result: Either[Problem, A]): F[A] =
    result.leftMap(Failure.apply).liftTo[F]

  def attempt[F[_]: MonadThrow, A](action: F[A]): F[Either[Problem, A]] =
    action.attempt.map(_.leftMap {
      case Failure(problem) => problem
      case error => Problem.Operational(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    })

trait IdSource[F[_]]:
  def next[A]: F[Id[A]]
object IdSource:
  def random[F[_]: Sync]: IdSource[F] = new IdSource[F]:
    def next[A]: F[Id[A]] = Sync[F].delay(Id[A](UUID.randomUUID()))

enum SourceEntry:
  case Directory(override val path: RelPath)
  case File(override val path: RelPath, size: Long, observation: String)

  def path: RelPath = this match
    case Directory(path) => path
    case File(path, _, _) => path

trait NativeSource[F[_]]:
  def evidence: Option[BlobRef] = None
  def retainedEvidence: Vector[BlobRef] = Vector.empty
  def label: String
  def entries: Stream[F, SourceEntry]
  def read(file: SourceEntry.File): Stream[F, Byte]

trait BlobStore[F[_]]:
  def put(bytes: Stream[F, Byte]): F[BlobRef]
  /** The stream verifies identity before successful completion. */
  def read(blob: BlobRef): Stream[F, Byte]
  def verify(blob: BlobRef): F[Unit]

trait SemanticInterpreter[F[_]]:
  def prepare(manifest: Manifest): F[(Interpretation, Assessment)]

final case class AuthorizedFile[F[_]](digest: Digest, size: Long, bytes: Stream[F, Byte])

trait ExportTarget[F[_]]:
  def materialize(manifest: Manifest, store: BlobStore[F]): F[ExportReceipt]

trait Catalog[F[_]]:
  def check(ctx: AccessContext, dataset: DatasetId, action: Action, release: Option[ReleaseId] = None): F[Either[Problem, Unit]]
  def grant(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId], actions: Set[Action]): F[Either[Problem, Grant]]
  def revoke(ctx: AccessContext, dataset: DatasetId, grant: GrantId): F[Either[Problem, Unit]]
  def select(ctx: AccessContext, releases: Vector[ReleaseRef], query: ScientificQuery): F[Either[Problem, FrozenSelection]]
  def selection(ctx: AccessContext, id: SelectionId): F[Either[Problem, FrozenSelection]]
  def create(ctx: AccessContext, custodian: CustodianId): F[Dataset]
  def dataset(ctx: AccessContext, id: DatasetId): F[Either[Problem, Dataset]]
  def draft(ctx: AccessContext, dataset: DatasetId, base: Option[ReleaseId], manifest: Manifest, activity: Activity): F[Either[Problem, Draft]]
  def readDraft(ctx: AccessContext, id: DraftId): F[Either[Problem, Draft]]
  def revise(ctx: AccessContext, id: DraftId, generation: Long, manifest: Manifest, activity: Activity): F[Either[Problem, Draft]]
  def prepared(ctx: AccessContext, draft: DraftId, generation: Long, interpretation: Interpretation, assessment: Assessment): F[Either[Problem, Candidate]]
  def candidate(ctx: AccessContext, id: CandidateId): F[Either[Problem, Candidate]]
  def receipt(ctx: AccessContext, candidate: CandidateId, key: IdempotencyKey): F[Either[Problem, Option[AcceptanceReceipt]]]
  def accept(ctx: AccessContext, candidate: CandidateId, key: IdempotencyKey, lease: Option[WorkLease] = None): F[Either[Problem, AcceptanceReceipt]]
  def release(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]]
  def metadata(ctx: AccessContext, ref: ReleaseRef, path: RelPath): F[Either[Problem, ArtifactMetadata]]
