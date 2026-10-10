package sanctity.kernel

/** Evidence distinguishes authored declarations from versioned companion-selection rules. */
enum RelationshipEvidence:
  case MetadataField(artifact: RelPath, document: RelPath, field: String)
  case CompanionSelection(artifact: RelPath, document: RelPath, rule: String,
      datasetRoot: Option[RelPath], matchedEntities: Map[String, String], applicable: Vector[RelPath])

  private[kernel] def tokens: Vector[String] = this match
    case MetadataField(a, d, f) => Vector(a.value, d.value, f)
    case CompanionSelection(a, d, rule, root, entities, applicable) =>
      Vector(a.value, d.value, rule, root.fold("")(_.value), entities.size.toString) ++
        entities.toVector.sortBy(_._1).flatMap((k, v) => Vector(k, v)) ++
        Vector(applicable.size.toString) ++ applicable.map(_.value)

  private[kernel] def taggedTokens: Vector[String] = Vector(productPrefix, tokens.size.toString) ++ tokens

  private[kernel] def orderKey: (String, String, String) = this match
    case MetadataField(a, d, f) => (a.value, d.value, f)
    case CompanionSelection(a, d, _, _, _, _) => (a.value, d.value, Digest.framed("sanctity.companion.order.v1", tokens).value)

object RelationshipEvidence:
  /** Source-compatible constructor for the original metadata-field evidence. */
  def apply(artifact: RelPath, document: RelPath, field: String): RelationshipEvidence =
    MetadataField(artifact, document, field)

/** A relationship is scientific interpretation, separate from access policy and execution choice.
  * All members of 'to' belong to one declared group; separate records are never silently collapsed.
  */
final case class ScientificRelationship(
    from: RelPath, kind: String, to: Vector[RelPath], label: Option[String],
    evidence: Vector[RelationshipEvidence]
):
  private[kernel] def canonical: ScientificRelationship =
    copy(to = to.sorted(Ordering.by[RelPath, String](_.value)),
      evidence = evidence.sortBy(e => (e.orderKey, e.productPrefix)))
  private[kernel] def tokens(evidenceVersion: Int): Vector[String] = Vector(from.value, kind, label.fold("absent")(_ => "present")) ++
    label.toVector ++ Vector(to.size.toString) ++ to.map(_.value) ++ Vector(evidence.size.toString) ++
    evidence.flatMap(e => if evidenceVersion == 1 then e.tokens else e.taggedTokens)

/** Closure under the recorded relationship vocabulary only. This is not a qualified pipeline bundle. */
final case class RelationshipClosure(roots: Vector[RelPath], artifacts: Vector[RelPath],
    metadataDocuments: Vector[RelPath], relationships: Vector[ScientificRelationship])

final class RelationshipGraph private (
    val version: String, val relationships: Vector[ScientificRelationship], val evidenceVersion: Int
):
  private[kernel] def tokens: Vector[String] =
    Vector(version, relationships.size.toString) ++ relationships.flatMap(_.tokens(evidenceVersion))

  private[kernel] def closure(roots: Vector[RelPath], metadata: Map[RelPath, ArtifactMetadata]): Either[Problem, RelationshipClosure] =
    if roots.isEmpty || roots.exists(p => !metadata.contains(p)) then Left(Problem.InvalidManifest("closure requires known artifact roots"))
    else
      val outgoing = relationships.groupBy(_.from)
      var pending = roots.distinct.toList
      var visited = Set.empty[RelPath]
      while pending.nonEmpty do
        val current = pending.head
        pending = pending.tail
        if !visited(current) then
          visited += current
          pending = outgoing.getOrElse(current, Vector.empty).flatMap(_.to).filterNot(visited).toList ::: pending
      val selected = relationships.filter(r => visited(r.from))
      val documents = visited.toVector.flatMap(p => metadata(p).sources).distinct.sortBy(_.value)
      Right(RelationshipClosure(roots.distinct.sortBy(_.value), visited.toVector.sortBy(_.value), documents, selected))

