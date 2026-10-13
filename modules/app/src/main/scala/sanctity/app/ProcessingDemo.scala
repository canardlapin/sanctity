package sanctity.app

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import io.circe.Json
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import java.nio.file.{Files, Path}

/** Reproducible public-contract demonstration, with inspectable exports and receipt. */
object ProcessingDemo extends IOApp.Simple:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  def run: IO[Unit] =
    val ids = IdSource.random[IO]
    for
      root <- IO.blocking(Files.createTempDirectory(Path.of("target"), "processing-demo-"))
      _ <- SyntheticFixture.create(root.resolve("source"))
      s <- ReferenceSystem.create(root.resolve("storage"))
      original <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      source <- checked(s.repository.accept(s.context, original.candidate.id, key("source")))
      researcher <- ids.next[PrincipalTag].map(AccessContext(_))
      custodian <- ids.next[CustodianTag]
      access <- checked(s.catalog.grant(s.context, source.release.dataset, researcher.principal, Some(source.release.release), Set(Action.ReadMetadata, Action.ReadContent)))
      selection <- checked(s.catalog.select(researcher, Vector(source.release), ScientificQuery(artifacts = ArtifactPredicate.All(
        Vector(ArtifactPredicate.Entity("sub", "01"), ArtifactPredicate.Suffix("bold"))))))
      inputs = new ProcessingInputs[IO](s.catalog, s.repository,
        new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids), new MriInputPlanner)
      destination <- s.repository.create(researcher, custodian)
      proposal <- checked(inputs.preparePrivate(researcher, source.release, selection.id, destination.id,
        DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), MriInputPlanner.Contract))
      frozen <- checked(s.repository.accept(researcher, proposal.candidate.id, key("bundle")))
      _ <- checked(inputs.materialize(researcher, frozen.release, new DirectoryExport[IO](root.resolve("bundle"))))
      derivatives = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
      output <- derivatives.create(researcher, custodian)
      producer = new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3"))
      runs = new LocalQcRuns[IO](s.catalog, s.store, producer)
      spec = RunSpec(frozen.release, proposal.candidate.activity.bundle.get.id, output.id, None, MriInputPlanner.Contract)
      receipt <- checked(runs.execute(researcher, spec, key("qc-run-1"), root.resolve("execution")))
      submissions = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, ids)
      draft <- checked(submissions.beginStaged(researcher, output.id, receipt))
      _ <- checked(submissions.collectStaged(researcher, draft.id, receipt.expected.head.path))
      // A new service instance resumes the persisted draft without executing the tool again.
      resumed = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, ids)
      _ <- receipt.expected.traverse_(f => checked(resumed.collectStaged(researcher, draft.id, f.path)))
      candidate <- checked(resumed.prepare(researcher, draft.id))
      accepted <- checked(derivatives.accept(researcher, candidate.candidate.id, key("derivative")))
      repeated <- checked(derivatives.accept(researcher, candidate.candidate.id, key("derivative")))
      _ <- IO.raiseUnless(accepted == repeated)(new IllegalStateException("retry changed receipt"))
      _ <- checked(derivatives.exportFull(researcher, accepted.release, new DirectoryExport[IO](root.resolve("derivative"))))
      second <- checked(runs.execute(researcher, spec.copy(base = Some(accepted.release.release)), key("qc-run-2"), root.resolve("execution-2")))
      next <- checked(submissions.beginStagedAt(researcher, accepted.release, second))
      _ <- second.expected.traverse_(f => checked(submissions.collectStaged(researcher, next.id, f.path)))
      prepared <- checked(submissions.prepare(researcher, next.id))
      latest <- checked(derivatives.accept(researcher, prepared.candidate.id, key("derivative-2")))
      _ <- checked(derivatives.exportFull(researcher, latest.release, new DirectoryExport[IO](root.resolve("derivative-2"))))
      observed = candidate.candidate.interpretation.observations.find(_.metric.name == "volumes").get
      coverage <- checked(new ScientificResults[IO](s.catalog).coverage(researcher, Vector(accepted.release, latest.release),
        ObservationQuery(Vector(observed.source), observed.metric)))
      _ <- IO.raiseUnless(coverage.head.results.size == 2)(new IllegalStateException("expected two accepted QC observations"))
      run <- checked(s.catalog.readRun(researcher, second.invocation))
      _ <- IO.blocking(Files.writeString(root.resolve("run-and-observations.json"), QcDerivativeFormat.render(Json.obj(
        "run" -> Json.fromString(run.id.render), "status" -> Json.fromString(run.status.toString),
        "generation" -> Json.fromLong(run.generation), "firstRelease" -> Json.fromString(accepted.release.release.render),
        "secondRelease" -> Json.fromString(latest.release.release.render), "inputRelease" -> Json.fromString(observed.source.at.release.render),
        "metric" -> Json.fromString(observed.metric.name), "value" -> Json.fromBigDecimal(observed.value),
        "acceptedResults" -> Json.fromInt(coverage.head.results.size)))))
      _ <- checked(s.catalog.revoke(s.context, source.release.dataset, access.id))
      denied <- inputs.materialize(researcher, frozen.release, new DirectoryExport[IO](root.resolve("revoked")))
      _ <- IO.raiseUnless(denied.isLeft)(new IllegalStateException("revoked source remained available through private bundle"))
      _ <- IO.blocking {
        val processing = Json.obj("id" -> Json.fromString(receipt.id.value),
          "invocation" -> Json.fromString(receipt.invocation.render),
          "inputDataset" -> Json.fromString(receipt.input.dataset.render), "inputRelease" -> Json.fromString(receipt.input.release.render),
          "bundle" -> Json.fromString(receipt.bundle.value), "producer" -> Json.fromString(receipt.producer.name),
          "version" -> Json.fromString(receipt.producer.version), "runtime" -> Json.fromString(receipt.producer.runtime),
          "requestSha256" -> Json.fromString(receipt.request.digest.value), "codeSha256" -> Json.fromString(receipt.producer.code.digest.value), "parameters" -> QcDerivativeFormat.parameters(receipt.parameters),
          "targets" -> Json.arr(receipt.targets.map(p => Json.fromString(p.value))*),
          "outputs" -> Json.arr(receipt.expected.map(f => Json.obj("path" -> Json.fromString(f.path.value),
            "sha256" -> Json.fromString(f.blob.digest.value), "size" -> Json.fromLong(f.blob.size)))*))
        val bundle = proposal.candidate.activity.bundle.get
        val evidence = Json.obj("id" -> Json.fromString(bundle.id.value), "tree" -> Json.fromString(bundle.tree.value),
          "readiness" -> Json.fromString(bundle.readiness.id.value), "projection" -> Json.fromString(bundle.projection.value),
          "contract" -> Json.fromString(bundle.readiness.contract.name), "version" -> Json.fromString(bundle.readiness.contract.version),
          "requirements" -> Json.arr(bundle.readiness.requirements.map(r => Json.obj("code" -> Json.fromString(r.code),
            "status" -> Json.fromString(r.status.toString), "target" -> r.target.fold(Json.Null)(p => Json.fromString(p.value)),
            "message" -> Json.fromString(r.message)))*))
        Files.writeString(root.resolve("processing-receipt.json"), QcDerivativeFormat.render(processing))
        Files.writeString(root.resolve("bundle-evidence.json"), QcDerivativeFormat.render(evidence))
      }
      _ <- IO.println(s"Accepted a private MRI bundle and two source-verified QC releases; queried exact-input observations and enforced source revocation. Evidence: ${root.toAbsolutePath}")
    yield ()
