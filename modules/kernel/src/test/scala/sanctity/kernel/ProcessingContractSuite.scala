package sanctity.kernel

import munit.FunSuite
import java.util.UUID

final class ProcessingContractSuite extends FunSuite:
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def id[A]: Id[A] = Id[A](UUID.randomUUID())
  private val blob = BlobRef.from(Digest.bytes(Array[Byte](1, 2)), 2).toOption.get
  private def manifest(path: String, artifact: ArtifactId): Manifest = Manifest.from(Vector(Entry.File(p(path), artifact, blob))).toOption.get

  test("content tree identity separates byte layout from logical artifact allocation") {
    val one = manifest("a.nii", id[ArtifactTag])
    val allocated = manifest("a.nii", id[ArtifactTag])
    val renamed = manifest("b.nii", one.files.head.artifact)
    assertNotEquals(one.id, allocated.id)
    assertEquals(ContentTree.identity(one), ContentTree.identity(allocated))
    assertNotEquals(ContentTree.identity(one), ContentTree.identity(renamed))
  }

  test("input contract identity includes explicit policy parameters without map-order dependence") {
    val one = InputContract("mri", "1", Map("events" -> "required", "anatomy" -> "same-session"))
    assertEquals(one.id, one.copy(parameters = one.parameters.toVector.reverse.toMap).id)
    assertNotEquals(one.id, one.copy(parameters = Map("events" -> "optional", "anatomy" -> "same-session")).id)
    assertNotEquals(one.id, one.copy(version = "2").id)
  }

  test("processing receipts bind software, parameters, inputs and exact output paths") {
    val producer = ProducerIdentity("qc", "1", blob, "python-test")
    val receipt = ProcessingEvidence(id[RunTag], ReleaseRef(id[DatasetTag], id[ReleaseTag]), Digest.bytes(Array[Byte](3)),
      producer, Map.empty, Vector(OutputFile(p("qc.tsv"), blob)), Vector(p("image.nii")), blob)
    assert(receipt.valid)
    assertNotEquals(receipt.id, receipt.copy(request = BlobRef.from(Digest.bytes(Array[Byte](4)), 1).toOption.get).id)
    assertNotEquals(receipt.id, receipt.copy(producer = producer.copy(version = "2")).id)
    assertNotEquals(receipt.id, receipt.copy(parameters = Map("mode" -> "other")).id)
    assertNotEquals(receipt.id, receipt.copy(input = receipt.input.copy(release = id[ReleaseTag])).id)
    assertNotEquals(receipt.id, receipt.copy(expected = Vector(OutputFile(p("other.tsv"), blob))).id)
    assert(!receipt.copy(expected = receipt.expected ++ receipt.expected).valid)
    assert(receipt.matches(manifest("qc.tsv", id[ArtifactTag])))
    assert(!receipt.matches(manifest("other.tsv", id[ArtifactTag])))
  }

  test("released evidence changes assessment identity without changing candidate bytes") {
    val m = manifest("qc.tsv", id[ArtifactTag])
    val i = Interpretation.from(m.id, SemanticProfile("qc", "library", "code", "bounded"), Vector.empty).toOption.get
    val ref = ReleasedArtifact(ReleaseRef(id[DatasetTag], id[ReleaseTag]), i.id,
      Assessment.from(i, "input", Vector.empty).id, p("bold.nii"), blob)
    def assessed(r: ReleasedArtifact): Assessment =
      val check = ContentCheck.from("qc.v1", p("qc.tsv"), Vector(CheckInput("output", p("qc.tsv"), blob)),
        Map("volumes" -> MetadataValue.Number(2)), Vector.empty, Vector(r)).toOption.get
      Assessment.from(i, "qc", Vector.empty, Vector(check))
    assertNotEquals(assessed(ref).id, assessed(ref.copy(at = ref.at.copy(release = id[ReleaseTag]))).id)
    val metric = MetricDefinition("volumes", "1", None)
    val observation = ScientificObservation(ref, metric, 2, p("qc.tsv"), "qc.v1")
    val q = ObservationQuery(Vector(ref), metric, atLeast = Some(BigDecimal(2)), below = Some(BigDecimal(3)))
    assert(q.matches(observation))
    assert(!q.matches(observation.copy(source = ref.copy(at = ref.at.copy(release = id[ReleaseTag])))))
    assert(!q.matches(observation.copy(metric = metric.copy(unit = Some("s")))))
  }

  test("run completion cannot change software, request, input or invocation") {
    val input = ReleaseRef(id[DatasetTag], id[ReleaseTag])
    val spec = RunSpec(input, Digest.bytes(Array[Byte](3)), id[DatasetTag], None, InputContract("qc", "1"))
    val run = ProcessingRun(id[RunTag], id[PrincipalTag], spec, IdempotencyKey.from("run").toOption.get, 0, RunStatus.Planned)
    val execution = ExecutionEvidence(ProducerIdentity("qc", "1", blob, "runtime"), blob, Vector(p("bold.nii")))
    val executing = run.advance(RunUpdate(RunStatus.Preparing)).toOption.get
      .advance(RunUpdate(RunStatus.Executing, Some(execution))).toOption.get
    val receipt = ProcessingEvidence(run.id, input, spec.bundle, execution.producer, Map.empty,
      Vector(OutputFile(p("qc.tsv"), blob)), execution.targets, blob)
    assert(executing.advance(RunUpdate(RunStatus.Completed, Some(execution), Some(receipt))).exists(_.valid))
    assert(executing.advance(RunUpdate(RunStatus.Completed, Some(execution), Some(receipt.copy(invocation = id[RunTag])))).isLeft)
    assert(executing.advance(RunUpdate(RunStatus.Completed, Some(execution), Some(receipt.copy(producer = execution.producer.copy(version = "2"))))).isLeft)
    val interrupted = executing.advance(RunUpdate(RunStatus.Interrupted, Some(execution), detail = Some("uncertain"))).toOption.get
    assert(interrupted.valid)
    assert(interrupted.advance(RunUpdate(RunStatus.Completed, Some(execution), Some(receipt))).isLeft)
  }

  test("observation identity is independent of input order and rejects unbounded decimal scales") {
    val m = manifest("qc.tsv", id[ArtifactTag])
    val profile = SemanticProfile("qc", "lib", "code", "bounded")
    val source = Interpretation.from(m.id, profile, Vector.empty).toOption.get
    val ref = ReleasedArtifact(ReleaseRef(id[DatasetTag], id[ReleaseTag]), source.id,
      Assessment.from(source, "input", Vector.empty).id, p("input.nii"), blob)
    val one = ScientificObservation(ref, MetricDefinition("length", "1", Some("mm")), 10, p("qc.tsv"), "rule")
    val two = one.copy(metric = one.metric.copy(unit = Some("cm")), value = 1)
    def interpret(os: Vector[ScientificObservation]) = Interpretation.from(m.id, profile, Vector.empty, observations = os)
    assertEquals(interpret(Vector(one, two)).toOption.get.id, interpret(Vector(two, one)).toOption.get.id)
    val extreme = BigDecimal(new java.math.BigDecimal(java.math.BigInteger.ONE, Int.MinValue))
    assert(interpret(Vector(one.copy(value = extreme))).isLeft)
    assert(!ObservationQuery(Vector(ref), one.metric, atLeast = Some(extreme)).valid)
  }