object RelationshipGraph:
  def from(version: String, relationships: Vector[ScientificRelationship],
      artifacts: Vector[ArtifactMetadata], evidenceVersion: Int = 1): Either[Problem, RelationshipGraph] =
    val metadata = artifacts.map(m => m.path -> m).toMap
    val canonical = relationships.map(_.canonical)
    def evidenceValid(r: ScientificRelationship, e: RelationshipEvidence): Boolean = e match
      case RelationshipEvidence.MetadataField(a, d, f) =>
        metadata.get(a).flatMap(_.fields.get(f)).exists(_.origin == d)
      case RelationshipEvidence.CompanionSelection(a, d, rule, root, entities, applicable) =>
        val parent = a.value.split('/').dropRight(1).mkString("/")
        evidenceVersion == 2 && a == r.from && r.to.contains(d) &&
          rule.nonEmpty && rule.length <= 200 && !rule.exists(_.isControl) &&
          metadata.get(a).exists(_.datasetRoot == root) &&
          applicable.nonEmpty && applicable.size <= 1024 && applicable.last == d &&
          applicable.distinct.size == applicable.size &&
          applicable.forall { p =>
            val directory = p.value.split('/').dropRight(1).mkString("/")
            metadata.get(p).exists(_.datasetRoot == root) &&
              (directory.isEmpty || parent == directory || parent.startsWith(directory + "/"))
          } &&
          applicable.map(_.value.count(_ == '/')).sliding(2).forall(v => v.size < 2 || v(0) < v(1)) &&
          entities.size <= 100 && entities.forall((k, v) => k.nonEmpty && k.length <= 100 && v.nonEmpty && v.length <= 4096)
    val valid = Set(1, 2)(evidenceVersion) && version.nonEmpty && version.length <= 200 && !version.exists(_.isControl) &&
      canonical.size <= 100000 && canonical.map(_.to.size.toLong).sum <= 100000 && canonical.distinct.size == canonical.size && canonical.forall { r =>
        metadata.contains(r.from) && r.kind.matches("[a-z][a-z0-9.-]{0,99}") && r.to.nonEmpty &&
          r.to.distinct.size == r.to.size && r.to.forall(metadata.contains) && r.label.forall(_.nonEmpty) &&
          r.evidence.nonEmpty && r.evidence.distinct.size == r.evidence.size &&
          r.evidence.forall(evidenceValid(r, _)) &&
          (evidenceVersion == 2 || r.evidence.forall(_.isInstanceOf[RelationshipEvidence.MetadataField]))
      }
    Either.cond(valid,
      new RelationshipGraph(version, canonical.sortBy(r => (r.from.value, r.kind, r.label.getOrElse(""), Digest.framed("sanctity.relationship.order.v1", r.tokens(evidenceVersion)).value)), evidenceVersion),
      Problem.InvalidManifest("invalid relationship graph or missing metadata evidence"))

final case class RelationshipDelta(path: RelPath, before: Vector[ScientificRelationship], after: Vector[ScientificRelationship], changedDependencies: Vector[RelPath] = Vector.empty)

object RelationshipImpact:
  def between(before: Interpretation, after: Interpretation, changedPaths: Set[RelPath] = Set.empty): Vector[RelationshipDelta] =
    val left = before.relationships.toVector.flatMap(_.relationships).groupBy(_.from)
    val right = after.relationships.toVector.flatMap(_.relationships).groupBy(_.from)
    (left.keySet ++ right.keySet).toVector.sortBy(_.value).flatMap { p =>
      val a = left.getOrElse(p, Vector.empty)
      val b = right.getOrElse(p, Vector.empty)
      val changed = (a ++ b).flatMap(_.to).filter(changedPaths).distinct.sortBy(_.value)
      Option.when(a != b || changed.nonEmpty)(RelationshipDelta(p, a, b, changed))
    }
