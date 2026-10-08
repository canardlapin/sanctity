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
