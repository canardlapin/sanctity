package sanctity.app

import cats.effect.{ExitCode, IO}
import sanctity.application.{Checked, OrganizationMigration}
import sanctity.kernel.*
import sanctity.postgres.PostgresDatabase

/** Trusted operator organization commands (ADR 001/003): lab creation, principal disablement and stewardship recovery.
  * They require the operator database connection and the configured bootstrap operator principal, and each takes the
  * generation the operator reviewed, so a concurrent change is refused instead of overwritten.
  */
object OrganizationCli extends ProtectedIOApp:
  val usage: String = """usage: organization recovery-needs OPERATOR
       organization create-lab OPERATOR ADMIN NAME KEY
       organization disable-principal OPERATOR PRINCIPAL GENERATION KEY
       organization recover-steward OPERATOR DATASET PRINCIPAL PLACEMENT_GENERATION KEY REASON..."""

  private def lift[A](v: Either[Problem, A]): IO[A] = Checked.lift[IO, A](v)
  private def generation(text: String): IO[OrganizationGeneration] =
    lift(text.toLongOption.toRight(Problem.Unsupported("generation must be a number")).flatMap(OrganizationGeneration.from))
  private def run(db: PostgresDatabase[IO], operator: PrincipalId, key: String, expected: Map[OrganizationRef, OrganizationGeneration],
      command: OrganizationCommand): IO[OrganizationReceipt] =
    lift(IdempotencyKey.from(key)).flatMap(k => db.organization.execute(AccessContext(operator), OrganizationMutation(k, expected, command)))
      .flatMap(lift)

  def command(db: PostgresDatabase[IO], args: List[String]): IO[String] =
    db.requireOperator *> db.checkCompatibility() *> (args match
      case "recovery-needs" :: person :: Nil =>
        for
          _ <- lift(Id.parse[PrincipalTag](person))
          s <- db.organization.snapshot
          now <- IO.realTimeInstant
          needs = s.recoveryNeeds(now)
        yield (needs.labs.toVector.map(l => s"lab ${l.render} generation ${s.labs(l).generation.value}") ++
          needs.projects.toVector.map(p => s"project ${p.render} generation ${s.projects(p).generation.value}") ++
          needs.datasets.toVector.map(d => s"dataset ${d.render} placement-generation ${s.placements(d).generation.value}"))
          .sorted.mkString("\n") match { case "" => "No lab, project or dataset needs recovery."; case text => text }
      case "create-lab" :: person :: admin :: name :: key :: Nil =>
        for
          operator <- lift(Id.parse[PrincipalTag](person))
          administrator <- lift(Id.parse[PrincipalTag](admin))
          label <- lift(OrganizationName.from(name))
          blank <- lift(OrganizationDescription.from(""))
          lab = OrganizationMigration.id[LabTag]("operator-lab", operator.render, key)
          member = OrganizationMigration.id[LabMembershipTag]("operator-lab-admin", operator.render, key)
          receipt <- run(db, operator, key, Map.empty, OrganizationCommand.CreateLab(lab, administrator, member, label, blank))
        yield s"Lab ${lab.render} created with administrator ${administrator.render} (sequence ${receipt.sequence}, ${receipt.durability})."
      case "disable-principal" :: person :: target :: expected :: key :: Nil =>
        for
          operator <- lift(Id.parse[PrincipalTag](person))
          principal <- lift(Id.parse[PrincipalTag](target))
          g <- generation(expected)
          receipt <- run(db, operator, key, Map(OrganizationRef.Principal(principal) -> g), OrganizationCommand.DisablePrincipal(principal))
        yield s"Principal ${principal.render} disabled; credentials and import leases revoked (sequence ${receipt.sequence}, ${receipt.durability})."
      case "recover-steward" :: person :: d :: target :: expected :: key :: reason if reason.nonEmpty =>
        for
          operator <- lift(Id.parse[PrincipalTag](person))
          dataset <- lift(Id.parse[DatasetTag](d))
          principal <- lift(Id.parse[PrincipalTag](target))
          g <- generation(expected)
          why <- lift(OrganizationDescription.from(reason.mkString(" ")))
          stewardship = OrganizationMigration.id[DatasetStewardshipTag]("operator-recovery", operator.render, dataset.render, key)
          receipt <- run(db, operator, key, Map(OrganizationRef.Placement(dataset) -> g),
            OrganizationCommand.RecoverSteward(dataset, stewardship, principal, why))
        yield s"Steward ${principal.render} appointed to ${dataset.render} by recovery (sequence ${receipt.sequence}, ${receipt.durability})."
      case _ => IO.raiseError(ConfigurationFailure(usage)))

  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    ServiceConfiguration.database(sys.env).flatMap { config =>
      PolicyClientConfiguration.database(config, sys.env).use(db => command(db, args).flatMap(IO.println))
    }
  }
