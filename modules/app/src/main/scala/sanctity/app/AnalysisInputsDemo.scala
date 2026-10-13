package sanctity.app

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Select one echo, freeze complete inputs, publish exact per-image QC, then exercise history and revocation. */
object AnalysisInputsDemo extends IOApp.Simple:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  def run: IO[Unit] =
    val fixture = Vector(Path.of("src/test/resources/mri-conformance/analysis-inputs/dataset"),
      Path.of("modules/app/src/test/resources/mri-conformance/analysis-inputs/dataset")).find(Files.isDirectory(_)).get
    val ids = IdSource.random[IO]
    val members = Vector(1, 2, 3).map(i => s"sub-01/func/sub-01_task-cue_run-01_echo-${i}_bold.nii")
    val mask = DisclosureProfile(Set.empty, Set("License", "TaskName", "RepetitionTime", "EchoTime"),
      eventFields = Some(Set("onset", "duration", "trial_type", "stim_file")))
    for
      root <- IO.blocking(Files.createTempDirectory(Path.of("target"), "analysis-input-demo-"))
      s <- ReferenceSystem.create(root.resolve("storage"))
      proposed <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](fixture)))
      original <- checked(s.repository.accept(s.context, proposed.candidate.id, key("source")))
      source <- checked(s.catalog.release(s.context, original.release))
      reader <- ids.next[PrincipalTag].map(AccessContext(_))
      grant <- checked(s.catalog.grant(s.context, source.ref.dataset, reader.principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
      query = ScientificQuery(artifacts = ArtifactPredicate.All(Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("echo", "2"))))
      selection <- checked(s.catalog.select(reader, Vector(source.ref), query))
      _ <- IO.raiseUnless(selection.hits.map(_.path) == Vector(p(members(1))))(new IllegalStateException("selection did not isolate echo 2"))
      views = new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids)
      inputs = new ProcessingInputs[IO](s.catalog, s.repository, views, new MriInputPlanner)
      destination <- s.repository.create(reader, s.dataset.custodian)
      proposal <- checked(inputs.preparePrivate(reader, source.ref, selection.id, destination.id, mask, MriInputPlanner.Contract))
      frozen <- checked(s.repository.accept(reader, proposal.candidate.id, key("input")))
      input <- checked(inputs.bundle(reader, frozen.release))
      _ <- IO.raiseUnless(input.activity.bundle.get.readiness.roots.toSet == members.map(p).toSet)(new IllegalStateException("bundle lost family members"))
      derivatives = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
      submissions = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, ids)
      output <- s.repository.create(reader, s.dataset.custodian)
      producer = new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3"))
      runs = new LocalQcRuns[IO](s.catalog, s.store, producer)
      spec = RunSpec(input.ref, input.activity.bundle.get.id, output.id, None, MriInputPlanner.Contract)
      result <- checked(runs.execute(reader, spec, key("run"), root.resolve("execution")))
      _ <- IO.blocking {
        val walk = Files.walk(root.resolve("execution"))
        try walk.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
        finally walk.close()
      }
      intent = PublicationIntent(result.invocation, result.id, reader.principal, output.id, None, key("publication"))
      draft <- checked(submissions.publish(reader, intent))
      _ <- result.expected.traverse_(f => checked(submissions.collectStaged(reader, draft.id, f.path)))
      ready <- checked(submissions.prepare(reader, draft.id))
      accepted <- checked(derivatives.accept(reader, ready.candidate.id, key("output")))
      correction <- checked(s.repository.correctMetadata(s.context, source.ref, p(members(1).stripSuffix(".nii") + ".json"),
        """{"EchoTime":0.025}""", "Correct second echo time"))
      corrected <- checked(s.repository.accept(s.context, correction.candidate.id, key("corrected-source")))
      historical <- checked(inputs.bundle(reader, input.ref))
      _ <- IO.raiseUnless(historical.activity.bundle == input.activity.bundle)(new IllegalStateException("source correction changed historical bundle"))
      repeated <- checked(runs.execute(reader, spec, key("run"), root.resolve("must-not-execute")))
      _ <- IO.raiseUnless(repeated.id == result.id && !Files.exists(root.resolve("must-not-execute")))(new IllegalStateException("completed run executed again"))
      _ <- checked(inputs.materialize(reader, input.ref, new DirectoryExport[IO](root.resolve("input"))))
      _ <- checked(derivatives.exportFull(reader, accepted.release, new DirectoryExport[IO](root.resolve("output"))))
      qc <- IO.blocking(Files.readString(root.resolve("output/qc.tsv")))
      expected = QcDerivativeFormat.Columns.mkString("\t") + "\n" + members.sorted.map(_ + "\t2\t2\t2\t3\t2\t2\n").mkString
      _ <- IO.raiseUnless(qc == expected)(new IllegalStateException("QC differs from independent fixture values"))
      _ <- IO.blocking {
        (members ++ Vector("stimuli/images/red.ppm", "stimuli/tone.txt")).foreach { n =>
          require(java.util.Arrays.equals(Files.readAllBytes(root.resolve("input").resolve(n)), Files.readAllBytes(fixture.resolve(n))), "historical bytes changed: " + n)
        }
      }
      _ <- checked(s.catalog.revoke(s.context, source.ref.dataset, grant.id))
      denied <- inputs.materialize(reader, input.ref, new DirectoryExport[IO](root.resolve("revoked-input")))
      deniedPublication <- submissions.publish(reader, intent)
      _ <- IO.raiseUnless(denied.isLeft && deniedPublication.isLeft)(new IllegalStateException("revocation failed"))
      _ <- IO.blocking {
        def files(entries: Vector[Entry.File]): Json = Json.arr(entries.map(f => Json.obj(
          "path" -> Json.fromString(f.path.value), "sha256" -> Json.fromString(f.blob.digest.value), "size" -> Json.fromLong(f.blob.size)))* )
        val receipt = Json.obj("source" -> Json.fromString(source.ref.release.render), "correctedSource" -> Json.fromString(corrected.release.release.render),
          "input" -> Json.fromString(input.ref.release.render), "output" -> Json.fromString(accepted.release.release.render),
          "bundle" -> Json.fromString(input.activity.bundle.get.id.value), "run" -> Json.fromString(result.invocation.render),
          "processing" -> Json.fromString(result.id.value), "publication" -> Json.fromString(intent.id.value),
          "selectedEchoes" -> Json.fromInt(selection.hits.size), "processedEchoes" -> Json.fromInt(result.targets.size),
          "sourceFingerprint" -> Json.fromString(BuildIdentity.sanctity), "bids4sFingerprint" -> Json.fromString(BuildIdentity.bids4s),
          "executionWorkspaceRemoved" -> Json.fromBoolean(!Files.exists(root.resolve("execution"))),
          "revokedInputDenied" -> Json.fromBoolean(denied.isLeft), "revokedPublicationDenied" -> Json.fromBoolean(deniedPublication.isLeft),
          "inputs" -> files(input.manifest.files), "outputs" -> Json.arr(result.expected.map(f => Json.obj(
            "path" -> Json.fromString(f.path.value), "sha256" -> Json.fromString(f.blob.digest.value), "size" -> Json.fromLong(f.blob.size)))*))
        Files.writeString(root.resolve("receipt.json"), QcDerivativeFormat.render(receipt))
      }
      _ <- IO.println(s"Selected one echo; retained three echoes, shared events and two stimuli; published QC; verified immutable history and current revocation. Evidence: ${root.toAbsolutePath}")
    yield ()
