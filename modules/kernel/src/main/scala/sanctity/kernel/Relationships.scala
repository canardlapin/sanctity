package sanctity.kernel

/** A relationship is scientific interpretation, separate from access policy and execution choice.
  * All members of 'to' belong to one declared group; separate records are never silently collapsed.
  */
final case class RelationshipEvidence(artifact: RelPath, document: RelPath, field: String)
final case class ScientificRelationship(
    from: RelPath, kind: String, to: Vector[RelPath], label: Option[String],
    evidence: Vector[RelationshipEvidence]
):
  private[kernel] def canonical: ScientificRelationship =
    copy(to = to.sorted(Ordering.by[RelPath, String](_.value)),
      evidence = evidence.sortBy(e => (e.artifact.value, e.document.value, e.field)))
  private[kernel] def tokens: Vector[String] = Vector(from.value, kind, label.fold("absent")(_ => "present")) ++
    label.toVector ++ Vector(to.size.toString) ++ to.map(_.value) ++ Vector(evidence.size.toString) ++
    evidence.flatMap(e => Vector(e.artifact.value, e.document.value, e.field))

/** Closure under the recorded relationship vocabulary only. This is not a qualified pipeline bundle. */
final case class RelationshipClosure(roots: Vector[RelPath], artifacts: Vector[RelPath],
    metadataDocuments: Vector[RelPath], relationships: Vector[ScientificRelationship])

final class RelationshipGraph private (
    val version: String, val relationships: Vector[ScientificRelationship]
):
  private[kernel] def tokens: Vector[String] =
    Vector(version, relationships.size.toString) ++ relationships.flatMap(_.tokens)

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
      artifacts: Vector[ArtifactMetadata]): Either[Problem, RelationshipGraph] =
    val metadata = artifacts.map(m => m.path -> m).toMap
    val canonical = relationships.map(_.canonical)
    val valid = version.nonEmpty && version.length <= 200 && !version.exists(_.isControl) &&
      canonical.size <= 100000 && canonical.map(_.to.size.toLong).sum <= 100000 && canonical.distinct.size == canonical.size && canonical.forall { r =>
        metadata.contains(r.from) && r.kind.matches("[a-z][a-z0-9.-]{0,99}") && r.to.nonEmpty &&
          r.to.distinct.size == r.to.size && r.to.forall(metadata.contains) && r.label.forall(_.nonEmpty) &&
          r.evidence.nonEmpty && r.evidence.distinct.size == r.evidence.size &&
          r.evidence.forall(e => metadata.get(e.artifact).flatMap(_.fields.get(e.field)).exists(_.origin == e.document))
      }
    Either.cond(valid,
      new RelationshipGraph(version, canonical.sortBy(r => (r.from.value, r.kind, r.label.getOrElse(""), Digest.framed("sanctity.relationship.order.v1", r.tokens).value))),
      Problem.InvalidManifest("invalid relationship graph or missing metadata evidence"))

final case class RelationshipDelta(path: RelPath, before: Vector[ScientificRelationship], after: Vector[ScientificRelationship])

object RelationshipImpact:
  def between(before: Interpretation, after: Interpretation): Vector[RelationshipDelta] =
    val left = before.relationships.toVector.flatMap(_.relationships).groupBy(_.from)
    val right = after.relationships.toVector.flatMap(_.relationships).groupBy(_.from)
    (left.keySet ++ right.keySet).toVector.sortBy(_.value).flatMap { p =>
      val a = left.getOrElse(p, Vector.empty)
      val b = right.getOrElse(p, Vector.empty)
      Option.when(a != b)(RelationshipDelta(p, a, b))
    }
