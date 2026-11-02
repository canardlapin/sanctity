package sanctity.app

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

abstract class CatalogProcessingSuite extends CatalogGeneratedQuerySuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val mask = DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime"))
  private val query = ScientificQuery(artifacts = ArtifactPredicate.All(Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))
  private def inputs(s: RepositorySystem) = new ProcessingInputs[IO](s.catalog, s.repository,
    new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, IdSource.random[IO]), new MriInputPlanner)
  private def derivatives(s: RepositorySystem) = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), IdSource.random[IO])
  private def submissions(s: RepositorySystem) = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives(s), IdSource.random[IO])
  private def producer(s: RepositorySystem) = new MriQcProducer[IO](s.catalog, s.store, s.repository, IdSource.random[IO], Path.of("/usr/bin/python3"))
  private def accepted(s: RepositorySystem, root: Path): IO[Release] =
    for
      preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("processing-source")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def bundle(s: RepositorySystem, release: Release): IO[Release] =
    for
      selection <- checked(s.catalog.select(s.context, Vector(release.ref), query))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      preview <- checked(inputs(s).prepare(s.context, release.ref, selection.id, destination.id, mask, MriInputPlanner.Contract))
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("processing-bundle")))
      result <- checked(s.catalog.release(s.context, receipt.release))
    yield result
  private def collect(s: RepositorySystem, ctx: AccessContext, draft: Draft, receipt: ProcessingEvidence): IO[Preview] =
    receipt.expected.traverse_(f => checked(submissions(s).collectStaged(ctx, draft.id, f.path))) *>
      checked(submissions(s).prepare(ctx, draft.id))

  test("worker heartbeat fails a revoked run and restoration does not restart it") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        principal <- IdSource.random[IO].next[PrincipalTag]
        owner = AccessContext(principal)
        grant <- checked(s.catalog.grant(s.context, input.ref.dataset, principal, Some(input.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        _ <- checked(s.catalog.grant(s.context, destination.id, principal, None, Set(Action.ReadMetadata, Action.Propose)))
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        run <- checked(s.catalog.startRun(owner, spec, key("heartbeat-parity")))
        _ <- checked(s.catalog.advanceRun(owner, run.id, 0, RunUpdate(RunStatus.Preparing)))
        _ <- checked(s.catalog.heartbeatRun(owner, run.id))
        _ <- checked(s.catalog.revoke(s.context, input.ref.dataset, grant.id))
        refused <- s.catalog.heartbeatRun(owner, run.id)
        unknown <- IdSource.random[IO].next[RunTag].flatMap(s.catalog.heartbeatRun(owner, _))
        _ = assertEquals(refused, Left(Problem.Denied))
        _ = assertEquals(unknown, refused)
        _ <- checked(s.catalog.grant(s.context, input.ref.dataset, principal, Some(input.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        failed <- checked(s.catalog.readRun(owner, run.id))
        _ = assertEquals(failed.status, RunStatus.Failed)
        _ = assert(failed.result.isEmpty)
        retry <- s.catalog.heartbeatRun(owner, run.id)
        _ = assert(retry.isLeft)
      yield ()
    }
  }

  test("accepted content can be unready for a pinned processing contract") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(source.ref), query))
        ready <- checked(inputs(s).readiness(s.context, source.ref, selection.id, MriInputPlanner.Contract))
        _ = assert(ready.ready, ready.requirements.toString)
        correction <- checked(s.repository.correctMetadata(s.context, source.ref, p(SyntheticFixture.sharedSidecar),
          SyntheticFixture.correctedJson, "Test readiness mismatch"))
        _ = assert(correction.candidate.assessment.admissible)
        receipt <- checked(s.repository.accept(s.context, correction.candidate.id, key("input-mismatch")))
        changed <- checked(s.catalog.select(s.context, Vector(receipt.release), query))
        report <- checked(inputs(s).readiness(s.context, receipt.release, changed.id, MriInputPlanner.Contract))
        _ = assert(!report.ready)
        _ = assert(report.requirements.exists(r => r.code == "header-timing" && r.status == RequirementStatus.Disallowed))
        old <- checked(inputs(s).readiness(s.context, source.ref, selection.id, MriInputPlanner.Contract))
        _ = assertEquals(old.id, ready.id)
        unknown <- checked(inputs(s).readiness(s.context, source.ref, selection.id, InputContract("unknown", "1")))
        _ = assert(!unknown.ready)
        required <- checked(inputs(s).readiness(s.context, source.ref, selection.id,
          MriInputPlanner.Contract.copy(parameters = Map("events" -> "required", "fieldmaps" -> "required"))))
        _ = assertEquals(required.requirements.filter(_.status == RequirementStatus.Missing).map(_.code).toSet, Set("events", "fieldmaps"))
      yield ()
    }
  }

  test("anatomy requirements correlate participant and session and report ambiguity") {
    fixture { (s, root) =>
      val names = Vector("sub-02/anat/sub-02_T1w.nii", "sub-01/ses-A/anat/sub-01_ses-A_T1w.nii",
        "sub-01/anat/sub-01_T1w.nii", "sub-01/anat/sub-01_acq-second_T1w.nii")
      for
        _ <- IO.blocking {
          val bytes = Files.readAllBytes(root.resolve("source").resolve(SyntheticFixture.firstImage))
          ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(48, 1.toShort)
          names.foreach { name =>
            val target = root.resolve("source").resolve(name)
            Files.createDirectories(target.getParent)
            val _ = Files.write(target, bytes)
          }
        }
        source <- accepted(s, root)
        _ = {
          val contract = MriInputPlanner.Contract.copy(parameters = Map("anatomy" -> "same-session"))
          def report(ns: Vector[String]) = new MriInputPlanner().assess(source.manifest, source.interpretation, source.assessment,
            (SyntheticFixture.firstImage +: ns).map(p), contract).toOption.get
          val wrong = report(names.take(2))
          assert(wrong.requirements.exists(r => r.code == "same-session-anatomy" && r.status == RequirementStatus.Missing))
          assert(report(Vector(names(2))).ready)
          assert(report(names.drop(2)).requirements.exists(r => r.code == "same-session-anatomy" && r.status == RequirementStatus.Ambiguous))
        }
      yield ()
    }
  }

  test("bundle content identity is deterministic and pinned across source correction and export") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        first <- bundle(s, source)
        second <- bundle(s, source)
        _ = assertEquals(first.activity.bundle.get.tree, second.activity.bundle.get.tree)
        _ = assertNotEquals(first.manifest.id, second.manifest.id)
        _ = assert(first.activity.bundle.get.readiness.ready)
        selection <- checked(s.catalog.select(s.context, Vector(source.ref), query))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        refused <- inputs(s).prepare(s.context, source.ref, selection.id, destination.id,
          mask.copy(metadataFields = Set("RepetitionTime")), MriInputPlanner.Contract)
        _ = assert(refused.isLeft)
        correction <- checked(s.repository.correctMetadata(s.context, source.ref, p(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson, "Later source change"))
        _ <- checked(s.repository.accept(s.context, correction.candidate.id, key("after-bundle")))
        unchanged <- checked(inputs(s).bundle(s.context, first.ref))
        _ = assertEquals(unchanged.activity.bundle, first.activity.bundle)
        _ <- checked(inputs(s).materialize(s.context, first.ref, new DirectoryExport[IO](root.resolve("bundle-export"))))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        imported <- checked(s.repository.importNative(s.context, destination.id, new NativeDirectorySource[IO](root.resolve("bundle-export"))))
        _ = assertEquals(imported.candidate.interpretation.artifacts, first.interpretation.artifacts)
        _ = assertEquals(ContentTree.identity(imported.candidate.manifest), first.activity.bundle.get.tree)
        _ = assertEquals(imported.candidate.activity.bundle, None)
        // A BIDS directory preserves scientific bytes; repository bundle authority is a separate record.
      yield ()
    }
  }

  test("external producer returns verifiable QC and interrupted collection resumes without rerunning") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        receipt <- checked(producer(s).produce(s.context, input.ref, root.resolve("producer")))
        qc <- s.store.read(receipt.expected.find(_.path == p("qc.tsv")).get.blob).through(fs2.text.utf8.decode).compile.string
        _ = assertEquals(qc, QcDerivativeFormat.Columns.mkString("\t") + "\n" + SyntheticFixture.firstImage + "\t1\t1\t1\t2\t1.5\t1.5\n")
        destination <- s.repository.create(s.context, s.dataset.custodian)
        draft <- checked(submissions(s).beginStaged(s.context, destination.id, receipt))
        first <- checked(submissions(s).collectStaged(s.context, draft.id, receipt.expected.head.path))
        failure <- submissions(s).collect(s.context, draft.id, receipt.expected(1).path,
          Stream.raiseError[IO](new java.io.IOException("Interrupted transport")))
        _ = assert(failure.isLeft)
        recovered <- checked(s.catalog.readDraft(s.context, draft.id))
        _ = assertEquals(recovered.manifest.id, first.manifest.id)
        pending <- submissions(s).prepare(s.context, draft.id)
        _ = assert(pending.isLeft)
        head <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(head.head, None)
        _ <- TestFiles.remove(root.resolve("producer"))
        preview <- collect(s, s.context, draft, receipt)
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.findings.toString)
        accepted <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("qc-return")))
        retry <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("qc-return")))
        _ = assertEquals(retry, accepted)
        release <- checked(s.catalog.release(s.context, accepted.release))
        _ = assertEquals(release.activity.processing.map(_.id), Some(receipt.id))
        _ = assert(release.activity.retained.contains(receipt.request))
        _ = assert(release.assessment.checks.exists(_.rule == "processing-receipt-v1"))
        _ <- checked(derivatives(s).exportFull(s.context, accepted.release, new DirectoryExport[IO](root.resolve("derivative-export"))))
        importedDestination <- s.repository.create(s.context, s.dataset.custodian)
        imported <- checked(derivatives(s).importNative(s.context, importedDestination.id, new NativeDirectorySource[IO](root.resolve("derivative-export"))))
        _ = assert(imported.candidate.assessment.admissible)
        _ = assertEquals(ContentTree.identity(imported.candidate.manifest), ContentTree.identity(release.manifest))
        _ = assertEquals(imported.candidate.activity.processing, None)
        original <- checked(s.catalog.release(s.context, source.ref))
        _ = assertEquals(original.manifest.id, source.manifest.id)
      yield ()
    }
  }

  test("source read and separate destination authority suffice, and revocation blocks acceptance") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        collaborator <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        grant <- checked(s.catalog.grant(s.context, input.ref.dataset, collaborator.principal, Some(input.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        destination <- s.repository.create(collaborator, s.dataset.custodian)
        receipt <- checked(producer(s).produce(collaborator, input.ref, root.resolve("collaborator-run")))
        editDenied <- s.catalog.check(collaborator, input.ref.dataset, Action.Propose)
        _ = assertEquals(editDenied, Left(Problem.Denied))
        draft <- checked(submissions(s).beginStaged(collaborator, destination.id, receipt))
        preview <- collect(s, collaborator, draft, receipt)
        _ <- checked(s.catalog.revoke(s.context, input.ref.dataset, grant.id))
        denied <- s.catalog.accept(collaborator, preview.candidate.id, key("revoked-run"))
        _ = assertEquals(denied, Left(Problem.Denied))
        deniedExport <- inputs(s).materialize(collaborator, input.ref, new DirectoryExport[IO](root.resolve("revoked-export")))
        _ = assertEquals(deniedExport, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, input.ref.dataset, collaborator.principal, Some(input.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        accepted <- checked(derivatives(s).accept(collaborator, preview.candidate.id, key("revoked-run")))
        release <- checked(s.catalog.release(collaborator, accepted.release))
        _ = assertEquals(release.activity.processing.get.input, input.ref)
        _ = assertEquals(release.ref.dataset, destination.id)
      yield ()
    }
  }

  test("wrong output bytes and forged input bundle cannot pass ordinary admission") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        receipt <- checked(producer(s).produce(s.context, input.ref, root.resolve("invalid-run")))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        forged <- submissions(s).beginStaged(s.context, destination.id, receipt.copy(bundle = Digest.bytes(Array[Byte](1))))
        _ = assert(forged.isLeft)
        draft <- checked(submissions(s).beginStaged(s.context, destination.id, receipt))
        invalid <- submissions(s).collect(s.context, draft.id, p("qc.tsv"), Stream.emits("wrong".getBytes).covary[IO])
        _ = assert(invalid.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
        extra <- submissions(s).collect(s.context, draft.id, p("extra.tsv"), Stream.empty)
        _ = assert(extra.isLeft)
        preview <- collect(s, s.context, draft, receipt)
        candidate = preview.candidate
        wrongActivity = candidate.activity.copy(processing = Some(receipt.copy(bundle = Digest.bytes(Array[Byte](2)))))
        changed <- s.catalog.revise(s.context, draft.id, candidate.generation, candidate.manifest, wrongActivity)
        _ = assert(changed.isLeft)
        _ <- checked(s.catalog.revise(s.context, draft.id, candidate.generation, candidate.manifest, candidate.activity.copy(reason = "Updated collection note")))
        stale <- derivatives(s).accept(s.context, candidate.id, key("stale-qc"))
        _ = assert(stale.isLeft)
      yield ()
    }
  }

  test("external QC independently reads licensed compressed NIfTI-1 and NIfTI-2 images") {
    fixture { (s, root) =>
      val files = Vector("example4d.nii.gz", "example_nifti2.nii.gz")
      val paths = Vector(1, 2).map(i => s"sub-01/func/sub-01_task-rest_run-${i}_bold.nii.gz")
      for
        _ <- IO.blocking {
          Files.delete(root.resolve("source").resolve(SyntheticFixture.firstImage))
          files.zip(paths).foreach((f, p) => Files.write(root.resolve("source").resolve(p), MriFixtures.bytes(f)))
          val _ = Files.writeString(root.resolve("source").resolve(SyntheticFixture.sharedSidecar),
            """{"TaskName":"rest","RepetitionTime":2000}""")
        }
        source <- accepted(s, root)
        input <- bundle(s, source)
        receipt <- checked(producer(s).produce(s.context, input.ref, root.resolve("real-qc")))
        qc <- s.store.read(receipt.expected.find(_.path == p("qc.tsv")).get.blob).through(fs2.text.utf8.decode).compile.string
        _ = {
          val oracle = MriFixtures.json("header-oracle.json").hcursor.downField("images")
          val expected = files.zip(paths).map { (f, path) =>
            val dimensions = oracle.downField(f).get[Vector[Long]]("dimensions").toOption.get
            path + "\t" + dimensions.mkString("\t") + "\t2000\t2000"
          }
          assertEquals(qc, (Vector(QcDerivativeFormat.Columns.mkString("\t")) ++ expected).mkString("", "\n", "\n"))
        }
      yield ()
    }
  }

  test("a deposition worker cannot accept results or read unrelated source data") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        worker <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        _ <- checked(s.catalog.grant(s.context, input.ref.dataset, worker.principal, Some(input.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        _ <- checked(s.catalog.grant(s.context, destination.id, worker.principal, None, Set(Action.ReadMetadata, Action.Propose)))
        receipt <- checked(producer(s).produce(worker, input.ref, root.resolve("worker-qc")))
        draft <- checked(submissions(s).beginStaged(worker, destination.id, receipt))
        preview <- collect(s, worker, draft, receipt)
        denied <- derivatives(s).accept(worker, preview.candidate.id, key("worker-denied"))
        _ = assertEquals(denied, Left(Problem.Denied))
        unrelated <- s.repository.readFile(worker, source.ref, p(SyntheticFixture.secondImage))
        _ = assertEquals(unrelated, Left(Problem.Denied))
        accepted <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("curator-accepted")))
        _ = assertEquals(accepted.release.dataset, destination.id)
      yield ()
    }
  }

  test("derivative assessment refuses valid-looking rows with false source provenance") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        receipt <- checked(producer(s).produce(s.context, input.ref, root.resolve("false-provenance")))
        declared = receipt.expected.find(_.path == p("dataset_description.json")).get
        text <- s.store.read(declared.blob).through(fs2.text.utf8.decode).compile.string
        wrong <- s.store.put(Stream.emits(text.replace(input.activity.bundle.get.id.value, Digest.bytes(Array[Byte](99)).value).getBytes).covary[IO])
        tampered = receipt.copy(expected = receipt.expected.map(f => if f.path == declared.path then f.copy(blob = wrong) else f))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        draft <- checked(submissions(s).beginStaged(s.context, destination.id, tampered))
        _ <- tampered.expected.traverse_(f => checked(submissions(s).collectStaged(s.context, draft.id, f.path)))
        refused <- submissions(s).prepare(s.context, draft.id)
        _ = assert(refused.toOption.exists(_.candidate.assessment.errors.exists(_.code == "QcReceipt")))
        denied <- derivatives(s).accept(s.context, refused.toOption.get.candidate.id, key("false-receipt"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(head.head, None)
      yield ()
    }
  }

  test("declared remote outputs remain distinct from verified retained staging") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        produced <- checked(producer(s).produce(s.context, input.ref, root.resolve("remote-receipt")))
        missing = BlobRef.from(Digest.bytes("not-yet-delivered".getBytes), 17).toOption.get
        receipt = produced.copy(expected = Vector(OutputFile(p("qc.tsv"), missing)))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        draft <- checked(submissions(s).begin(s.context, destination.id, receipt))
        _ = assertEquals(draft.activity.retained.toSet, Set(receipt.producer.code, receipt.request))
        _ = assert(!draft.activity.retained.contains(missing))
        staged <- submissions(s).beginStaged(s.context, destination.id, receipt)
        _ = assert(staged.isLeft)
        refused <- submissions(s).prepare(s.context, draft.id)
        _ = assert(refused.isLeft)
      yield ()
    }
  }

  test("self-consistent output inventories cannot validate false scientific values") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        receipt <- checked(producer(s).produce(s.context, input.ref, root.resolve("false-values")))
        output = receipt.expected.find(_.path == p("qc.tsv")).get
        text <- s.store.read(output.blob).through(fs2.text.utf8.decode).compile.string
        changed <- s.store.put(Stream.emits(text.replace("\t1\t1\t1\t2\t", "\t9\t1\t1\t2\t").getBytes).covary[IO])
        tampered = receipt.copy(expected = receipt.expected.map(f => if f.path == output.path then f.copy(blob = changed) else f))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        draft <- checked(submissions(s).beginStaged(s.context, destination.id, tampered))
        preview <- collect(s, s.context, draft, tampered)
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "QcInputMismatch"))
        result <- derivatives(s).accept(s.context, preview.candidate.id, key("false-values"))
        _ = assert(result.isLeft)
        head <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(head.head, None)
      yield ()
    }
  }

  test("observations correlate exact input releases and retain historical evidence") {
    fixture { (s, root) =>
      def image(r: Release): ReleasedArtifact =
        val f = r.manifest.files.find(_.path == p(SyntheticFixture.firstImage)).get
        ReleasedArtifact(r.ref, r.interpretation.id, r.assessment.id, f.path, f.blob)
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        other <- bundle(s, source)
        receipt <- checked(producer(s).produce(s.context, input.ref, root.resolve("observations")))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        draft <- checked(submissions(s).beginStaged(s.context, destination.id, receipt))
        preview <- collect(s, s.context, draft, receipt)
        _ = assertEquals(preview.candidate.interpretation.observations.size, 6)
        checks = preview.candidate.assessment.checks.filter(_.rule == QcDerivativeInterpreter.ValueRule)
        _ = assert(checks.exists(_.external.contains(image(input))))
        published <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("observations")))
        metric = MetricDefinition("volumes", "1", None)
        q = ObservationQuery(Vector(image(input), image(other)), metric, atLeast = Some(BigDecimal(2)))
        results <- checked(new ScientificResults[IO](s.catalog).coverage(s.context, Vector(published.release), q))
        _ = assertEquals(results.map(_.results.size), Vector(1, 0))
        wrongUnit <- checked(s.catalog.observations(s.context, Vector(published.release), q.copy(metric = metric.copy(unit = Some("s")))))
        _ = assert(wrongUnit.isEmpty)
        threshold <- checked(s.catalog.observations(s.context, Vector(published.release), q.copy(below = Some(BigDecimal(2)))))
        _ = assert(threshold.isEmpty)
        correction <- checked(s.repository.correctMetadata(s.context, source.ref, p(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson, "Later source correction"))
        _ <- checked(s.repository.accept(s.context, correction.candidate.id, key("observation-source-change")))
        historical <- checked(s.catalog.release(s.context, published.release))
        _ = assertEquals(historical.assessment.id, preview.candidate.assessment.id)
        again <- checked(new ScientificResults[IO](s.catalog).coverage(s.context, Vector(published.release), q))
        _ = assertEquals(again, results)
      yield ()
    }
  }

  test("seeded exact-input observation oracle preserves metric bounds, release identity, coverage and authority") {
    fixture { (s, root) =>
      val seed = 417L
      val volumes = Vector(2, 3, 5)
      def image(r: Release): ReleasedArtifact =
        val f = r.manifest.files.find(_.path == p(SyntheticFixture.firstImage)).get
        ReleasedArtifact(r.ref, r.interpretation.id, r.assessment.id, f.path, f.blob)
      val measurements = Vector("dim-x" -> BigDecimal(1), "dim-y" -> BigDecimal(1), "dim-z" -> BigDecimal(1),
        "repetition-time" -> BigDecimal("1.5"), "header-spacing" -> BigDecimal("1.5"))
      def expectedMetrics(n: Int): Vector[(MetricDefinition, BigDecimal)] =
        (measurements :+ ("volumes" -> BigDecimal(n))).map { (name, value) =>
          MetricDefinition(name, "1", Option.when(Set("repetition-time", "header-spacing")(name))("s")) -> value
        }
      for
        inputsByCount <- volumes.zipWithIndex.traverse { (n, index) =>
          val directory = root.resolve(s"observation-source-$index")
          for
            _ <- SyntheticFixture.create(directory)
            _ <- IO.blocking {
              val bytes = DwiTestFiles.nifti(volumes = n)
              val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
              header.putFloat(92, 1.5f)
              header.put(123, 10.toByte)
              val _ = Files.write(directory.resolve(SyntheticFixture.firstImage), bytes)
            }
            target <- s.repository.create(s.context, s.dataset.custodian)
            imported <- checked(s.repository.importNative(s.context, target.id, new NativeDirectorySource[IO](directory)))
            accepted <- checked(s.repository.accept(s.context, imported.candidate.id, key(s"observation-source-$index")))
            raw <- checked(s.catalog.release(s.context, accepted.release))
            input <- bundle(s, raw)
          yield raw -> input
        }
        equalBytes <- bundle(s, inputsByCount.head._1)
        inputReleases = inputsByCount.map(_._2) :+ equalBytes
        qualified = inputReleases.map(image)
        _ = assertEquals(qualified.head.blob, qualified.last.blob)
        _ = assertNotEquals(qualified.head.at, qualified.last.at)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        accepted <- Vector(0, 1, 0, 3).zipWithIndex.foldLeftM(Vector.empty[(ReleaseRef, Int)]) { (history, item) =>
          val (inputIndex, iteration) = item
          for
            receipt <- checked(producer(s).produce(s.context, inputReleases(inputIndex).ref, root.resolve(s"observation-run-$iteration")))
            draft <- history.lastOption match
              case None => checked(submissions(s).beginStaged(s.context, destination.id, receipt))
              case Some((base, _)) => checked(submissions(s).beginStagedAt(s.context, base, receipt))
            preview <- collect(s, s.context, draft, receipt)
            _ <- if iteration != 0 then IO.unit else
              val i = preview.candidate.interpretation
              val forged = Interpretation.from(i.content, i.profile, i.artifacts, i.facts, i.participants, i.queryVersion,
                i.relationships, i.declaredBidsVersion, i.observations.map(_.copy(source = qualified.last))).toOption.get
              val a = preview.candidate.assessment
              s.catalog.prepared(s.context, draft.id, preview.candidate.generation, forged,
                Assessment.from(forged, a.policy, a.findings, a.checks)).map(r => assert(r.isLeft, "unbound source must not enter an observation index"))
            published <- checked(derivatives(s).accept(s.context, preview.candidate.id, key(s"observation-release-$iteration")))
          yield history :+ (published.release -> inputIndex)
        }
        facts = accepted.flatMap { (at, inputIndex) =>
          expectedMetrics(if inputIndex == 3 then 2 else volumes(inputIndex)).map { (metric, value) =>
            ObservationHit(at, ScientificObservation(qualified(inputIndex), metric, value, p("qc.tsv"), QcDerivativeInterpreter.ValueRule))
          }
        }
        reader <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        inputGrants <- inputReleases.traverse(r => checked(s.catalog.grant(s.context, r.ref.dataset, reader.principal, Some(r.ref.release), Set(Action.ReadMetadata))))
        derivativeGrants <- accepted.traverse((ref, _) => checked(s.catalog.grant(s.context, ref.dataset, reader.principal, Some(ref.release), Set(Action.ReadMetadata))))
        queries = {
          val random = new scala.util.Random(seed)
          val metrics = expectedMetrics(2).map(_._1)
          val ordinary = metrics.flatMap(m => Vector(m, m.copy(version = "2"), m.copy(unit = Some("ms")), m.copy(unit = None))).distinct
          ordinary.flatMap { metric =>
            Vector(ObservationQuery(random.shuffle(qualified), metric),
              ObservationQuery(random.shuffle(qualified), metric, atLeast = Some(BigDecimal("1.5")), below = Some(BigDecimal(3))),
              ObservationQuery(random.shuffle(qualified), metric, atLeast = Some(BigDecimal(2)), below = Some(BigDecimal(2))))
          }
        }
        _ <- queries.zipWithIndex.traverse_ { (q, index) =>
          val universe = accepted.map(_._1).reverse
          val expected = facts.filter(h => q.inputs.contains(h.observation.source) && h.observation.metric == q.metric &&
            q.atLeast.forall(bound => h.observation.value >= bound) && q.below.forall(bound => h.observation.value < bound))
            .sortBy(h => (h.at.dataset.render, h.at.release.render, h.observation.source.path.value))
          for
            actual <- checked(s.catalog.observations(reader, universe, q))
            _ = assertEquals(actual, expected, s"seed=$seed query=$index $q")
            coverage <- checked(new ScientificResults[IO](s.catalog).coverage(reader, universe, q))
            _ = assertEquals(coverage.map(_.input), q.inputs)
            _ = coverage.foreach(c => assertEquals(c.results, expected.filter(_.observation.source == c.input), s"seed=$seed query=$index"))
            _ = assert(coverage.find(_.input == qualified(2)).get.results.isEmpty)
          yield ()
        }
        q = ObservationQuery(qualified, MetricDefinition("volumes", "1", None))
        invalid <- s.catalog.observations(reader, accepted.map(_._1), q.copy(inputs = Vector(
          qualified.head, qualified.head.copy(assessment = qualified.last.assessment))))
        _ = assert(invalid.isLeft)
        _ <- checked(s.catalog.revoke(s.context, destination.id, derivativeGrants.head.id))
        deniedDerivative <- s.catalog.observations(reader, accepted.map(_._1), q)
        _ = assertEquals(deniedDerivative, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, destination.id, reader.principal, Some(accepted.head._1.release), Set(Action.ReadMetadata)))
        _ <- checked(s.catalog.revoke(s.context, inputReleases(2).ref.dataset, inputGrants(2).id))
        deniedSource <- new ScientificResults[IO](s.catalog).coverage(reader, accepted.map(_._1), q)
        _ = assertEquals(deniedSource, Left(Problem.Denied))
      yield ()
    }
  }

  test("private processing needs read authority and cannot acquire independent sharing authority") {
    fixture { (s, root) =>
      val ids = IdSource.random[IO]
      for
        source <- accepted(s, root)
        reader <- ids.next[PrincipalTag].map(AccessContext(_))
        outsider <- ids.next[PrincipalTag].map(AccessContext(_))
        grant <- checked(s.catalog.grant(s.context, source.ref.dataset, reader.principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        selection <- checked(s.catalog.select(reader, Vector(source.ref), query))
        destination <- s.repository.create(reader, s.dataset.custodian)
        _ <- checked(s.catalog.grant(reader, destination.id, outsider.principal, None, Set(Action.ReadMetadata, Action.ReadContent, Action.Propose, Action.Accept)))
        shared <- inputs(s).prepare(reader, source.ref, selection.id, destination.id, mask, MriInputPlanner.Contract)
        _ = assert(shared.isLeft)
        preview <- checked(inputs(s).preparePrivate(reader, source.ref, selection.id, destination.id, mask, MriInputPlanner.Contract))
        hiddenDraft <- s.catalog.readDraft(outsider, preview.candidate.draft)
        hiddenCandidate <- s.catalog.candidate(outsider, preview.candidate.id)
        _ = assert(hiddenDraft.isLeft && hiddenCandidate.isLeft)
        privateDraft <- checked(s.catalog.readDraft(reader, preview.candidate.draft))
        stripped <- s.catalog.revise(reader, privateDraft.id, privateDraft.generation, privateDraft.manifest,
          privateDraft.activity.copy(kind = ActivityKind.NativeImport, projection = None, bundle = None))
        _ = assert(stripped.isLeft)
        accepted <- checked(s.repository.accept(reader, preview.candidate.id, key("private")))
        repeat <- checked(s.repository.accept(reader, preview.candidate.id, key("private")))
        _ = assertEquals(repeat, accepted)
        _ <- checked(inputs(s).materialize(reader, accepted.release, new DirectoryExport[IO](root.resolve("private-input"))))
        leaked <- s.catalog.release(outsider, accepted.release)
        sharing <- s.catalog.grant(reader, destination.id, outsider.principal, Some(accepted.release.release), Set(Action.ReadContent))
        correction <- s.repository.correctMetadata(reader, accepted.release, p("dataset_description.json"), "{}", "Cannot declassify")
        _ = assert(leaked.isLeft && sharing.isLeft && correction.isLeft)
        _ <- checked(s.catalog.revoke(s.context, source.ref.dataset, grant.id))
        revoked <- inputs(s).materialize(reader, accepted.release, new DirectoryExport[IO](root.resolve("revoked-private")))
        metadata <- s.catalog.release(reader, accepted.release)
        _ = assert(revoked.isLeft && metadata.isLeft)
      yield ()
    }
  }

  test("local runs and collection are idempotent and support successive derivative releases") {
    fixture { (s, root) =>
      val runner = new LocalQcRuns[IO](s.catalog, s.store, producer(s))
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        first <- checked(runner.execute(s.context, spec, key("run-1"), root.resolve("run-1")))
        record <- checked(s.catalog.readRun(s.context, first.invocation))
        _ = assertEquals(record.status, RunStatus.Completed)
        _ = assertEquals(record.execution.map(_.request), Some(first.request))
        _ <- TestFiles.remove(root.resolve("run-1"))
        retry <- checked(new LocalQcRuns[IO](s.catalog, s.store, producer(s)).execute(s.context, spec, key("run-1"), root.resolve("must-not-execute")))
        _ = assertEquals(retry.id, first.id)
        _ <- IO.blocking(assert(!Files.exists(root.resolve("must-not-execute"))))
        unrelated <- s.repository.create(s.context, s.dataset.custodian)
        wrongDestination <- submissions(s).beginStaged(s.context, unrelated.id, first)
        _ = assert(wrongDestination.isLeft)
        draft <- checked(submissions(s).beginStaged(s.context, destination.id, first))
        repeated <- checked(submissions(s).beginStaged(s.context, destination.id, first))
        _ = assertEquals(repeated.id, draft.id)
        preview <- collect(s, s.context, draft, first)
        a <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("run-1-accept")))
        afterAccept <- checked(submissions(s).beginStaged(s.context, destination.id, first))
        _ = assertEquals(afterAccept.id, draft.id)
        second <- checked(runner.execute(s.context, spec.copy(base = Some(a.release.release)), key("run-2"), root.resolve("run-2")))
        next <- checked(submissions(s).beginStagedAt(s.context, a.release, second))
        nextPreview <- collect(s, s.context, next, second)
        competing <- checked(producer(s).produce(s.context, input.ref, root.resolve("competing")))
        stale <- checked(submissions(s).beginStagedAt(s.context, a.release, competing))
        stalePreview <- collect(s, s.context, stale, competing)
        b <- checked(derivatives(s).accept(s.context, nextPreview.candidate.id, key("run-2-accept")))
        refused <- derivatives(s).accept(s.context, stalePreview.candidate.id, key("stale-run"))
        _ = assert(refused.isLeft)
        old <- checked(s.catalog.release(s.context, a.release))
        latest <- checked(s.catalog.release(s.context, b.release))
        _ = assertEquals(latest.parent, Some(a.release.release))
        _ = assertEquals(old.manifest.files.map(f => f.path -> f.artifact), latest.manifest.files.map(f => f.path -> f.artifact))
        _ = assertEquals(old.activity.processing.map(_.id), Some(first.id))
        _ <- checked(derivatives(s).exportFull(s.context, a.release, new DirectoryExport[IO](root.resolve("old-derivative"))))
        _ <- checked(derivatives(s).exportFull(s.context, b.release, new DirectoryExport[IO](root.resolve("new-derivative"))))
      yield ()
    }
  }

  test("explicit publication reuses completed bytes after a head conflict and recovers retries") {
    fixture { (s, root) =>
      val runner = new LocalQcRuns[IO](s.catalog, s.store, producer(s))
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        result <- checked(runner.execute(s.context, spec, key("publish-run"), root.resolve("publish-run")))
        before <- checked(s.catalog.readRun(s.context, result.invocation))
        // A competing producer advances the destination after this execution completed.
        competing <- checked(producer(s).produce(s.context, input.ref, root.resolve("competitor")))
        firstDraft <- checked(submissions(s).beginStaged(s.context, destination.id, competing))
        first <- collect(s, s.context, firstDraft, competing)
        head <- checked(derivatives(s).accept(s.context, first.candidate.id, key("competing-head")))
        stale <- submissions(s).beginStaged(s.context, destination.id, result)
        _ = assert(stale.isLeft)
        _ <- TestFiles.remove(root.resolve("publish-run"))
        intent = PublicationIntent(result.invocation, result.id, s.context.principal, destination.id, Some(head.release.release), key("publish-reviewed-base"))
        pair <- (checked(submissions(s).publish(s.context, intent)), checked(submissions(s).publish(s.context, intent))).parTupled
        _ = assertEquals(pair._1.id, pair._2.id)
        draft = pair._1
        _ = assertEquals(draft.activity.publication, Some(intent))
        stripped <- s.catalog.revise(s.context, draft.id, draft.generation, draft.manifest, draft.activity.copy(publication = None))
        _ = assert(stripped.isLeft)
        other <- s.repository.create(s.context, s.dataset.custodian)
        changed <- submissions(s).publish(s.context, intent.copy(destination = other.id, base = None))
        _ = assert(changed.isLeft)
        _ <- checked(submissions(s).collectStaged(s.context, draft.id, result.expected.head.path))
        resumed <- checked(submissions(s).publish(s.context, intent))
        _ = assertEquals(resumed.manifest.files.size, 1)
        preview <- collect(s, s.context, resumed, result)
        published <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("published")))
        repeated <- checked(derivatives(s).accept(s.context, preview.candidate.id, key("published")))
        _ = assertEquals(repeated, published)
        retry <- checked(submissions(s).publish(s.context, intent))
        _ = assertEquals(retry.id, draft.id)
        release <- checked(s.catalog.release(s.context, published.release))
        prior <- checked(s.catalog.release(s.context, head.release))
        _ = assertEquals(release.parent, Some(head.release.release))
        _ = assertEquals(release.manifest.files.map(f => f.path -> f.blob).toMap, result.expected.map(f => f.path -> f.blob).toMap)
        _ = assertEquals(release.manifest.files.map(f => f.path -> f.artifact), prior.manifest.files.map(f => f.path -> f.artifact))
        secondIntent = intent.copy(destination = other.id, base = None, key = key("publish-elsewhere"))
        elsewhere <- checked(submissions(s).publish(s.context, secondIntent))
        second <- collect(s, s.context, elsewhere, result)
        _ <- checked(derivatives(s).accept(s.context, second.candidate.id, key("published-elsewhere")))
        after <- checked(s.catalog.completedRun(s.context, result.invocation))
        _ = assertEquals(after, before)
        _ <- IO.blocking(assert(!Files.exists(root.resolve("publish-run"))))
      yield ()
    }
  }

  test("publication is owner-only and independent of old destination authority, with current private-source access") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        owner <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        sourceGrant <- checked(s.catalog.grant(s.context, source.ref.dataset, owner.principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        selection <- checked(s.catalog.select(owner, Vector(source.ref), query))
        privateDataset <- s.repository.create(owner, s.dataset.custodian)
        prepared <- checked(inputs(s).preparePrivate(owner, source.ref, selection.id, privateDataset.id, mask, MriInputPlanner.Contract))
        acceptedInput <- checked(s.repository.accept(owner, prepared.candidate.id, key("publication-private-input")))
        input <- checked(s.catalog.release(owner, acceptedInput.release))
        old <- s.repository.create(s.context, s.dataset.custodian)
        oldGrant <- checked(s.catalog.grant(s.context, old.id, owner.principal, None, Set(Action.ReadMetadata, Action.Propose)))
        result <- checked(new LocalQcRuns[IO](s.catalog, s.store, producer(s)).execute(owner,
          RunSpec(input.ref, input.activity.bundle.get.id, old.id, None, MriInputPlanner.Contract), key("owned-run"), root.resolve("owned-run")))
        _ <- checked(s.catalog.revoke(s.context, old.id, oldGrant.id))
        oldDenied <- s.catalog.readRun(owner, result.invocation)
        _ = assert(oldDenied.isLeft)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        proposalGrant <- checked(s.catalog.grant(s.context, destination.id, owner.principal, None, Set(Action.ReadMetadata, Action.Propose)))
        intent = PublicationIntent(result.invocation, result.id, owner.principal, destination.id, None, key("owned-publication"))
        wrongOwner <- submissions(s).publish(s.context, intent.copy(publisher = s.context.principal))
        _ = assertEquals(wrongOwner, Left(Problem.Denied))
        draft <- checked(submissions(s).publish(owner, intent))
        preview <- collect(s, owner, draft, result)
        noAccept <- derivatives(s).accept(owner, preview.candidate.id, key("owner-accept"))
        _ = assertEquals(noAccept, Left(Problem.Denied))
        acceptGrant <- checked(s.catalog.grant(s.context, destination.id, owner.principal, None, Set(Action.Accept)))
        _ <- checked(s.catalog.revoke(s.context, destination.id, proposalGrant.id))
        noProposal <- submissions(s).publish(owner, intent)
        _ = assertEquals(noProposal, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, destination.id, owner.principal, None, Set(Action.ReadMetadata, Action.Propose)))
        _ <- checked(s.catalog.revoke(s.context, destination.id, acceptGrant.id))
        acceptRevoked <- derivatives(s).accept(owner, preview.candidate.id, key("owner-accept"))
        _ = assertEquals(acceptRevoked, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, destination.id, owner.principal, None, Set(Action.Accept)))
        _ <- checked(s.catalog.revoke(s.context, source.ref.dataset, sourceGrant.id))
        inputRevoked <- submissions(s).publish(owner, intent)
        revokedAccept <- derivatives(s).accept(owner, preview.candidate.id, key("owner-accept"))
        _ = assertEquals(inputRevoked, Left(Problem.Denied))
        _ = assertEquals(revokedAccept, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, source.ref.dataset, owner.principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        _ <- checked(derivatives(s).accept(owner, preview.candidate.id, key("owner-accept")))
      yield ()
    }
  }

  test("publication refuses incomplete runs, changed receipts, forged activities and foreign bases") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        planned <- checked(s.catalog.startRun(s.context, spec, key("not-completed")))
        pending <- submissions(s).publish(s.context, PublicationIntent(planned.id, spec.bundle, s.context.principal, destination.id, None, key("pending")))
        _ = assert(pending.isLeft)
        result <- checked(new LocalQcRuns[IO](s.catalog, s.store, producer(s)).execute(s.context, spec, key("not-completed"), root.resolve("completed-now")))
        intent = PublicationIntent(result.invocation, result.id, s.context.principal, destination.id, None, key("valid-publication"))
        forged <- submissions(s).publish(s.context, intent.copy(receipt = spec.bundle))
        foreign <- submissions(s).publish(s.context, intent.copy(base = Some(source.ref.release)))
        sourceDestination <- submissions(s).publish(s.context, intent.copy(destination = input.ref.dataset))
        _ = assert(forged.isLeft && foreign.isLeft && sourceDestination.isLeft)
        draft <- checked(submissions(s).publish(s.context, intent))
        raw <- s.catalog.draft(s.context, destination.id, None, draft.manifest,
          draft.activity.copy(processing = Some(result.copy(parameters = Map("forged" -> "yes")))))
        _ = assert(raw.isLeft)
        ordinary <- checked(s.catalog.draft(s.context, destination.id, None, draft.manifest,
          draft.activity.copy(kind = ActivityKind.NativeImport, processing = None, publication = None)))
        added <- s.catalog.revise(s.context, ordinary.id, ordinary.generation, ordinary.manifest, draft.activity)
        _ = assert(added.isLeft)
      yield ()
    }
  }

  test("uncertain execution is explicit and late completion cannot override interruption") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        planned <- checked(s.catalog.startRun(s.context, spec, key("uncertain")))
        preparing <- checked(s.catalog.advanceRun(s.context, planned.id, 0, RunUpdate(RunStatus.Preparing)))
        refused <- new LocalQcRuns[IO](s.catalog, s.store, producer(s)).execute(s.context, spec, key("uncertain"), root.resolve("must-not-start"))
        _ = assert(refused.isLeft)
        _ <- IO.blocking(assert(!Files.exists(root.resolve("must-not-start"))))
        interrupted <- checked(new LocalQcRuns[IO](s.catalog, s.store, producer(s)).interrupt(s.context, preparing.id, preparing.generation, "Process outcome unknown after restart"))
        _ = assertEquals(interrupted.status, RunStatus.Interrupted)
        late <- s.catalog.advanceRun(s.context, preparing.id, preparing.generation, RunUpdate(RunStatus.Failed, detail = Some("late worker")))
        _ = assert(late.isLeft)
        failureRunner = new LocalQcRuns[IO](s.catalog, s.store,
          new MriQcProducer[IO](s.catalog, s.store, s.repository, IdSource.random[IO], root.resolve("missing-python")))
        failure <- failureRunner.execute(s.context, spec, key("failed-launch"), root.resolve("failed-launch"))
        _ = assert(failure.isLeft)
        failed <- checked(s.catalog.startRun(s.context, spec, key("failed-launch")))
        _ = assertEquals(failed.status, RunStatus.Failed)
      yield ()
    }
  }

  test("cancellation before launch leaves an interrupted durable run and no output publication") {
    fixture { (s, root) =>
      for
        source <- accepted(s, root)
        input <- bundle(s, source)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        reached <- Deferred[IO, Unit]
        paused = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob) *> reached.complete(()).void *> IO.never[Unit]
        spec = RunSpec(input.ref, input.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        worker <- new LocalQcRuns[IO](s.catalog, paused, producer(s)).execute(s.context, spec, key("cancelled-run"), root.resolve("cancelled-run")).start
        _ <- reached.get
        _ <- worker.cancel
        record <- checked(s.catalog.startRun(s.context, spec, key("cancelled-run")))
        _ = assertEquals(record.status, RunStatus.Interrupted)
        _ = assert(record.result.isEmpty)
        _ <- IO.blocking(assert(!Files.exists(root.resolve("cancelled-run/output"))))
        dataset <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(dataset.head, None)
      yield ()
    }
  }
