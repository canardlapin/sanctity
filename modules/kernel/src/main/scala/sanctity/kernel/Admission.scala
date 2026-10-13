package sanctity.kernel

/** Shared scientific admission rules. Adapters supply atomically observed state and authority. */
object Admission:
  def revision(current: Activity, next: Activity): Either[Problem, Unit] =
    Either.cond(current.publication == next.publication &&
      (current.publication.isEmpty || current.actor == next.actor) &&
      (current.processing.isEmpty || current.processing == next.processing) &&
      (current.kind != ActivityKind.PrivateBundle || (next.kind == current.kind && next.projection == current.projection && next.actor == current.actor)),
      (), Problem.Conflict("draft revision cannot replace processing provenance or private scope"))

  def prepared(draft: Draft, generation: Long, accepted: Boolean, interpretation: Interpretation, assessment: Assessment, baseContent: Option[ManifestId] = None): Either[Problem, Unit] =
    for
      _ <- Either.cond(!accepted, (), Problem.Conflict("draft already accepted"))
      _ <- Either.cond(draft.generation == generation, (), Problem.Conflict("stale preparation"))
      _ <- reinterpretation(draft, baseContent)
      _ <- projection(draft.manifest, draft.activity)
      _ <- change(draft.dataset, draft.base, draft.manifest, draft.activity)
      _ <- processing(draft.dataset, draft.manifest, draft.activity, interpretation, assessment)
      _ <- publication(draft.dataset, draft.base, draft.activity)
      _ <- bindings(draft.manifest, interpretation, assessment)
    yield ()

  def accept(dataset: Dataset, draft: Draft, candidate: Candidate, accepted: Boolean, baseContent: Option[ManifestId] = None): Either[Problem, Unit] =
    for
      _ <- Either.cond(!accepted, (), Problem.Conflict("draft already accepted under another key"))
      _ <- Either.cond(candidate.dataset == dataset.id && draft.dataset == dataset.id && candidate.draft == draft.id,
        (), Problem.Conflict("candidate belongs to another dataset or draft"))
      _ <- Either.cond(candidate.activity.kind != ActivityKind.PrivateBundle ||
        (candidate.base.isEmpty && candidate.activity.actor == dataset.curator && candidate.activity.bundle.nonEmpty && candidate.activity.projection.nonEmpty),
        (), Problem.Conflict("private bundle requires its creator's empty destination and complete evidence"))
      _ <- Either.cond(dataset.head == candidate.base && draft.base == candidate.base, (), Problem.Conflict("dataset head advanced"))
      _ <- Either.cond(draft.generation == candidate.generation && draft.manifest.id == candidate.manifest.id &&
        draft.activity == candidate.activity, (), Problem.Conflict("prepared draft changed"))
      _ <- reinterpretation(draft, baseContent)
      _ <- projection(candidate.manifest, candidate.activity)
      _ <- change(candidate.dataset, candidate.base, candidate.manifest, candidate.activity)
      _ <- processing(candidate.dataset, candidate.manifest, candidate.activity, candidate.interpretation, candidate.assessment)
      _ <- publication(candidate.dataset, candidate.base, candidate.activity)
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

  private def processing(dataset: DatasetId, manifest: Manifest, activity: Activity,
      interpretation: Interpretation, assessment: Assessment): Either[Problem, Unit] =
    val bundle = activity.bundle.forall(b => Set(ActivityKind.ScientificView, ActivityKind.PrivateBundle)(activity.kind) &&
      b.matches(manifest, interpretation, assessment, activity.projection))
    val derivative = activity.processing match
      case None => activity.kind != ActivityKind.Derivative
      case Some(p) => activity.kind == ActivityKind.Derivative && p.input.dataset != dataset &&
        activity.bundle.isEmpty && activity.projection.isEmpty && p.matches(manifest) &&
        activity.retained.contains(p.producer.code) && activity.retained.contains(p.request) && assessment.checks.exists(c =>
          c.rule == "processing-receipt-v1" && c.facts.get("receipt").contains(MetadataValue.Text(p.id.value)))
    Either.cond(bundle && derivative, (), Problem.Conflict("processing evidence is incomplete or differs from the prepared scientific object"))

  private def publication(dataset: DatasetId, base: Option[ReleaseId], activity: Activity): Either[Problem, Unit] =
    Either.cond(activity.publication.forall(_.matches(dataset, base, activity)), (),
      Problem.Conflict("publication intent differs from the prepared destination or receipt"))

  private def bindings(manifest: Manifest, interpretation: Interpretation, assessment: Assessment): Either[Problem, Unit] =
    Either.cond(interpretation.content == manifest.id && assessment.content == manifest.id &&
      assessment.interpretation == interpretation.id &&
      assessment.checks.forall(_.inputs.forall(i => manifest.byPath.get(i.path).exists {
        case Entry.File(_, _, blob) => blob == i.blob
        case _ => false
      })), (), Problem.Conflict("preparation evidence does not identify the draft"))
