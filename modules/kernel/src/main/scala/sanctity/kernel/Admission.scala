package sanctity.kernel

/** Shared scientific admission rules. Adapters supply atomically observed state and authority. */
object Admission:
  def prepared(draft: Draft, generation: Long, accepted: Boolean, interpretation: Interpretation, assessment: Assessment, baseContent: Option[ManifestId] = None): Either[Problem, Unit] =
    for
      _ <- Either.cond(!accepted, (), Problem.Conflict("draft already accepted"))
      _ <- Either.cond(draft.generation == generation, (), Problem.Conflict("stale preparation"))
      _ <- reinterpretation(draft, baseContent)
      _ <- projection(draft.manifest, draft.activity)
      _ <- change(draft.dataset, draft.base, draft.manifest, draft.activity)
      _ <- bindings(draft.manifest, interpretation, assessment)
    yield ()

  def accept(dataset: Dataset, draft: Draft, candidate: Candidate, accepted: Boolean, baseContent: Option[ManifestId] = None): Either[Problem, Unit] =
    for
      _ <- Either.cond(!accepted, (), Problem.Conflict("draft already accepted under another key"))
      _ <- Either.cond(candidate.dataset == dataset.id && draft.dataset == dataset.id && candidate.draft == draft.id,
        (), Problem.Conflict("candidate belongs to another dataset or draft"))
      _ <- Either.cond(dataset.head == candidate.base && draft.base == candidate.base, (), Problem.Conflict("dataset head advanced"))
      _ <- Either.cond(draft.generation == candidate.generation && draft.manifest.id == candidate.manifest.id &&
        draft.activity == candidate.activity, (), Problem.Conflict("prepared draft changed"))
      _ <- reinterpretation(draft, baseContent)
      _ <- projection(candidate.manifest, candidate.activity)
      _ <- change(candidate.dataset, candidate.base, candidate.manifest, candidate.activity)
      _ <- bindings(candidate.manifest, candidate.interpretation, candidate.assessment)
      _ <- Either.cond(candidate.assessment.admissible, (), Problem.Rejected(candidate.assessment.errors))
    yield ()

  private def reinterpretation(draft: Draft, baseContent: Option[ManifestId]): Either[Problem, Unit] =
    Either.cond(draft.activity.kind != ActivityKind.Reinterpretation ||
      (draft.base.nonEmpty && baseContent.contains(draft.manifest.id) &&
        draft.activity.projection.isEmpty && draft.activity.change.isEmpty), (),
      Problem.Conflict("reinterpretation requires unchanged content from an accepted base"))

  private def projection(manifest: Manifest, activity: Activity): Either[Problem, Unit] =
    Either.cond(activity.projection.forall(_.matches(manifest)), (),
      Problem.Conflict("projection receipt does not identify the prepared output"))

  private def change(dataset: DatasetId, base: Option[ReleaseId], manifest: Manifest, activity: Activity): Either[Problem, Unit] =
    val valid = activity.change match
      case None => activity.kind != ActivityKind.ScientificChange
      case Some(c) => activity.kind == ActivityKind.ScientificChange && c.source.dataset == dataset &&
        base.contains(c.source.release) && c.output == manifest.id && activity.evidence.contains(c.receipt)
    Either.cond(valid, (), Problem.Conflict("change receipt does not identify the base and prepared output"))

  private def bindings(manifest: Manifest, interpretation: Interpretation, assessment: Assessment): Either[Problem, Unit] =
    Either.cond(interpretation.content == manifest.id && assessment.content == manifest.id &&
      assessment.interpretation == interpretation.id &&
      assessment.checks.forall(_.inputs.forall(i => manifest.byPath.get(i.path).exists {
        case Entry.File(_, _, blob) => blob == i.blob
        case _ => false
      })), (), Problem.Conflict("preparation evidence does not identify the draft"))
