package sanctity.kernel

import munit.FunSuite

final class RelationshipSuite extends FunSuite:
  private def right[A](value: Either[Problem, A]): A = value.fold(p => fail(p.message), identity)
  private def path(v: String): RelPath = right(RelPath.from(v))
  private val a = path("a.nii")
  private val b = path("b.nii")
  private val c = path("c.nii")
  private def metadata(p: RelPath): ArtifactMetadata =
    val doc = path(p.value + ".json")
    ArtifactMetadata(p, Map("Link" -> EffectiveField(MetadataValue.Text("group"), doc,
      Vector(Contribution(doc, MetadataValue.Text("group"))))), Vector(doc), None)
  private val artifacts = Vector(a, b, c).map(metadata)
  private def link(from: RelPath, to: Vector[RelPath]): ScientificRelationship =
    ScientificRelationship(from, "test.related", to, Some("group"),
      Vector(RelationshipEvidence(from, path(from.value + ".json"), "Link")))
  private val content = right(Manifest.from(Vector.empty)).id
  private val profile = SemanticProfile("p", "lib", "resolver", "limited")
  private def interpreted(graph: Option[RelationshipGraph]): Interpretation =
    right(Interpretation.from(content, profile, artifacts, relationships = graph))

  test("relationship identity is canonical and binds groups, mechanisms and resolver version") {
    val links = Vector(link(a, Vector(c, b)), link(b, Vector(b)))
    val first = right(RelationshipGraph.from("v1", links, artifacts))
    val reordered = right(RelationshipGraph.from("v1", links.reverse.map(r => r.copy(to = r.to.reverse)), artifacts))
    assertEquals(interpreted(Some(first)).id, interpreted(Some(reordered)).id)
    val changed = right(RelationshipGraph.from("v2", links, artifacts))
    assertNotEquals(interpreted(Some(first)).id, interpreted(Some(changed)).id)
    assertNotEquals(interpreted(None).id, interpreted(Some(right(RelationshipGraph.from("v1", Vector.empty, artifacts)))).id)
  }
  test("closure terminates on cycles, preserves groups and includes metadata dependencies") {
    val graph = right(RelationshipGraph.from("v1", Vector(link(a, Vector(b, c)), link(b, Vector(a, b))), artifacts))
    val closure = right(interpreted(Some(graph)).relationshipClosure(Vector(a)))
    assertEquals(closure.artifacts, Vector(a, b, c))
    assertEquals(closure.metadataDocuments, artifacts.flatMap(_.sources))
    assertEquals(closure.relationships.size, 2)
    assert(interpreted(None).relationshipClosure(Vector(a)).left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
  }
  test("graphs reject dangling members, absent evidence, duplicates and evidence from another document") {
    val valid = link(a, Vector(b))
    Vector(valid.copy(to = Vector(path("missing.nii"))), valid.copy(to = Vector.empty),
      valid.copy(evidence = Vector.empty), valid.copy(evidence = Vector(RelationshipEvidence(a, path("wrong.json"), "Link"))),
      valid.copy(to = Vector(b, b))).foreach { r =>
      assert(RelationshipGraph.from("v1", Vector(r), artifacts).isLeft)
    }
    assert(RelationshipGraph.from("v1", Vector(valid, valid), artifacts).isLeft)
  }
  test("relationship impact identifies affected targets even when their own metadata is unchanged") {
    val before = interpreted(Some(right(RelationshipGraph.from("v1", Vector(link(a, Vector(b))), artifacts))))
    val after = interpreted(Some(right(RelationshipGraph.from("v1", Vector(link(a, Vector(b, c))), artifacts))))
    assertEquals(Impact.between(before, after), Vector.empty)
    assertEquals(RelationshipImpact.between(before, after).map(_.path), Vector(a))
  }

  test("companion evidence is versioned and validates selection scope and hierarchy") {
    val target = path("sub-01/func/sub-01_task-rest_bold.nii")
    val inherited = path("task-rest_events.tsv")
    val selected = path("sub-01/func/sub-01_task-rest_events.tsv")
    val metas = Vector(target, inherited, selected).map(p => ArtifactMetadata(p, Map.empty, Vector.empty, None))
    val evidence = new RelationshipEvidence.CompanionSelection(target, selected, "events-v1", None,
      Map("task" -> "rest", "sub" -> "01"), Vector(inherited, selected))
    val relation = ScientificRelationship(target, "bids.task-events", Vector(selected), None, Vector(evidence))
    def graph(e: RelationshipEvidence, encoding: Int = 2) = RelationshipGraph.from("companions", Vector(relation.copy(evidence = Vector(e))), metas, encoding)
    val valid = right(graph(evidence))
    assertEquals(valid.evidenceVersion, 2)
    assert(graph(evidence, 1).isLeft)
    assert(graph(evidence.copy(applicable = Vector(selected, inherited))).isLeft)
    assert(graph(evidence.copy(document = inherited)).isLeft)
    assert(graph(evidence.copy(datasetRoot = Some(path("other")))).isLeft)
    assert(graph(evidence.copy(applicable = Vector(path("missing.tsv"), selected))).isLeft)
    assert(RelationshipGraph.from("companions", Vector(relation), metas, 4).isLeft)
    val current = right(Interpretation.from(content, profile, metas, relationships = Some(valid)))
    val closure = right(current.relationshipClosure(Vector(target)))
    assertEquals(closure.artifacts.toSet, Set(target, selected))
    assert(!closure.artifacts.contains(inherited))
    val reordered = evidence.copy(matchedEntities = Map("sub" -> "01", "task" -> "rest"))
    val same = right(Interpretation.from(content, profile, metas.reverse, relationships = Some(right(graph(reordered)))))
    assertEquals(current.id, same.id)
    val altered = right(Interpretation.from(content, profile, metas, relationships = Some(right(graph(evidence.copy(rule = "events-v2"))))))
    assertNotEquals(current.id, altered.id)
  }

  test("direct dependency content changes appear even when the relationship itself is unchanged") {
    val i = interpreted(Some(right(RelationshipGraph.from("v1", Vector(link(a, Vector(b))), artifacts))))
    val impact = RelationshipImpact.between(i, i, Set(b, c))
    assertEquals(impact, Vector(RelationshipDelta(a, i.relationships.get.relationships, i.relationships.get.relationships, Vector(b))))
  }

  test("the recorded pre-companion v3 identity remains byte-for-byte stable") {
    val legacy = interpreted(Some(right(RelationshipGraph.from("v1", Vector(link(a, Vector(b))), artifacts))))
    assertEquals(legacy.id.value.value, "c6386cfcb97deca5363e6425e81806d2cade4f18e5ed7510f26780c0a0e82b1d")
  }

  test("table-column evidence requires its own graph encoding and binds the actual source table") {
    val table = path("events.tsv")
    val asset = path("stimuli/a.txt")
    val metas = Vector(table, asset).map(p => ArtifactMetadata(p, Map.empty, Vector.empty, None))
    val evidence = new RelationshipEvidence.TableColumn(table, table, "stim_file", "stimuli-v1")
    val relation = ScientificRelationship(table, "test.stimuli", Vector(asset), None, Vector(evidence))
    def graph(e: RelationshipEvidence, version: Int) = RelationshipGraph.from("stimuli", Vector(relation.copy(evidence = Vector(e))), metas, version)
    assert(graph(evidence, 2).isLeft)
    assert(graph(evidence.copy(document = asset), 3).isLeft)
    assert(graph(evidence.copy(artifact = asset), 3).isLeft)
    assert(graph(evidence.copy(column = "invalid column"), 3).isLeft)
    val current = right(Interpretation.from(content, profile, metas, relationships = Some(right(graph(evidence, 3)))))
    assertEquals(right(current.relationshipClosure(Vector(table))).artifacts.toSet, Set(table, asset))
    assertEquals(RelationshipImpact.between(current, current, Set(table)).head.changedDependencies, Vector(table))
  }

  test("changes to the exact metadata evidence appear even when membership and origins are unchanged") {
    val i = interpreted(Some(right(RelationshipGraph.from("v1", Vector(link(a, Vector(b))), artifacts))))
    assertEquals(RelationshipImpact.between(i, i, Set(path("a.nii.json"))).head.changedDependencies, Vector(path("a.nii.json")))
  }

  test("reverse dependency scope includes transitive consumers and terminates on cycles") {
    val graph = right(RelationshipGraph.from("v1", Vector(link(a, Vector(b)), link(b, Vector(c)), link(c, Vector(a))), artifacts))
    val at = interpreted(Some(graph))
    val impact = DependencyImpact.between(at, interpreted(None), Set(path("c.nii.json")))
    assertEquals(impact.before, Vector(a, b, c))
    assertEquals(impact.after, Vector(c))
    assertEquals(DependencyImpact.between(at, at, Set(path("unrelated.json"))), DependencyImpact.empty)
  }
