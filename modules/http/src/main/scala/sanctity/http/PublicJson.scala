package sanctity.http

import io.circe.Json
import sanctity.kernel.*

/** Explicit disclosure boundary. No generic encoders for internal releases, activities or blob references. */
private[http] object PublicJson:
  private def text(s: String): Json = Json.fromString(s)
  private def opt[A](a: Option[A])(f: A => Json): Json = a.fold(Json.Null)(f)
  def ref(r: ReleaseRef): Json = Json.obj("dataset" -> text(r.dataset.render), "release" -> text(r.release.render))
  def finding(f: Finding): Json = Json.obj("code" -> text(f.code), "severity" -> text(f.severity.toString),
    "path" -> opt(f.path)(p => text(p.value)), "message" -> text(f.message))
  def assessment(a: Assessment): Json = Json.obj("id" -> text(a.id.value.value), "policy" -> text(a.policy),
    "admissible" -> Json.fromBoolean(a.admissible), "findings" -> Json.arr(a.findings.map(finding)*),
    "checks" -> Json.arr(a.checks.map(contentCheck)*))
  def contentCheck(c: ContentCheck): Json = Json.obj(
    "rule" -> text(c.rule), "target" -> text(c.target.value),
    "inputs" -> Json.arr(c.inputs.map(i => Json.obj("role" -> text(i.role), "path" -> text(i.path.value),
      "digest" -> text(i.blob.digest.value), "size" -> Json.fromLong(i.blob.size)))*),
    "facts" -> Json.obj(c.facts.toVector.sortBy(_._1).map((k, v) => k -> value(v))*),
    "findings" -> Json.arr(c.findings.map(finding)*))
  def entry(e: Entry): Json = e match
    case Entry.Directory(p) => Json.obj("path" -> text(p.value), "kind" -> text("directory"))
    case Entry.File(p, _, b) => Json.obj("path" -> text(p.value), "kind" -> text("file"), "size" -> Json.fromLong(b.size))
  def relationship(r: ScientificRelationship): Json = Json.obj("from" -> text(r.from.value), "kind" -> text(r.kind),
    "to" -> Json.arr(r.to.map(p => text(p.value))*), "label" -> opt(r.label)(text),
    "evidence" -> Json.arr(r.evidence.map(relationshipEvidence)*))
  def relationshipEvidence(e: RelationshipEvidence): Json = e match
    case RelationshipEvidence.MetadataField(a, d, f) =>
      Json.obj("artifact" -> text(a.value), "document" -> text(d.value), "field" -> text(f))
    case RelationshipEvidence.TableColumn(a, d, column, rule) =>
      Json.obj("kind" -> Json.fromString("table-column"), "artifact" -> Json.fromString(a.value),
        "document" -> Json.fromString(d.value), "column" -> Json.fromString(column), "rule" -> Json.fromString(rule))
    case RelationshipEvidence.CompanionSelection(a, d, rule, root, entities, applicable) =>
      Json.obj("kind" -> text("companion-selection"), "artifact" -> text(a.value), "document" -> text(d.value),
        "rule" -> text(rule), "datasetRoot" -> opt(root)(p => text(p.value)),
        "matchedEntities" -> Json.obj(entities.toVector.sortBy(_._1).map((k, v) => k -> text(v))*),
        "applicable" -> Json.arr(applicable.map(p => text(p.value))*))
  /** Changed paths listed per group; counts are always exact. */
  val listedChanges = 200
  private def kindName(k: ActivityKind): String = k.toString.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase
  /** Curation history: what kind of change made the release, why, who proposed and accepted it, and which files
    * differ from its parent. Paths only, never digests; the activity's source label is not disclosed. */
  def provenance(r: Release, parent: Option[Release], identities: Boolean): Json =
    val a = r.activity
    val changes = parent.map { p =>
      val files = p.manifest.diff(r.manifest).filter(d => (d.before.toVector ++ d.after.toVector).exists(_.isInstanceOf[Entry.File]))
      def group(ds: Vector[EntryDelta]) = Json.obj("count" -> Json.fromInt(ds.size), "paths" -> Json.arr(ds.take(listedChanges).map(d => text(d.path.value))*))
      Json.obj("added" -> group(files.filter(_.before.isEmpty)), "changed" -> group(files.filter(d => d.before.nonEmpty && d.after.nonEmpty)),
        "removed" -> group(files.filter(_.after.isEmpty)))
    }
    Json.obj("kind" -> text(kindName(a.kind)), "reason" -> text(a.reason),
      "proposedBy" -> (if identities then text(a.actor.render) else Json.Null), "proposedAt" -> text(a.at.toString),
      "acceptedBy" -> (if identities then text(r.acceptedBy.render) else Json.Null), "changesFromParent" -> opt(changes)(identity))
  def release(r: Release, provenance: Option[Json] = None, showParent: Boolean = true): Json = Json.obj(
    "ref" -> ref(r.ref), "parent" -> opt(r.parent.filter(_ => showParent))(p => text(p.render)), "manifest" -> text(r.manifest.id.value.value),
    "interpretation" -> text(r.interpretation.id.value.value), "profile" -> text(r.interpretation.profile.name),
    "coverage" -> text(r.interpretation.profile.coverage), "assessment" -> assessment(r.assessment),
    "acceptedAt" -> text(r.acceptedAt.toString), "entries" -> Json.arr(r.manifest.entries.map(entry)*),
    "relationships" -> opt(r.interpretation.relationships)(g => Json.obj("version" -> text(g.version), "evidenceVersion" -> Json.fromInt(g.evidenceVersion),
      "links" -> Json.arr(g.relationships.map(relationship)*))),
    "provenance" -> provenance.getOrElse(Json.Null))
  def candidate(c: Candidate): Json = Json.obj("id" -> text(c.id.render), "dataset" -> text(c.dataset.render),
    "base" -> opt(c.base)(r => text(r.render)), "generation" -> Json.fromLong(c.generation),
    "manifest" -> text(c.manifest.id.value.value), "interpretation" -> text(c.interpretation.id.value.value),
    "assessment" -> assessment(c.assessment))
  def value(v: MetadataValue): Json = v match
    case MetadataValue.Text(v) => text(v)
    case MetadataValue.Number(v) => Json.fromBigDecimal(v)
    case MetadataValue.Flag(v) => Json.fromBoolean(v)
    case MetadataValue.Null => Json.Null
    case MetadataValue.Array(vs) => Json.arr(vs.map(value)*)
    case MetadataValue.Record(vs) => Json.obj(vs.toVector.sortBy(_._1).map((k, v) => k -> value(v))*)
  def field(f: EffectiveField): Json = Json.obj("value" -> value(f.value), "origin" -> text(f.origin.value),
    "history" -> Json.arr(f.history.map(c => Json.obj("source" -> text(c.source.value), "value" -> value(c.value)))*))
  def metadata(m: ArtifactMetadata): Json = Json.obj("path" -> text(m.path.value),
    "fields" -> Json.obj(m.fields.toVector.sortBy(_._1).map((k, v) => k -> field(v))*),
    "sources" -> Json.arr(m.sources.map(p => text(p.value))*))
  def preview(p: Preview): Json = Json.obj("candidate" -> candidate(p.candidate),
    "contentChanges" -> Json.arr(p.contentChanges.map(d => Json.obj("path" -> text(d.path.value),
      "before" -> opt(d.before)(entry), "after" -> opt(d.after)(entry)))*),
    "metadataChanges" -> Json.arr(p.metadataChanges.map(d => Json.obj("path" -> text(d.path.value),
      "fields" -> Json.arr(d.fields.map(f => Json.obj("name" -> text(f.name),
        "before" -> opt(f.before)(field), "after" -> opt(f.after)(field)))*)))*),
    "relationshipChanges" -> Json.arr(p.relationshipChanges.map(d => Json.obj("path" -> text(d.path.value),
      "before" -> Json.arr(d.before.map(relationship)*), "after" -> Json.arr(d.after.map(relationship)*),
      "changedDependencies" -> Json.arr(d.changedDependencies.map(p => text(p.value))*)))*))
  def receipt(r: AcceptanceReceipt): Json = Json.obj("ref" -> ref(r.release), "candidate" -> text(r.candidate.render),
    "key" -> text(r.key.value), "acceptedAt" -> text(r.at.toString))
  private def instant(i: java.time.Instant): Json = text(i.toString)
  private def lower(e: Any): Json = text(e.toString.toLowerCase)
  def organizationRef(r: OrganizationRef): String = r match
    case OrganizationRef.Principal(id) => "principal:" + id.render
    case OrganizationRef.Lab(id) => "lab:" + id.render
    case OrganizationRef.Project(id) => "project:" + id.render
    case OrganizationRef.Membership(id) => "membership:" + id.render
    case OrganizationRef.Participation(id) => "participation:" + id.render
    case OrganizationRef.Placement(id) => "placement:" + id.render
    case OrganizationRef.Stewardship(id) => "stewardship:" + id.render
    case OrganizationRef.Link(id) => "link:" + id.render
    case OrganizationRef.Move(id) => "move:" + id.render
  /** Durability is stated as recorded: LocalOnly, Pending or Durable (a verified independent COMMIT). */
  def organizationReceipt(r: OrganizationReceipt, created: Json): Json = Json.obj("sequence" -> Json.fromLong(r.sequence),
    "key" -> text(r.key.value), "at" -> instant(r.at), "durability" -> text(r.durability.toString),
    "affected" -> Json.arr(r.affected.toVector.map(organizationRef).sorted.map(text)*), "created" -> created)
  def membership(m: LabMembership): Json = Json.obj("id" -> text(m.id.render), "lab" -> text(m.lab.render),
    "principal" -> text(m.principal.render), "role" -> lower(m.role), "status" -> lower(m.status),
    "expiresAt" -> opt(m.expiresAt)(instant), "generation" -> Json.fromLong(m.generation.value))
  def participation(r: ProjectParticipation): Json = Json.obj("id" -> text(r.id.render), "project" -> text(r.project.render),
    "principal" -> text(r.principal.render), "role" -> lower(r.role), "basis" -> (r.basis match
      case ParticipationBasis.Independent => text("independent")
      case ParticipationBasis.Membership(id) => Json.obj("membership" -> text(id.render))),
    "status" -> lower(r.status), "expiresAt" -> opt(r.expiresAt)(instant), "generation" -> Json.fromLong(r.generation.value))
  def lab(v: sanctity.application.OrganizationService.LabView): Json = Json.obj("id" -> text(v.lab.id.render),
    "name" -> text(v.lab.name.value), "description" -> text(v.lab.description.value), "status" -> lower(v.lab.status),
    "generation" -> Json.fromLong(v.lab.generation.value), "roster" -> opt(v.roster)(ms => Json.arr(ms.map(membership)*)))
  def project(v: sanctity.application.OrganizationService.ProjectView): Json = Json.obj("id" -> text(v.project.id.render),
    "lab" -> text(v.project.lab.render), "name" -> text(v.project.name.value), "description" -> text(v.project.description.value),
    "status" -> lower(v.project.status), "generation" -> Json.fromLong(v.project.generation.value),
    "roster" -> opt(v.roster)(rs => Json.arr(rs.map(participation)*)))
  def placement(v: sanctity.application.OrganizationService.PlacementView): Json = Json.obj(
    "dataset" -> text(v.placement.dataset.render), "project" -> text(v.placement.project.render),
    "policy" -> text(v.placement.policy.toString), "generation" -> Json.fromLong(v.placement.generation.value),
    "stewards" -> Json.arr(v.stewards.map(x => Json.obj("id" -> text(x.id.render), "principal" -> text(x.principal.render),
      "status" -> lower(x.status), "generation" -> Json.fromLong(x.generation.value)))*))
  private def roles(m: Map[PrincipalId, OrganizationRole]): Json = Json.arr(m.toVector.sortBy(_._1.render)
    .map((p, r) => Json.obj("principal" -> text(p.render), "role" -> lower(r)))*)
  def move(v: sanctity.application.OrganizationService.MoveView): Json =
    val m = v.request
    Json.obj("id" -> text(m.id.render), "dataset" -> text(m.dataset.render), "source" -> text(m.source.render),
      "destination" -> text(m.destination.render), "policy" -> text(m.policy.toString), "keepLink" -> Json.fromBoolean(m.keepLink.nonEmpty),
      "status" -> lower(m.status), "generation" -> Json.fromLong(m.generation.value),
      "approvals" -> Json.obj(m.approvals.toVector.sortBy(_._1.toString).map((r, p) => r.toString -> text(p.render))*),
      "required" -> Json.arr(MoveRole.values.toVector.map(r => text(r.toString))*),
      "yourRoles" -> Json.arr(v.yourRoles.toVector.map(_.toString).sorted.map(text)*),
      "preview" -> Json.obj("lost" -> roles(v.lost), "gained" -> roles(v.gained),
        "survivingStewards" -> Json.arr(v.stewards.map(a => text(a.principal.render))*), "survivingGrants" -> Json.fromInt(v.grants)))
  /** The access-impact preview a steward confirms; its digest binds the confirmation to exactly this content. */
  def impact(i: OrganizationAccessImpact): Json = Json.obj("project" -> text(i.project.render),
    "generation" -> Json.fromLong(i.generation.value), "recipients" -> Json.arr(i.recipients.toVector.sortBy(_._1.render)
      .map((p, role) => Json.obj("principal" -> text(p.render), "role" -> lower(role)))*))
  def impactDigest(i: OrganizationAccessImpact): String =
    Digest.bytes(impact(i).printWith(io.circe.Printer.noSpaces.copy(sortKeys = true)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).value
  /** A steward's grant list: recipients and exact scope/actions, never issuer identities. */
  def grants(gs: Vector[OrganizationAuthority.GrantView]): Json = Json.obj("grants" -> Json.arr(gs.map { v =>
    val g = v.grant
    Json.obj("id" -> text(g.id.render), "recipient" -> text(g.recipient.render), "release" -> opt(g.scope.release)(r => text(r.render)),
      "actions" -> Json.arr(g.actions.toVector.map(_.toString).sorted.map(text)*), "createdAt" -> instant(g.createdAt),
      "expiresAt" -> opt(g.expiresAt)(instant), "effectiveExpiresAt" -> opt(v.effectiveExpiresAt)(instant),
      "basis" -> text(g.basis match { case OrganizationAuthority.GrantBasis.Independent => "independent"; case _ => "derived" }),
      "state" -> text(v.state.toString.toLowerCase))
  }*))
  /** Why access exists: each basis by kind with its own expiry. No issuer, parent-project name or unrelated scope. */
  def explanation(e: OrganizationAuthority.Explanation): Json =
    import OrganizationAuthority.Basis
    Json.obj("dataset" -> text(e.scope.dataset.render), "release" -> opt(e.scope.release)(r => text(r.render)),
      "permission" -> text(e.permission.toString), "allowed" -> Json.fromBoolean(e.allowed),
      "bases" -> Json.arr(e.bases.map {
        case Basis.Stewardship(id, _, expires) => Json.obj("kind" -> text("dataset-stewardship"), "id" -> text(id.render), "expiresAt" -> opt(expires)(instant))
        case Basis.ProjectRole(id, role, _, expires) => Json.obj("kind" -> text("project-role"), "id" -> text(id.render),
          "role" -> text(role.toString.toLowerCase), "expiresAt" -> opt(expires)(instant))
        case Basis.Share(id, scope, _, expires, derived) => Json.obj("kind" -> text("grant"), "id" -> text(id.render),
          "release" -> opt(scope.release)(r => text(r.render)), "derived" -> Json.fromBoolean(derived), "expiresAt" -> opt(expires)(instant))
      }*))
  def grant(g: Grant): Json = Json.obj("id" -> text(g.id.render), "dataset" -> text(g.dataset.render),
    "recipient" -> text(g.recipient.render), "release" -> opt(g.release)(r => text(r.render)),
    "actions" -> Json.arr(g.actions.toVector.map(_.toString).sorted.map(text)*))
