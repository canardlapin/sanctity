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
  def editableCompanions: Set[String] = Set("dwi.bval", "dwi.bvec", "events.tsv", "beh.tsv")
  def prepare(manifest: Manifest): F[(Interpretation, Assessment)]
  def prepare(manifest: Manifest, @scala.annotation.unused activity: Activity): F[(Interpretation, Assessment)] = prepare(manifest)

  def prepare(manifest: Manifest, activity: Activity, @scala.annotation.unused sources: Vector[Release]): F[(Interpretation, Assessment)] = prepare(manifest, activity)

/** Reads what an accepted collection says about itself, for the catalog (SEE2). It is total: a descriptor that cannot
  * be read yields a record with missing fields, never a failure, so describing a release cannot block its acceptance.
  */
trait ReleaseDescriber[F[_]]:
  def describe(manifest: Manifest, interpretation: Interpretation): F[CatalogRecord]

final case class AuthorizedFile[F[_]](digest: Digest, size: Long, bytes: Stream[F, Byte])

/** One accepted release for packaging. `read` rechecks current authority on every chunk, as a single-file read does. */
final case class AuthorizedRelease[F[_]](manifest: Manifest, acceptedAt: java.time.Instant, read: Entry.File => Stream[F, Byte])

trait ExportTarget[F[_]]:
  def materialize(manifest: Manifest, store: BlobStore[F]): F[ExportReceipt]

trait Catalog[F[_]]:
  def check(ctx: AccessContext, dataset: DatasetId, action: Action, release: Option[ReleaseId] = None): F[Either[Problem, Unit]]
  /** `id` makes issuance safe to retry (ARC05). A caller that names the grant gets that grant back when it repeats the
    * same intent: recipient, scope, action set and expiry. Naming an existing grant with a different intent, or one that
    * was revoked, is a conflict; a retry never renews access. Current authority is checked before either outcome.
    * Without `id` every call issues a new grant.
    */
  def grant(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId], actions: Set[Action],
      id: Option[GrantId] = None): F[Either[Problem, Grant]]
  def revoke(ctx: AccessContext, dataset: DatasetId, grant: GrantId): F[Either[Problem, Unit]]
  /** A share with any ADR 002 permission set (including Discover/ReadEvidence) and an optional expiry, issued at the
    * database clock under IssueGrant authority. Requires an initialized organization. `id` behaves as for `grant`.
    */
  def grantShare(ctx: AccessContext, dataset: DatasetId, recipient: PrincipalId, release: Option[ReleaseId],
      actions: Set[OrganizationAuthority.Permission], expiresAt: Option[java.time.Instant],
      id: Option[GrantId] = None): F[Either[Problem, OrganizationAuthority.PolicyGrant]]
  /** Shares on a dataset, for its current access managers. */
  def grants(ctx: AccessContext, dataset: DatasetId): F[Either[Problem, Vector[OrganizationAuthority.GrantView]]]
  /** Why `subject` holds or lacks `permission` on `scope`: the caller's own access, or a principal known on that
    * dataset for a current access manager. Denials say nothing about other principals or hidden policy. */
  def explainAccess(ctx: AccessContext, subject: PrincipalId, scope: OrganizationAuthority.DataScope,
      permission: OrganizationAuthority.Permission): F[Either[Problem, OrganizationAuthority.Explanation]]
  def select(ctx: AccessContext, releases: Vector[ReleaseRef], query: ScientificQuery): F[Either[Problem, FrozenSelection]]
  def selection(ctx: AccessContext, id: SelectionId): F[Either[Problem, FrozenSelection]]
  def startRun(ctx: AccessContext, spec: RunSpec, key: IdempotencyKey): F[Either[Problem, ProcessingRun]]
  def readRun(ctx: AccessContext, id: RunId): F[Either[Problem, ProcessingRun]]
  /** Worker heartbeat. Rechecks both scopes; a denied active run is failed without
    * disclosing its record or allowing a revoked worker to deposit any output.
    */
  def heartbeatRun(ctx: AccessContext, id: RunId): F[Either[Problem, Unit]]
  /** Completed owner-held evidence under current input authority, independent of the old destination. */
  def completedRun(ctx: AccessContext, id: RunId): F[Either[Problem, ProcessingRun]]
  def advanceRun(ctx: AccessContext, id: RunId, generation: Long, update: RunUpdate): F[Either[Problem, ProcessingRun]]
  def observations(ctx: AccessContext, releases: Vector[ReleaseRef], query: ObservationQuery): F[Either[Problem, Vector[ObservationHit]]]
  def create(ctx: AccessContext, custodian: CustodianId): F[Dataset]
  def dataset(ctx: AccessContext, id: DatasetId): F[Either[Problem, Dataset]]
  def draft(ctx: AccessContext, dataset: DatasetId, base: Option[ReleaseId], manifest: Manifest, activity: Activity): F[Either[Problem, Draft]]
  def readDraft(ctx: AccessContext, id: DraftId): F[Either[Problem, Draft]]
  def revise(ctx: AccessContext, id: DraftId, generation: Long, manifest: Manifest, activity: Activity): F[Either[Problem, Draft]]
  def prepared(ctx: AccessContext, draft: DraftId, generation: Long, interpretation: Interpretation, assessment: Assessment): F[Either[Problem, Candidate]]
  def candidate(ctx: AccessContext, id: CandidateId): F[Either[Problem, Candidate]]
  def receipt(ctx: AccessContext, candidate: CandidateId, key: IdempotencyKey): F[Either[Problem, Option[AcceptanceReceipt]]]
  /** `description` is the release's catalog record, stored in the same transaction that accepts it. A replay of an
    * accepted key returns the first receipt and stores nothing.
    */
  def accept(ctx: AccessContext, candidate: CandidateId, key: IdempotencyKey, lease: Option[WorkLease] = None,
      description: Option[CatalogRecord] = None): F[Either[Problem, AcceptanceReceipt]]
  /** The record stored when the release was accepted, if any. The caller may read that exact release's metadata, or,
    * in an organization, may discover that exact release (ADR 005).
    */
  def catalogRecord(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Option[CatalogRecord]]]
  /** Internal: the accepted release, only for deriving the catalog record of a release that has none stored. It is
    * authorized exactly as `catalogRecord` is, so it can reach a caller who may only discover the release. Nothing
    * but the derived record may be returned to that caller.
    */
  def releaseForDescription(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]]
  def release(ctx: AccessContext, ref: ReleaseRef): F[Either[Problem, Release]]
  def metadata(ctx: AccessContext, ref: ReleaseRef, path: RelPath): F[Either[Problem, ArtifactMetadata]]
