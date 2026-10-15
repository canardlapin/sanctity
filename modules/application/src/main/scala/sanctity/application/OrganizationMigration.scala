package sanctity.application

import sanctity.kernel.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** Deterministic, explicit legacy mapping; never infers a verified identity or shares data. */
object OrganizationMigration:
  def id[A](kind: String, parts: String*): Id[A] =
    Id[A](UUID.nameUUIDFromBytes(("sanctity.organization.v1:" + kind + ":" + parts.mkString(":")).getBytes(StandardCharsets.UTF_8)))
  def lab(operator: PrincipalId): LabId = id[LabTag]("legacy-lab", operator.render)
  def project(dataset: Dataset): ProjectId = id[ProjectTag]("legacy-project", dataset.custodian.render, dataset.curator.render)
  def stewardship(dataset: DatasetId): DatasetStewardshipId = id[DatasetStewardshipTag]("legacy-stewardship", dataset.render)

  def transform(principals: Vector[OrganizationPrincipal], datasets: Vector[Dataset],
      operator: PrincipalId, at: Instant): Either[Problem, ReferenceOrganizationCatalog.Snapshot] =
    val people = principals.map(p => p.id -> p).toMap
    val valid = people.size == principals.size && datasets.map(_.id).distinct.size == datasets.size &&
      people.get(operator).exists(_.active) && datasets.forall(d => people.get(d.curator).exists(_.active))
    if !valid then Left(Problem.Integrity("invalid legacy organization references"))
    else
      val generation = OrganizationGeneration.initial
      val description = OrganizationDescription.from("Private legacy mapping; no verified identity inferred").toOption.get
      val labId = lab(operator)
      val memberId = id[LabMembershipTag]("bootstrap-admin", operator.render)
      val projects = datasets.map(project).distinct.map { p =>
        p -> Project(p, labId, OrganizationName.from("Legacy project " + p.render.take(8)).toOption.get,
          description, OrganizationStatus.Active, generation, at)
      }.toMap
      val roles = datasets.map(d => project(d) -> d.curator).distinct.map { (p, curator) =>
        val key = id[ProjectParticipationTag]("legacy-project-steward", p.render, curator.render)
        key -> ProjectParticipation(key, p, curator, OrganizationRole.Steward, ParticipationBasis.Independent,
          AppointmentStatus.Active, None, generation, at)
      }.toMap
      Right(ReferenceOrganizationCatalog.Snapshot(people, datasets.map(d => d.id -> d).toMap,
        labs = Map(labId -> Lab(labId, OrganizationName.from("Legacy migration lab").toOption.get, description,
          OrganizationStatus.Active, generation, at)), projects = projects,
        memberships = Map(memberId -> LabMembership(memberId, labId, operator, LabRole.Admin,
          AppointmentStatus.Active, None, generation, at)), participations = roles,
        placements = datasets.map(d => d.id -> DatasetPlacement(d.id, project(d), PlacementPolicy.ExplicitOnly, generation, at)).toMap,
        stewardships = datasets.map(d => stewardship(d.id) -> DatasetStewardship(stewardship(d.id), d.id, d.curator,
          AppointmentStatus.Active, generation, at)).toMap))
