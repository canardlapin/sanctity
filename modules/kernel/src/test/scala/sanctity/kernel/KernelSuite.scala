package sanctity.kernel

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import java.util.UUID

final class KernelSuite extends ScalaCheckSuite:
  private def right[A](value: Either[Problem, A]): A = value.fold(p => fail(p.message), identity)
  private def path(value: String): RelPath = right(RelPath.from(value))
  private def artifact(value: Long): ArtifactId = Id[ArtifactTag](new UUID(0, value))
  private val blob = right(BlobRef.from(Digest.bytes(Array[Byte](1, 2, 3)), 3L))
  private val entries = Vector(
    Entry.Directory(path("sub-01")),
    Entry.File(path("sub-01/a.nii"), artifact(1), blob),
    Entry.File(path("README"), artifact(2), blob)
  )

  property("manifest identity is independent of inventory order") {
    Prop.forAll(Gen.pick(entries.size, entries)) { shuffled =>
      right(Manifest.from(shuffled.toVector)).id == right(Manifest.from(entries)).id
    }
  }

  test("relative paths reject traversal, absolute paths, alternate separators, and silent normalization") {
    Vector("", "/tmp/file", "../a", "a/../b", "a//b", "./a", "a\\b", "C:a", "a/", " file", "file ", "a /b", "a/ b").foreach { value =>
      assert(RelPath.from(value).isLeft, value)
    }
    assertEquals(path("sub-01/func/file.nii").value, "sub-01/func/file.nii")
  }

  test("manifest rejects collisions, duplicate identity, and missing directories") {
    assert(Manifest.from(entries :+ Entry.File(path("readme"), artifact(3), blob)).isLeft)
    assert(Manifest.from(entries :+ Entry.File(path("other.nii"), artifact(1), blob)).isLeft)
    assert(Manifest.from(Vector(Entry.File(path("missing/file.nii"), artifact(1), blob))).isLeft)
    assert(Manifest.from(Vector(Entry.File(path("é"), artifact(1), blob), Entry.File(path("e\u0301"), artifact(2), blob))).isLeft)
  }

  test("rename preserves artifact and bytes while changing manifest identity") {
    val before = right(Manifest.from(entries))
    val after = right(before.change(Vector(Change.Move(path("sub-01/a.nii"), path("sub-01/b.nii")))))
    assertNotEquals(before.id, after.id)
    assertEquals(after.byPath(path("sub-01/b.nii")), Entry.File(path("sub-01/b.nii"), artifact(1), blob))
    assertEquals(after.diff(before).size, 2)
  }

  test("replacing a path cannot silently replace artifact identity") {
    val before = right(Manifest.from(entries))
    assert(before.change(Vector(Change.Put(path("README"), artifact(3), blob))).isLeft)
    assert(before.change(Vector(Change.Move(path("README"), path("sub-01/a.nii")))).isLeft)
    assert(BlobRef.from(blob.digest, -1).isLeft)
  }

  test("equal bytes retain independent artifact identities") {
    assertEquals(right(Manifest.from(entries)).files.map(_.blob).distinct.size, 1)
    assertEquals(right(Manifest.from(entries)).files.map(_.artifact).distinct.size, 2)
  }

  test("interpretation identity binds software profile and assessment identity binds evidence") {
    val manifest = right(Manifest.from(entries))
    val profile = SemanticProfile("reference", "library-a", "resolver-a", "limited")
    val first = right(Interpretation.from(manifest.id, profile, Vector.empty))
    val next = right(Interpretation.from(manifest.id, profile.copy(interpreterIdentity = "resolver-b"), Vector.empty))
    assertNotEquals(first.id, next.id)
    assertEquals(first.content, next.content)
    assertNotEquals(Assessment.from(first, "policy", Vector.empty).id, Assessment.from(next, "policy", Vector.empty).id)
    assertNotEquals(Assessment.from(first, "policy", Vector.empty).id,
      Assessment.from(first, "policy", Vector(Finding("x", Severity.Warning, None, "new evidence"))).id)
  }

  test("impact includes newly applicable metadata and distinguishes unchanged local overrides") {
    val manifest = right(Manifest.from(entries))
    val profile = SemanticProfile("reference", "library", "resolver", "limited")
    val shared = path("task-rest_bold.json")
    val local = path("sub-01/local.json")
    val image = path("sub-01/a.nii")
    def interpretation(global: Int): Interpretation =
      val field = EffectiveField(MetadataValue.Number(2), local,
        Vector(Contribution(shared, MetadataValue.Number(global)), Contribution(local, MetadataValue.Number(2))))
      right(Interpretation.from(manifest.id, profile, Vector(ArtifactMetadata(image, Map("TR" -> field), Vector(shared, local), None))))
    val impact = Impact.between(interpretation(1), interpretation(3))
    assertEquals(impact.size, 1)
    assert(!impact.head.fields.head.valueChanged)
    val empty = right(Interpretation.from(manifest.id, profile, Vector.empty))
    assert(Impact.between(empty, interpretation(1)).head.fields.head.valueChanged)
  }

  test("identifier tags reject accidental substitution at compile time") {
    assert(compileErrors("""
      import sanctity.kernel.*
      val dataset: DatasetId = Id[DatasetTag](java.util.UUID.randomUUID())
      val artifact: ArtifactId = dataset
    """).nonEmpty)
  }
