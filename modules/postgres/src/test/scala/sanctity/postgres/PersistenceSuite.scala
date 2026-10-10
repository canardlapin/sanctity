package sanctity.postgres

import munit.FunSuite
import sanctity.kernel.*
import java.util.UUID

final class PersistenceSuite extends FunSuite:
  import JsonCodecs.given
  private def right[A](value: Either[Problem, A]): A = value.fold(p => fail(p.message), identity)
  private def path(value: String): RelPath = right(RelPath.from(value))
  private val manifest = right(Manifest.from(Vector(
    Entry.Directory(path("sub-01")),
    Entry.File(path("sub-01/a.nii"), Id[ArtifactTag](UUID.randomUUID()), right(BlobRef.from(Digest.bytes(Array[Byte](1, 2)), 2)))
  )))
  private val profile = SemanticProfile("pinned", "library-source-digest", "interpreter-source-digest", "limited")
  private val fields = Map("Value" -> EffectiveField(
    MetadataValue.Record(Map("precise" -> MetadataValue.Number(BigDecimal("9007199254740993.0001")), "missing" -> MetadataValue.Null)),
    path("metadata.json"), Vector(Contribution(path("metadata.json"), MetadataValue.Text("evidence")))
  ))
  private val interpretation = right(Interpretation.from(manifest.id, profile,
    Vector(ArtifactMetadata(path("sub-01/a.nii"), fields, Vector(path("metadata.json")), None))))

  test("manifest round trip revalidates identity, directories and blob references") {
    val decoded = right(JsonCodecs.decode[Manifest](JsonCodecs.encode(manifest)))
    assertEquals(decoded.id, manifest.id)
    assertEquals(decoded.entries, manifest.entries)
  }
  test("interpretation round trip retains exact values, origins, history and profile") {
    val decoded = right(JsonCodecs.decode[Interpretation](JsonCodecs.encode(interpretation)))
    assertEquals(decoded.id, interpretation.id)
    assertEquals(decoded.artifacts, interpretation.artifacts)
    assertEquals(decoded.profile, profile)
  }
  test("tampered identity and unsupported envelope versions are rejected") {
    val encoded = JsonCodecs.encode(manifest)
    assert(JsonCodecs.decode[Manifest](encoded.replace("sub-01/a.nii", "sub-01/b.nii")).isLeft)
    assert(JsonCodecs.decode[Manifest](encoded.replace("\"version\":1", "\"version\":2")).isLeft)
    assert(JsonCodecs.decode[Manifest](encoded.replace("\"size\":2", "\"size\":-1")).isLeft)
  }
  test("assessment is reconstructed only with its exact interpretation") {
    val assessment = Assessment.from(interpretation, "policy-v1", Vector(Finding("case", Severity.Warning, None, "evidence")))
    val restored = right(JsonCodecs.assessment(JsonCodecs.encode(assessment), interpretation))
    assertEquals(restored.id, assessment.id)
    assertEquals(restored.findings, assessment.findings)
    val other = right(Interpretation.from(manifest.id, profile.copy(interpreterIdentity = "updated"), interpretation.artifacts))
    assert(JsonCodecs.assessment(JsonCodecs.encode(assessment), other).isLeft)
  }
  test("database configuration validates identifier boundaries and redacts credentials") {
    val config = right(DatabaseConfig.from("jdbc:postgresql://localhost/test", "test", "do-not-print", "safe_schema"))
    assert(!config.toString.contains("do-not-print"))
    Vector("public", "pg_catalog", "x; DROP SCHEMA public", "mixedCase", "").foreach { schema =>
      assert(DatabaseConfig.from("jdbc:postgresql://localhost/test", "test", "", schema).isLeft)
    }
    assert(DatabaseConfig.environment(Map.empty).isLeft)
  }

  test("historical interpretation envelopes without relationship fields preserve their original identities") {
    val queried = right(Interpretation.from(manifest.id, profile, interpretation.artifacts,
      Vector(ArtifactFacts(path("sub-01/a.nii"), "sub-01", None, "T1w", "nii", Map("sub" -> "01"))),
      queryVersion = Some("scientific-query-v1")))
    Vector(interpretation, queried).foreach { original =>
      val encoded = JsonCodecs.encode(original)
      val json = io.circe.jawn.parse(encoded).toOption.get
      val legacy = json.mapObject(_.mapValues { value =>
        value.asObject.fold(value)(fields => io.circe.Json.fromJsonObject(fields.remove("relationships")))
      })
      val restored = right(JsonCodecs.decode[Interpretation](legacy.noSpaces))
      assertEquals(restored.id, original.id)
      assertEquals(restored.relationships, None)
      assert(restored.relationshipClosure(Vector(path("sub-01/a.nii"))).isLeft)
    }
  }

  test("relationship round trips bind evidence and reject tampered groups or resolver versions") {
    val artifact = path("sub-01/a.nii")
    val relation = ScientificRelationship(artifact, "test.self", Vector(artifact), Some("group"),
      Vector(RelationshipEvidence(artifact, path("metadata.json"), "Value")))
    val graph = right(RelationshipGraph.from("test-relations-v1", Vector(relation), interpretation.artifacts))
    val current = right(Interpretation.from(manifest.id, profile, interpretation.artifacts, relationships = Some(graph)))
    val encoded = JsonCodecs.encode(current)
    val decoded = right(JsonCodecs.decode[Interpretation](encoded))
    assertEquals(decoded.id, current.id)
    assertEquals(decoded.relationships.get.relationships, Vector(relation))
    assert(JsonCodecs.decode[Interpretation](encoded.replace("test-relations-v1", "test-relations-v2")).isLeft)
    assert(JsonCodecs.decode[Interpretation](encoded.replace("\"group\"", "\"different\"")).isLeft)
  }

  test("typed companion evidence round trips and rejects tampered selection rules and hierarchy") {
    val target = path("sub-01/a.nii")
    val root = path("events.tsv")
    val local = path("sub-01/events.tsv")
    val artifacts = interpretation.artifacts ++ Vector(root, local).map(p => ArtifactMetadata(p, Map.empty, Vector.empty, None))
    val evidence = RelationshipEvidence.CompanionSelection(target, local, "events-selection-v1", None,
      Map("sub" -> "01"), Vector(root, local))
    val relation = ScientificRelationship(target, "bids.task-events", Vector(local), None, Vector(evidence))
    val graph = right(RelationshipGraph.from("companions-v1", Vector(relation), artifacts, 2))
    val current = right(Interpretation.from(manifest.id, profile, artifacts, relationships = Some(graph)))
    val encoded = JsonCodecs.encode(current)
    val decoded = right(JsonCodecs.decode[Interpretation](encoded))
    assertEquals(decoded.id, current.id)
    assertEquals(decoded.relationships.get.evidenceVersion, 2)
    assertEquals(decoded.relationships.get.relationships, Vector(relation))
    assert(JsonCodecs.decode[Interpretation](encoded.replace("events-selection-v1", "events-selection-v2")).isLeft)
    assert(JsonCodecs.decode[Interpretation](encoded.replace("\"evidenceVersion\":2", "\"evidenceVersion\":1")).isLeft)
    assert(JsonCodecs.decode[Interpretation](encoded.replace("\"evidenceVersion\":2", "\"evidenceVersion\":3")).isLeft)
  }

  test("legacy field-only graph envelopes remain v3 when evidenceVersion is absent") {
    val target = path("sub-01/a.nii")
    val relation = ScientificRelationship(target, "test.self", Vector(target), None,
      Vector(RelationshipEvidence(target, path("metadata.json"), "Value")))
    val graph = right(RelationshipGraph.from("legacy-v1", Vector(relation), interpretation.artifacts))
    val legacy = right(Interpretation.from(manifest.id, profile, interpretation.artifacts, relationships = Some(graph)))
    val encoded = JsonCodecs.encode(legacy).replace("\"evidenceVersion\":1,", "")
    assert(!encoded.contains("evidenceVersion"))
    val decoded = right(JsonCodecs.decode[Interpretation](encoded))
    assertEquals(decoded.id, legacy.id)
    assertEquals(decoded.relationships.get.evidenceVersion, 1)
    val modern = right(RelationshipGraph.from("legacy-v1", Vector(relation), interpretation.artifacts, 2))
    assertNotEquals(right(Interpretation.from(manifest.id, profile, interpretation.artifacts, relationships = Some(modern))).id, legacy.id)
  }

  test("content checks round trip exact inputs and observations and reject tampered evidence") {
    val file = manifest.files.head
    val check = right(ContentCheck.from("dwi-test-v1", file.path, Vector(CheckInput("image", file.path, file.blob)),
      Map("volumeCount" -> MetadataValue.Number(2)), Vector(Finding("Mismatch", Severity.Error, Some(file.path), "wrong count"))))
    val assessment = Assessment.from(interpretation, "policy", Vector.empty, Vector(check))
    val encoded = JsonCodecs.encode(assessment)
    val restored = right(JsonCodecs.assessment(encoded, interpretation))
    assertEquals(restored.id, assessment.id)
    assertEquals(restored.checks.head.inputs, check.inputs)
    assertEquals(restored.checks.head.facts, check.facts)
    assertEquals(restored.errors, check.findings)
    assert(JsonCodecs.assessment(encoded.replace("dwi-test-v1", "dwi-test-v2"), interpretation).isLeft)
    assert(JsonCodecs.assessment(encoded.replace("\"role\":\"image\"", "\"role\":\"other\""), interpretation).isLeft)
  }

  test("historical assessments without a checks property retain their original identity") {
    val original = Assessment.from(interpretation, "historical", Vector(Finding("Old", Severity.Warning, None, "old finding")))
    val json = io.circe.jawn.parse(JsonCodecs.encode(original)).toOption.get
    val legacy = json.mapObject(_.mapValues(value =>
      value.asObject.fold(value)(fields => io.circe.Json.fromJsonObject(fields.remove("checks")))))
    assert(!legacy.noSpaces.contains("checks"))
    val restored = right(JsonCodecs.assessment(legacy.noSpaces, interpretation))
    assertEquals(restored.id, original.id)
    assertEquals(restored.findings, original.findings)
    assertEquals(restored.checks, Vector.empty)
  }
