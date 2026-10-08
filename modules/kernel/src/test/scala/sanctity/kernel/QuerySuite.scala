package sanctity.kernel

import munit.FunSuite
import java.util.UUID

final class QuerySuite extends FunSuite:
  private def path(s: String): RelPath = RelPath.from(s).toOption.get

  test("query facts must be bound to an explicit interpreter version") {
    val manifest = Manifest.from(Vector.empty).toOption.get
    val profile = SemanticProfile("test", "library", "interpreter", "fixture")
    val metadata = ArtifactMetadata(path("sub-01/anat/sub-01_T1w.nii"), Map.empty, Vector.empty, None)
    val facts = ArtifactFacts(metadata.path, "sub-01", None, "T1w", "nii", Map("sub" -> "01"))
    assert(Interpretation.from(manifest.id, profile, Vector(metadata), Vector(facts)).isLeft)
  }

  test("bounded numeric semantics preserve censored and missing lexical values") {
    assertEquals(ScientificNumber.parse("89+"), None)
    assertEquals(ScientificNumber.parse("n/a"), None)
    assertEquals(ScientificNumber.parse("89.5"), Some(BigDecimal("89.5")))
  }

  test("large pagination offsets cannot overflow into earlier membership") {
    val ref = ReleaseRef(Id[DatasetTag](UUID.randomUUID()), Id[ReleaseTag](UUID.randomUUID()))
    val hit = SelectionHit(ref, path("sub-01/anat/sub-01_T1w.nii"), "sub-01", None)
    val selection = FrozenSelection.from(Vector(ref), ScientificQuery(), Vector(hit)).toOption.get
    assertEquals(selection.page(Int.MaxValue, 1000), Right(Vector.empty))
  }
