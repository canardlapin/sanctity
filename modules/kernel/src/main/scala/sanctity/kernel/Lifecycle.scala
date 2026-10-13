package sanctity.kernel

import java.time.Instant

/** Trusted in-process context; network adapters must supply a currently verified credential. */
final case class AccessContext(principal: PrincipalId, credential: Option[CredentialId] = None)
final case class Dataset(id: DatasetId, custodian: CustodianId, curator: PrincipalId, head: Option[ReleaseId])
final case class ReleaseRef(dataset: DatasetId, release: ReleaseId)

enum ActivityKind:
  case NativeImport, MetadataCorrection, ScientificView, ScientificChange, Reinterpretation, Derivative, PrivateBundle

final case class Activity(kind: ActivityKind, actor: PrincipalId, reason: String, source: Option[String], at: Instant, projection: Option[ProjectionEvidence] = None, evidence: Option[BlobRef] = None, retained: Vector[BlobRef] = Vector.empty, change: Option[ChangeEvidence] = None, bundle: Option[BundleEvidence] = None, processing: Option[ProcessingEvidence] = None, publication: Option[PublicationIntent] = None)
final case class Draft(id: DraftId, dataset: DatasetId, base: Option[ReleaseId], generation: Long, manifest: Manifest, activity: Activity)
final case class Candidate(
    id: CandidateId,
    draft: DraftId,
    dataset: DatasetId,
    base: Option[ReleaseId],
    generation: Long,
    manifest: Manifest,
    interpretation: Interpretation,
    assessment: Assessment,
    activity: Activity
)
final case class Release(
    ref: ReleaseRef,
    parent: Option[ReleaseId],
    manifest: Manifest,
    interpretation: Interpretation,
    assessment: Assessment,
    activity: Activity,
    acceptedBy: PrincipalId,
    acceptedAt: Instant
)
final case class AcceptanceReceipt(release: ReleaseRef, candidate: CandidateId, key: IdempotencyKey, at: Instant)
final case class Preview(candidate: Candidate, contentChanges: Vector[EntryDelta], metadataChanges: Vector[ArtifactImpact],
    relationshipChanges: Vector[RelationshipDelta] = Vector.empty, dependencyImpact: DependencyImpact = DependencyImpact.empty)
final case class ExportReceipt(manifest: ManifestId, files: Int, directories: Int, bytes: Long)
