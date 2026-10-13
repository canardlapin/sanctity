package sanctity.app

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import io.circe.Json
import sanctity.processing.*
import java.nio.file.{Files, Path}

/** Reproducible public-contract demonstration, with inspectable exports and receipt. */
object TemporalDemo extends IOApp.Simple:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  def run: IO[Unit] =
    val ids = IdSource.random[IO]
    for
      root <- IO.blocking(Files.createTempDirectory(Path.of("target"), "temporal-demo-"))
      _ <- SyntheticFixture.create(root.resolve("source"))
      _ <- IO.blocking {
        val file = root.resolve("source").resolve(SyntheticFixture.firstImage)
        val bytes = Files.readAllBytes(file)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(352, 3).putFloat(356, 7)
        val _ = Files.write(file, bytes)
      }
      base <- ReferenceSystem.create(root.resolve("storage"))
      s = base.copy(repository = new NativeRepository[IO](base.catalog, base.store, new PayloadBidsInterpreter[IO](base.store), ids))
      original <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      source <- checked(s.repository.accept(s.context, original.candidate.id, key("source")))
      researcher <- ids.next[PrincipalTag].map(AccessContext(_))
      custodian <- ids.next[CustodianTag]
      access <- checked(s.catalog.grant(s.context, source.release.dataset, researcher.principal, Some(source.release.release), Set(Action.ReadMetadata, Action.ReadContent)))
      selection <- checked(s.catalog.select(researcher, Vector(source.release), ScientificQuery(artifacts = ArtifactPredicate.All(
        Vector(ArtifactPredicate.Entity("sub", "01"), ArtifactPredicate.Suffix("bold"))))))
      inputs = new ProcessingInputs[IO](s.catalog, s.repository,
        new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids), new TemporalInputPlanner)
      destination <- s.repository.create(researcher, custodian)
      proposal <- checked(inputs.preparePrivate(researcher, source.release, selection.id, destination.id,
        DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), TemporalStatistics.Contract))
      frozen <- checked(s.repository.accept(researcher, proposal.candidate.id, key("bundle")))
      _ <- checked(inputs.materialize(researcher, frozen.release, new DirectoryExport[IO](root.resolve("bundle"))))
      derivatives = new NativeRepository[IO](s.catalog, s.store, new TemporalDerivativeInterpreter[IO](s.store), ids)
      output <- derivatives.create(researcher, custodian)
      producer = new TemporalProducer[IO](s.catalog, s.store, s.repository, ids, Path.of(sys.env.getOrElse("SANCTITY_MRI_PYTHON", throw new IllegalStateException("Set SANCTITY_MRI_PYTHON after running scripts/setup-mri-python.sh"))))
      runs = new LocalProcessingRuns[IO](s.catalog, s.store, producer)
      spec = RunSpec(frozen.release, proposal.candidate.activity.bundle.get.id, output.id, None, TemporalStatistics.Contract)
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
      stranded <- checked(runs.execute(researcher, spec.copy(base = Some(accepted.release.release)), key("stranded-run"), root.resolve("execution-stranded")))
      next <- checked(submissions.beginStagedAt(researcher, accepted.release, second))
      _ <- second.expected.traverse_(f => checked(submissions.collectStaged(researcher, next.id, f.path)))
      prepared <- checked(submissions.prepare(researcher, next.id))
      latest <- checked(derivatives.accept(researcher, prepared.candidate.id, key("derivative-2")))
      _ <- checked(derivatives.exportFull(researcher, latest.release, new DirectoryExport[IO](root.resolve("derivative-2"))))
      conflict <- submissions.beginStagedAt(researcher, accepted.release, stranded)
      _ <- IO.raiseUnless(conflict.isLeft)(new IllegalStateException("stale execution base unexpectedly published"))
      _ <- IO.blocking {
        val walk = Files.walk(root.resolve("execution-stranded"))
        try walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => { Files.delete(p); () })
        finally walk.close()
      }
      intent = PublicationIntent(stranded.invocation, stranded.id, researcher.principal, output.id,
        Some(latest.release.release), key("reviewed-publication"))
      publication <- checked(submissions.publish(researcher, intent))
      _ <- checked(submissions.collectStaged(researcher, publication.id, stranded.expected.head.path))
      recovered <- checked(resumed.publish(researcher, intent))
      _ <- stranded.expected.traverse_(f => checked(resumed.collectStaged(researcher, recovered.id, f.path)))
      publicationCandidate <- checked(resumed.prepare(researcher, recovered.id))
      published <- checked(derivatives.accept(researcher, publicationCandidate.candidate.id, key("published-run")))
      _ <- checked(derivatives.exportFull(researcher, published.release, new DirectoryExport[IO](root.resolve("derivative-3"))))
      publishedRelease <- checked(s.catalog.release(researcher, published.release))
      _ <- IO.raiseUnless(publishedRelease.manifest.files.map(f => f.path -> f.blob).toMap == stranded.expected.map(f => f.path -> f.blob).toMap)(
        new IllegalStateException("publication changed completed output bytes"))
      // Reconstruct an oracle workspace from managed evidence and fresh exports, after execution workspace loss.
      _ <- IO.blocking(Files.createDirectory(root.resolve("publication-oracle")))
      _ <- checked(inputs.materialize(researcher, frozen.release, new DirectoryExport[IO](root.resolve("publication-oracle/input"))))
      _ <- checked(derivatives.exportFull(researcher, published.release, new DirectoryExport[IO](root.resolve("publication-oracle/output"))))
      request <- s.store.read(stranded.request).compile.toVector
      _ <- IO.blocking(Files.write(root.resolve("publication-oracle/request.json"), request.toArray))
      observed = candidate.candidate.interpretation.observations.find(_.metric == TemporalStatistics.metric("mean")).get
      coverage <- checked(new ScientificResults[IO](s.catalog).coverage(researcher, Vector(accepted.release, latest.release, published.release),
        ObservationQuery(Vector(observed.source), observed.metric)))
      _ <- IO.raiseUnless(coverage.head.results.size == 3)(new IllegalStateException("expected three accepted temporal-map observations"))
      run <- checked(s.catalog.completedRun(researcher, stranded.invocation))
      _ <- IO.raiseUnless(run.spec.base.contains(accepted.release.release) && run.result.contains(stranded))(
        new IllegalStateException("publication rewrote execution evidence"))
      _ <- IO.blocking(Files.writeString(root.resolve("run-and-observations.json"), QcDerivativeFormat.render(Json.obj(
        "run" -> Json.fromString(run.id.render), "status" -> Json.fromString(run.status.toString),
        "generation" -> Json.fromLong(run.generation), "firstRelease" -> Json.fromString(accepted.release.release.render),
        "secondRelease" -> Json.fromString(latest.release.release.render), "inputRelease" -> Json.fromString(observed.source.at.release.render),
        "thirdRelease" -> Json.fromString(published.release.release.render), "publication" -> Json.fromString(intent.id.value),
        "metric" -> Json.fromString(observed.metric.name), "value" -> Json.fromBigDecimal(observed.value),
        "acceptedResults" -> Json.fromInt(coverage.head.results.size)))))
      _ <- checked(s.catalog.revoke(s.context, source.release.dataset, access.id))
      denied <- inputs.materialize(researcher, frozen.release, new DirectoryExport[IO](root.resolve("revoked")))
      _ <- IO.raiseUnless(denied.isLeft)(new IllegalStateException("revoked source remained available through private bundle"))
      publicationDenied <- submissions.publish(researcher, intent)
      _ <- IO.raiseUnless(publicationDenied.isLeft)(new IllegalStateException("revoked source allowed publication retry"))
      _ <- IO.blocking(Files.writeString(root.resolve("publication.json"), QcDerivativeFormat.render(Json.obj(
        "run" -> Json.fromString(stranded.invocation.render), "receipt" -> Json.fromString(stranded.id.value),
        "publication" -> Json.fromString(intent.id.value), "publisher" -> Json.fromString(intent.publisher.render),
        "key" -> Json.fromString(intent.key.value), "destination" -> Json.fromString(intent.destination.render),
        "originalBase" -> Json.fromString(accepted.release.release.render), "reviewedBase" -> Json.fromString(latest.release.release.render),
        "release" -> Json.fromString(published.release.release.render), "draft" -> Json.fromString(publication.id.render),
        "inputRelease" -> Json.fromString(stranded.input.release.render),
        "executionWorkspaceRemoved" -> Json.fromBoolean(!Files.exists(root.resolve("execution-stranded"))),
        "sourceRevocationDeniedRetry" -> Json.fromBoolean(publicationDenied.isLeft),
        "outputs" -> Json.arr(stranded.expected.map(f => Json.obj("path" -> Json.fromString(f.path.value),
          "sha256" -> Json.fromString(f.blob.digest.value), "size" -> Json.fromLong(f.blob.size)))*)))))
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
      _ <- IO.println(s"Accepted temporal maps, recovered a stranded completed run through explicit publication, queried three releases and enforced source revocation. Evidence: ${root.toAbsolutePath}")
    yield ()
