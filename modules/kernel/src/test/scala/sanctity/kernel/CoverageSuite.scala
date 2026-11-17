package sanctity.kernel

import munit.FunSuite
import java.time.Instant
import java.util.UUID

final class CoverageSuite extends FunSuite:
  private def right[A](value: Either[Problem, A]): A = value.fold(p => fail(p.message), identity)
  private def path(s: String): RelPath = right(RelPath.from(s))
  private def file(s: String, n: Int): Entry.File =
    Entry.File(path(s), Id[ArtifactTag](new UUID(0, n.toLong)), right(BlobRef.from(Digest.bytes(Array[Byte](n.toByte)), 1)))
  private val image = file("image.nii", 1)
  private val notes = file("notes.txt", 2)
  private val manifest = right(Manifest.from(Vector(image, notes)))
  private val profile = SemanticProfile("p", "b", "s", "c")
  private val artifacts = Vector(ArtifactMetadata(image.path, Map.empty, Vector.empty, None))
  private val facts = Vector(ArtifactFacts(image.path, "sub-01", None, "T1w", "nii", Map("sub" -> "01")))
  private def levels(image: CoverageLevel = CoverageLevel.Assessed, group: String = "anat"): CoverageLevels =
    right(CoverageLevels.from(Vector(FileLevel(notes.path, "other", CoverageLevel.Preserved), FileLevel(this.image.path, group, image))))
  private def interpretation(l: Option[CoverageLevels]): Interpretation =
    right(Interpretation.from(manifest.id, profile, artifacts, facts, queryVersion = Some("scientific-query-v1"), levels = l))

  test("an interpretation without levels keeps its original identity") {
    // Golden values computed by the kernel at commit 3709923, before coverage levels existed.
    // Every historical release depends on this encoding staying fixed.
    assertEquals(manifest.id.value.value, "acd8db2f6a05d6bbe7422b595aab1e5d81252be9fb447d030bf0a91103b22248")
    assertEquals(interpretation(None).id.value.value, "40b32bc145a6ca1583cda176a113c7f2a97bb5c1a19638977b804b65726a40fa")
    assertEquals(interpretation(None).levels, None)
  }

  test("levels are canonical and bind the interpretation identity") {
    val base = interpretation(Some(levels()))
    assertNotEquals(base.id, interpretation(None).id)
    val reordered = right(CoverageLevels.from(Vector(FileLevel(image.path, "anat", CoverageLevel.Assessed), FileLevel(notes.path, "other", CoverageLevel.Preserved))))
    assertEquals(base.id, interpretation(Some(reordered)).id)
    assertNotEquals(base.id, interpretation(Some(levels(CoverageLevel.Described))).id)
    assertNotEquals(base.id, interpretation(Some(levels(group = "func"))).id)
    val renamed = right(CoverageLevels.from(levels().files, "sanctity-coverage-levels-v2"))
    assertNotEquals(base.id, interpretation(Some(renamed)).id)
    assertEquals(base.levels.map(_.summary), Some(Vector(("anat", CoverageLevel.Assessed, 1), ("other", CoverageLevel.Preserved, 1))))
  }

  test("an indexed file cannot be recorded as merely preserved, or left without a level") {
    assert(Interpretation.from(manifest.id, profile, artifacts, facts, queryVersion = Some("scientific-query-v1"),
      levels = Some(levels(CoverageLevel.Preserved))).isLeft)
    val missing = right(CoverageLevels.from(Vector(FileLevel(notes.path, "other", CoverageLevel.Preserved))))
    assert(Interpretation.from(manifest.id, profile, artifacts, facts, queryVersion = Some("scientific-query-v1"), levels = Some(missing)).isLeft)
  }

  test("levels reject duplicate paths and malformed groups") {
    assert(CoverageLevels.from(Vector(FileLevel(image.path, "anat", CoverageLevel.Assessed), FileLevel(image.path, "anat", CoverageLevel.Described))).isLeft)
    assert(CoverageLevels.from(Vector(FileLevel(image.path, "Anat folder", CoverageLevel.Assessed))).isLeft)
    assert(CoverageLevels.from(Vector(FileLevel(image.path, "anat", CoverageLevel.Assessed)), "Version 1").isLeft)
  }

  test("admission requires exactly one level for every file of the manifest") {
    val actor = Id[PrincipalTag](new UUID(0, 9))
    val dataset = Dataset(Id[DatasetTag](new UUID(0, 7)), Id[CustodianTag](new UUID(0, 8)), actor, None)
    val activity = Activity(ActivityKind.NativeImport, actor, "import", None, Instant.EPOCH)
    val draft = Draft(Id[DraftTag](new UUID(0, 5)), dataset.id, None, 0, manifest, activity)
    def prepared(l: CoverageLevels) =
      val i = interpretation(Some(l))
      Admission.prepared(draft, 0, accepted = false, i, Assessment.from(i, "policy", Vector.empty))
    assert(prepared(levels()).isRight)
    val partial = right(CoverageLevels.from(Vector(FileLevel(image.path, "anat", CoverageLevel.Assessed))))
    assert(prepared(partial).isLeft)
    val foreign = right(CoverageLevels.from(levels().files.filterNot(_.path == notes.path) :+ FileLevel(path("elsewhere.txt"), "other", CoverageLevel.Preserved)))
    assert(prepared(foreign).isLeft)
  }
