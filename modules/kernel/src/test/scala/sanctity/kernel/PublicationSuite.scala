package sanctity.kernel

import munit.FunSuite
import java.util.UUID

final class PublicationSuite extends FunSuite:
  private def id[A]: Id[A] = Id[A](UUID.randomUUID())
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val blob = BlobRef.from(Digest.bytes(Array[Byte](1)), 1).toOption.get
  private val path = RelPath.from("bold.nii").toOption.get
  private val spec = RunSpec(ReleaseRef(id[DatasetTag], id[ReleaseTag]), blob.digest, id[DatasetTag], None, InputContract("qc", "1"))
  private val owner = AccessContext(id[PrincipalTag])
  private val planned = ProcessingRun(id[RunTag], owner.principal, spec, key("execution"), 0, RunStatus.Planned)
  private val execution = ExecutionEvidence(ProducerIdentity("qc", "1", blob, "runtime"), blob, Vector(path))
  private val result = ProcessingEvidence(planned.id, spec.input, spec.bundle, execution.producer, Map.empty,
    Vector(OutputFile(path, blob)), Vector(path), blob)
  private val completed = planned.advance(RunUpdate(RunStatus.Preparing)).toOption.get
    .advance(RunUpdate(RunStatus.Executing, Some(execution))).toOption.get
    .advance(RunUpdate(RunStatus.Completed, Some(execution), Some(result))).toOption.get
  private val intent = PublicationIntent(completed.id, result.id, owner.principal, id[DatasetTag], None, key("publication"))

  test("publication preserves execution while allowing an explicit new destination and base") {
    assertEquals(intent.validate(owner, completed), Right(result))
    assertEquals(intent.copy(base = Some(id[ReleaseTag])).validate(owner, completed), Right(result))
    assertNotEquals(intent.destination, completed.spec.destination)
    assertEquals(completed.spec, spec)
    assert(completed.submission(owner, intent.destination, None, result).isLeft)
  }

  test("every material publication field participates in identity and retry binding") {
    val changes = Vector(intent.copy(run = id[RunTag]), intent.copy(receipt = Digest.bytes(Array[Byte](2))),
      intent.copy(publisher = id[PrincipalTag]), intent.copy(destination = id[DatasetTag]),
      intent.copy(base = Some(id[ReleaseTag])), intent.copy(key = key("another")))
    changes.foreach { changed =>
      assertNotEquals(intent.id, changed.id)
      assert(intent.retry(changed).isLeft)
    }
    assertEquals(intent.retry(intent.copy()), Right(()))
  }

  test("publication rejects unfinished, interrupted, forged and non-owner runs") {
    assert(intent.validate(owner, planned).isLeft)
    assert(intent.validate(owner, planned.advance(RunUpdate(RunStatus.Failed, detail = Some("failed"))).toOption.get).isLeft)
    assert(intent.validate(owner, planned.advance(RunUpdate(RunStatus.Interrupted, detail = Some("uncertain"))).toOption.get).isLeft)
    assert(intent.validate(AccessContext(id[PrincipalTag]), completed).isLeft)
    assert(intent.copy(receipt = blob.digest).validate(owner, completed).isLeft)
    assert(intent.copy(run = id[RunTag]).validate(owner, completed).isLeft)
    assert(intent.copy(destination = spec.input.dataset).validate(owner, completed).isLeft)
    assert(intent.validate(owner, completed.copy(generation = 0)).isLeft)
    assert(intent.validate(owner, completed.copy(result = Some(result.copy(request = BlobRef.from(blob.digest, 2).toOption.get)))).isLeft)
  }

  test("publication binds destination, expected base, actor and derivative receipt") {
    val activity = Activity(ActivityKind.Derivative, owner.principal, "Publish", None, java.time.Instant.EPOCH,
      processing = Some(result))
    assert(intent.matches(intent.destination, None, activity))
    assert(!intent.matches(spec.destination, None, activity))
    assert(!intent.matches(intent.destination, Some(id[ReleaseTag]), activity))
    assert(!intent.matches(intent.destination, None, activity.copy(actor = id[PrincipalTag])))
    assert(!intent.matches(intent.destination, None, activity.copy(kind = ActivityKind.NativeImport)))
    assert(!intent.matches(intent.destination, None, activity.copy(processing = None)))
  }
