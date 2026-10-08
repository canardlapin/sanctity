package sanctity.kernel

sealed trait GrantTag
type GrantId = Id[GrantTag]

enum Action:
  case ReadMetadata, ReadContent, Propose, Accept, ManageAccess

/** Fixed releases permit reading only. A dataset scope explicitly includes future releases. */
final case class Grant(id: GrantId, dataset: DatasetId, recipient: PrincipalId,
    release: Option[ReleaseId], actions: Set[Action])

object Authority:
  def validate(release: Option[ReleaseId], actions: Set[Action]): Either[Problem, Unit] =
    Either.cond(actions.nonEmpty && !actions.contains(Action.ManageAccess) &&
      (release.isEmpty || actions.subsetOf(Set(Action.ReadMetadata, Action.ReadContent))),
      (), Problem.Unsupported("grants require nonempty actions; fixed releases are read-only; delegation is not supported"))

  def permits(dataset: Dataset, grants: Iterable[Grant], ctx: AccessContext,
      action: Action, release: Option[ReleaseId]): Boolean =
    dataset.curator == ctx.principal || grants.exists(g =>
      g.dataset == dataset.id && g.recipient == ctx.principal && g.actions.contains(action) &&
      (g.release.isEmpty || g.release == release))
