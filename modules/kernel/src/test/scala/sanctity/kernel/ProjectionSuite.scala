package sanctity.kernel

import munit.FunSuite
import java.time.Instant
import java.util.UUID

final class ProjectionSuite extends FunSuite:
  private def right[A](e: Either[Problem, A]): A = e.fold(p => fail(p.message), identity)
  private def path(s: String): RelPath = right(RelPath.from(s))
  private val blob = right(BlobRef.from(Digest.bytes(Array[Byte](1)), 1))
  private val file: Entry.File = Entry.File(path("a.nii"), Id[ArtifactTag](new UUID(0, 1)), blob)
  private val source = right(Manifest.from(Vector(file)))
  private val interpretation = right(Interpretation.from(source.id, SemanticProfile("p", "b", "s", "c"), Vector.empty))
  private val spec = Digest.bytes(Array[Byte](9))
  private val step = ProjectionStep(file.path, "copy", Vector(CheckInput("content", file.path, blob)))

  test("receipt binds exact sources, interpretation, output and transformation with canonical ordering") {
    val first = right(ProjectionReceipt.from(source, interpretation.id, source, Vector(step), spec))
    assert(first.matches(source))
    val transformed = right(ProjectionReceipt.from(source, interpretation.id, source, Vector(step.copy(operation = "project")), spec))
    assertNotEquals(first.id, transformed.id)
    val second = right(Interpretation.from(source.id, interpretation.profile.copy(name = "new"), Vector.empty))
    assertNotEquals(first.id, right(ProjectionReceipt.from(source, second.id, source, Vector(step), spec)).id)
    val withInputs = step.copy(operation = "project", inputs = step.inputs :+ CheckInput("metadata", file.path, blob))
    assertEquals(right(ProjectionReceipt.from(source, interpretation.id, source, Vector(withInputs), spec)).id,
      right(ProjectionReceipt.from(source, interpretation.id, source, Vector(withInputs.copy(inputs = withInputs.inputs.reverse)), spec)).id)
  }
  test("receipt rejects incomplete mappings, duplicate outputs, incorrect source bytes and false copy claims") {
    val other = right(BlobRef.from(Digest.bytes(Array[Byte](2)), 1))
    assert(ProjectionReceipt.from(source, interpretation.id, source, Vector.empty, spec).isLeft)
    assert(ProjectionReceipt.from(source, interpretation.id, source, Vector(step, step), spec).isLeft)
    assert(ProjectionReceipt.from(source, interpretation.id, source, Vector(step.copy(inputs = Vector(CheckInput("content", file.path, other)))), spec).isLeft)
    val changed = right(Manifest.from(Vector(Entry.File(file.path, file.artifact, other))))
    assert(ProjectionReceipt.from(source, interpretation.id, changed, Vector(step), spec).isLeft)
    assert(!right(ProjectionReceipt.from(source, interpretation.id, source, Vector(step), spec)).matches(changed))
  }
  test("admission rejects a projection receipt for a different output") {
    val ref = ReleaseRef(Id[DatasetTag](new UUID(0, 2)), Id[ReleaseTag](new UUID(0, 3)))
    val selection = right(FrozenSelection.from(Vector(ref), ScientificQuery(), Vector.empty))
    val initial = ProjectionEvidence(ref, selection.id, Digest.bytes(Array.emptyByteArray), "compiler", Vector.empty)
    val receipt = right(ProjectionReceipt.from(source, interpretation.id, source, Vector(step), initial.binding))
    val evidence = initial.copy(receipt = Some(receipt))
    assert(evidence.matches(source))
    assert(!evidence.copy(compiler = "changed").matches(source))
    val changed = right(Manifest.from(Vector(file.copy(path = path("b.nii")))))
    val meaning = right(Interpretation.from(changed.id, interpretation.profile, Vector.empty))
    val activity = Activity(ActivityKind.ScientificView, Id[PrincipalTag](new UUID(0, 4)), "test", None, Instant.EPOCH, Some(evidence))
    val draft = Draft(Id[DraftTag](new UUID(0, 5)), ref.dataset, None, 0, changed, activity)
    assert(Admission.prepared(draft, 0, false, meaning, Assessment.from(meaning, "p", Vector.empty)).isLeft)
  }
  test("legacy disclosure identity remains stable while each new table mask binds identity") {
    val old = DisclosureProfile(Set("age"), Set("TaskName"))
    assertEquals(old.id, Digest.framed("sanctity.mri-view.v1", Vector("1", "age", "1", "TaskName")))
    Vector(old.copy(eventFields = Some(Set.empty)), old.copy(sessionFields = Set("age")), old.copy(scanFields = Set("acq_time")),
      old.copy(behaviouralFields = Some(Set.empty)), old.copy(behaviouralFields = Some(Set("response_time"))))
      .foreach(p => assertNotEquals(p.id, old.id))
    assertNotEquals(old.copy(behaviouralFields = Some(Set("response_time"))).id,
      old.copy(eventFields = Some(Set("response_time"))).id)
  }
