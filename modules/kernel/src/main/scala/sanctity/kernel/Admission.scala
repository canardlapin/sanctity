package sanctity.kernel

/** Shared scientific admission rules. Adapters supply atomically observed state and authority. */
object Admission:
  def prepared(draft: Draft, generation: Long, accepted: Boolean, interpretation: Interpretation, assessment: Assessment): Either[Problem, Unit] =
    for
      _ <- Either.cond(!accepted, (), Problem.Conflict("draft already accepted"))
      _ <- Either.cond(draft.generation == generation, (), Problem.Conflict("stale preparation"))
      _ <- bindings(draft.manifest, interpretation, assessment)
    yield ()

  def accept(dataset: Dataset, draft: Draft, candidate: Candidate, accepted: Boolean): Either[Problem, Unit] =
    for
      _ <- Either.cond(!accepted, (), Problem.Conflict("draft already accepted under another key"))
      _ <- Either.cond(candidate.dataset == dataset.id && draft.dataset == dataset.id && candidate.draft == draft.id,
        (), Problem.Conflict("candidate belongs to another dataset or draft"))
      _ <- Either.cond(dataset.head == candidate.base && draft.base == candidate.base, (), Problem.Conflict("dataset head advanced"))
      _ <- Either.cond(draft.generation == candidate.generation && draft.manifest.id == candidate.manifest.id &&
        draft.activity == candidate.activity, (), Problem.Conflict("prepared draft changed"))
      _ <- bindings(candidate.manifest, candidate.interpretation, candidate.assessment)
      _ <- Either.cond(candidate.assessment.admissible, (), Problem.Rejected(candidate.assessment.errors))
    yield ()

  private def bindings(manifest: Manifest, interpretation: Interpretation, assessment: Assessment): Either[Problem, Unit] =
    Either.cond(interpretation.content == manifest.id && assessment.content == manifest.id &&
      assessment.interpretation == interpretation.id &&
      assessment.checks.forall(_.inputs.forall(i => manifest.byPath.get(i.path).exists {
        case Entry.File(_, _, blob) => blob == i.blob
        case _ => false
      })), (), Problem.Conflict("preparation evidence does not identify the draft"))
