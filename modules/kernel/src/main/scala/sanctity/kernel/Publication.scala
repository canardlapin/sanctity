package sanctity.kernel

/** An explicit publication decision; it never changes the execution request or result. */
final case class PublicationIntent(run: RunId, receipt: Digest, publisher: PrincipalId,
    destination: DatasetId, base: Option[ReleaseId], key: IdempotencyKey):
  def id: Digest = Digest.framed("sanctity.publication-intent.v1", Vector(run.render, receipt.value,
    publisher.render, destination.render, base.nonEmpty.toString) ++ base.toVector.map(_.render) ++ Vector(key.value))

  /** State-independent binding. Catalogs additionally check current authority and destination history. */
  def validate(ctx: AccessContext, completed: ProcessingRun): Either[Problem, ProcessingEvidence] =
    if publisher != ctx.principal || completed.owner != publisher then Left(Problem.Denied)
    else completed.result.filter(p => completed.valid && completed.status == RunStatus.Completed &&
      completed.id == run && p.id == receipt && p.input.dataset != destination)
      .toRight(Problem.Conflict("publication requires the exact completed run and a separate destination"))

  def matches(dataset: DatasetId, expectedBase: Option[ReleaseId], activity: Activity): Boolean =
    destination == dataset && base == expectedBase && publisher == activity.actor &&
      activity.kind == ActivityKind.Derivative && activity.processing.exists(p => p.invocation == run && p.id == receipt)

  /** Keys are scoped to the publisher, independently of run and destination. */
  def retry(request: PublicationIntent): Either[Problem, Unit] =
    Either.cond(this == request, (), Problem.Conflict("publication key belongs to another request"))
