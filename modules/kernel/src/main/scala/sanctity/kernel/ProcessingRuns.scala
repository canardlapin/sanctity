package sanctity.kernel

final case class RunSpec(input: ReleaseRef, bundle: Digest, destination: DatasetId, base: Option[ReleaseId], recipe: InputContract):
  def valid: Boolean = destination != input.dataset && recipe.valid
  def validateInput(r: Release): Either[Problem, Unit] = Either.cond(valid && r.ref == input && r.activity.bundle.exists(b =>
    b.id == bundle && b.readiness.contract == recipe && b.matches(r.manifest, r.interpretation, r.assessment, r.activity.projection)),
    (), Problem.Conflict("run does not identify an accepted input contract"))

final case class ExecutionEvidence(producer: ProducerIdentity, request: BlobRef, targets: Vector[RelPath]):
  def valid: Boolean = producer.valid && targets.nonEmpty && targets.size <= 100 && targets == targets.distinct.sortBy(_.value)
  def retained: Vector[BlobRef] = Vector(producer.code, request).distinct

enum RunStatus:
  case Planned, Preparing, Executing, Completed, Failed, Interrupted

final case class RunUpdate(status: RunStatus, execution: Option[ExecutionEvidence] = None,
    result: Option[ProcessingEvidence] = None, detail: Option[String] = None)

/** Executing is deliberately not auto-retried after restart: the outcome may be unknown. */
final case class ProcessingRun(id: RunId, owner: PrincipalId, spec: RunSpec, key: IdempotencyKey,
    generation: Long, status: RunStatus, execution: Option[ExecutionEvidence] = None,
    result: Option[ProcessingEvidence] = None, detail: Option[String] = None):
  def valid: Boolean = spec.valid && generation >= 0 && detail.forall(s => s.nonEmpty && s.length <= 4096) && (status match
    case RunStatus.Planned => generation == 0 && execution.isEmpty && result.isEmpty && detail.isEmpty
    case RunStatus.Preparing => generation == 1 && execution.isEmpty && result.isEmpty && detail.isEmpty
    case RunStatus.Executing => generation == 2 && execution.exists(_.valid) && result.isEmpty && detail.isEmpty
    case RunStatus.Completed => generation == 3 && execution.exists(_.valid) && detail.isEmpty && result.exists { p =>
      p.valid && p.invocation == id && p.input == spec.input && p.bundle == spec.bundle && execution.exists(e =>
        e.producer == p.producer && e.request == p.request && e.targets == p.targets)
    }
    case RunStatus.Failed | RunStatus.Interrupted => generation >= 1 && generation <= 3 && result.isEmpty && detail.nonEmpty &&
      (if generation == 3 then execution.exists(_.valid) else execution.isEmpty))

  def advance(update: RunUpdate): Either[Problem, ProcessingRun] =
    val valid = this.valid && generation < Long.MaxValue && update.detail.forall(s => s.nonEmpty && s.length <= 4096) && ((status, update.status) match
      case (RunStatus.Planned, RunStatus.Preparing) => update.execution.isEmpty && update.result.isEmpty && update.detail.isEmpty
      case (RunStatus.Preparing, RunStatus.Executing) => update.execution.exists(_.valid) && update.result.isEmpty && update.detail.isEmpty
      case (RunStatus.Executing, RunStatus.Completed) => update.execution == execution && update.detail.isEmpty && update.result.exists { p =>
        p.valid && p.invocation == id && p.input == spec.input && p.bundle == spec.bundle && execution.exists(e =>
          e.producer == p.producer && e.request == p.request && e.targets == p.targets)
      }
      case (RunStatus.Planned | RunStatus.Preparing | RunStatus.Executing, RunStatus.Failed | RunStatus.Interrupted) =>
        update.execution == execution && update.result.isEmpty && update.detail.nonEmpty
      case _ => false)
    Either.cond(valid, copy(generation = generation + 1, status = update.status, execution = update.execution,
      result = update.result, detail = update.detail), Problem.Conflict("invalid or terminal processing run transition"))
  def submission(ctx: AccessContext, destination: DatasetId, base: Option[ReleaseId], receipt: ProcessingEvidence): Either[Problem, Unit] =
    Either.cond(owner == ctx.principal && status == RunStatus.Completed && spec.destination == destination && spec.base == base &&
      result.exists(_.id == receipt.id), (), Problem.Conflict("collection differs from the completed run and its destination"))

  def retained: Vector[BlobRef] = (execution.toVector.flatMap(_.retained) ++ result.toVector.flatMap(_.expected.map(_.blob))).distinct
