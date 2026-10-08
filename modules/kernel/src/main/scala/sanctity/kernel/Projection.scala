package sanctity.kernel

/** This profile is a declared disclosure policy, not a de-identification certificate. */
final case class DisclosureProfile(participantFields: Set[String], metadataFields: Set[String]):
  val id: Digest = Digest.framed("sanctity.mri-view.v1",
    Vector(participantFields.size.toString) ++ participantFields.toVector.sorted ++
      Vector(metadataFields.size.toString) ++ metadataFields.toVector.sorted)

final case class ProjectionEvidence(
    source: ReleaseRef,
    selection: SelectionId,
    profile: Digest,
    compiler: String,
    generated: Vector[RelPath]
)

final case class ProjectionPlan(retained: Vector[Entry.File], generated: Vector[(RelPath, String)],
    evidence: ProjectionEvidence)
