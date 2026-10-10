package sanctity.kernel

import munit.FunSuite
import java.time.Instant
import java.util.UUID

final class ContentCheckSuite extends FunSuite:
  private def right[A](value: Either[Problem, A]): A = value.fold(p => fail(p.message), identity)
  private val path = right(RelPath.from("image.nii"))
  private val blob = right(BlobRef.from(Digest.bytes(Array[Byte](1)), 1))
  private val manifest = right(Manifest.from(Vector(Entry.File(path, Id[ArtifactTag](new UUID(0, 1)), blob))))
  private val interpretation = right(Interpretation.from(manifest.id, SemanticProfile("p", "b", "s", "c"), Vector.empty))
  private val inputs = Vector(CheckInput("image", path, blob), CheckInput("support", path, blob))
  private val facts = Map("volumes" -> MetadataValue.Number(2), "scope" -> MetadataValue.Text("header"))
  private def check(in: Vector[CheckInput] = inputs, fs: Map[String, MetadataValue] = facts,
      errors: Vector[Finding] = Vector.empty): ContentCheck = right(ContentCheck.from("rule-v1", path, in, fs, errors))

  test("check ordering is canonical and facts, rules and exact inputs bind assessment identity") {
    def assessment(c: ContentCheck) = Assessment.from(interpretation, "policy", Vector.empty, Vector(c))
    val first = assessment(check())
    assertEquals(first.id, assessment(check(inputs.reverse, facts.toVector.reverse.toMap)).id)
    assertNotEquals(first.id, assessment(check(fs = facts.updated("volumes", MetadataValue.Number(3)))).id)
    val changed = right(BlobRef.from(Digest.bytes(Array[Byte](2)), 1))
    assertNotEquals(first.id, assessment(check(inputs.map(_.copy(blob = changed)))).id)
    assertNotEquals(first.id, assessment(right(ContentCheck.from("rule-v2", path, inputs, facts, Vector.empty))).id)
  }

  test("content findings participate in admission without a second manual finding copy") {
    val error = Finding("Count", Severity.Error, Some(path), "mismatch")
    val assessment = Assessment.from(interpretation, "policy", Vector.empty, Vector(check(errors = Vector(error))))
    assertEquals(assessment.errors, Vector(error))
    assert(!assessment.admissible)
  }

  test("admission rejects checking evidence bound to different bytes") {
    val draft = Draft(Id[DraftTag](new UUID(0, 2)), Id[DatasetTag](new UUID(0, 3)), None, 0, manifest,
      Activity(ActivityKind.NativeImport, Id[PrincipalTag](new UUID(0, 4)), "test", None, Instant.EPOCH))
    def prepare(c: ContentCheck) = Admission.prepared(draft, 0, false, interpretation,
      Assessment.from(interpretation, "policy", Vector.empty, Vector(c)))
    assertEquals(prepare(check()), Right(()))
    val other = right(BlobRef.from(Digest.bytes(Array[Byte](2)), 1))
    assert(prepare(check(inputs.map(_.copy(blob = other)))).isLeft)
  }

  test("check factories reject duplicate roles, unrelated targets and unrelated finding paths") {
    assert(ContentCheck.from("r", path, inputs :+ inputs.head, facts, Vector.empty).isLeft)
    val other = right(RelPath.from("other.nii"))
    assert(ContentCheck.from("r", other, inputs, facts, Vector.empty).isLeft)
    assert(ContentCheck.from("r", path, inputs, facts, Vector(Finding("e", Severity.Error, Some(other), "wrong input"))).isLeft)
  }

  test("assessments without checks preserve the original canonical v1 formula") {
    val finding = Finding("x", Severity.Warning, Some(path), "historical")
    val tokens = Vector(manifest.id.value.value, interpretation.id.value.value, "policy", path.value, "x", "Warning", "historical")
    assertEquals(Assessment.from(interpretation, "policy", Vector(finding)).id,
      AssessmentId(Digest.framed("sanctity.assessment.v1", tokens)))
  }
