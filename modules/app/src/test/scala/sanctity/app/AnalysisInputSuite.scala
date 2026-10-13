package sanctity.app

import cats.effect.IO
import cats.syntax.all.*
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

abstract class CatalogAnalysisInputSuite extends CatalogPhasePairSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val provenance = MriFixtures.json("analysis-inputs/provenance.json").hcursor
  private val inventory = provenance.downField("files").focus.get.asObject.get.keys.toVector.sorted
  private val members = provenance.get[Vector[String]]("expectedMembers").toOption.get
  private val assets = provenance.get[Vector[String]]("expectedStimuli").toOption.get
  private val events = provenance.get[String]("events").toOption.get
  private val mask = DisclosureProfile(Set.empty, Set("License", "TaskName", "RepetitionTime", "EchoTime"),
    eventFields = Some(Set("onset", "duration", "trial_type", "stim_file")))
  private val query = ScientificQuery(artifacts = ArtifactPredicate.All(Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("echo", "2"))))
  private def install(root: Path): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
    inventory.foreach { name =>
      val out = root.resolve("source").resolve(name)
      Files.createDirectories(out.getParent)
      val _ = Files.write(out, MriFixtures.bytes("analysis-inputs/dataset/" + name))
    }
  }
  private def original(s: RepositorySystem, root: Path): IO[Release] = for {
    imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
    _ = assert(imported.candidate.assessment.admissible, imported.candidate.assessment.errors.toString)
    accepted <- checked(s.repository.accept(s.context, imported.candidate.id, key("analysis-source")))
    release <- checked(s.catalog.release(s.context, accepted.release))
  } yield release
  private def views(s: RepositorySystem) = new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, IdSource.random[IO])
  private def inputs(s: RepositorySystem) = new ProcessingInputs[IO](s.catalog, s.repository, views(s), new MriInputPlanner)
  private def changes(s: RepositorySystem) = new ScientificChanges[IO](s.catalog, s.store, s.repository, new BidsChangeCompiler)

  MriFixtures.json("analysis-inputs/contract.json").hcursor.get[Vector[io.circe.Json]]("cases").toOption.get.foreach { specification =>
    val c = specification.hcursor
    val name = c.get[String]("name").toOption.get
    test("analysis input independent corpus: " + name) {
      fixture { (s, root) => for
        _ <- install(root)
        _ <- IO.blocking {
          val base = root.resolve("source")
          c.get[Vector[String]]("delete").getOrElse(Vector.empty).foreach(n => Files.delete(base.resolve(n)))
          c.get[Map[String, io.circe.Json]]("jsonEdits").getOrElse(Map.empty).foreach((n, j) => Files.writeString(base.resolve(n), j.noSpaces))
          c.get[Map[String, String]]("writes").getOrElse(Map.empty).foreach((n, text) => Files.writeString(base.resolve(n), text))
          c.get[Map[String, String]]("copy").getOrElse(Map.empty).foreach((a, b) => Files.copy(base.resolve(a), base.resolve(b)))
          c.get[Map[String, Float]]("pixelDimension").getOrElse(Map.empty).foreach { (n, v) =>
            val bytes = Files.readAllBytes(base.resolve(n))
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(80, v)
            val _ = Files.write(base.resolve(n), bytes)
          }
        }
        preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
        observed = preview.candidate.assessment.errors.map(_.code).filter(v => v.startsWith("MultiEcho") || v.startsWith("Stimulus")).distinct.sorted
        _ = assertEquals(observed, c.get[Vector[String]]("findings").toOption.get.sorted)
        _ = if observed.isEmpty then assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      yield () }
    }
  }

  test("analysis inputs expand one selected echo into its exact family, shared events and referenced stimuli") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      closure <- Checked.lift[IO, RelationshipClosure](source.interpretation.relationshipClosure(Vector(p(members(1)))))
      _ = assertEquals(closure.artifacts.toSet, (members ++ assets :+ events).map(p).toSet)
      _ = assertEquals(closure.relationships.count(_.kind == "bids.multi-echo-bold"), 3)
      _ = assert(closure.relationships.exists(_.evidence.exists(_.isInstanceOf[RelationshipEvidence.TableColumn])))
      selection <- checked(s.catalog.select(s.context, Vector(source.ref), query))
      _ = assertEquals(selection.hits.map(_.path), Vector(p(members(1))))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      preview <- checked(views(s).prepare(s.context, source.ref, selection.id, destination.id, mask))
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("analysis-view")))
      view <- checked(s.catalog.release(s.context, receipt.release))
      _ = assertEquals(view.manifest.files.filter(_.path.value.endsWith(".nii")).map(_.path).toSet, members.map(p).toSet)
      _ = assertEquals(view.manifest.files.count(_.path.fileName.endsWith("_events.tsv")), 1)
      _ = assertEquals(view.manifest.files.filter(_.path.value.startsWith("stimuli/")).map(_.path).toSet, assets.map(p).toSet)
      _ = (members ++ assets).foreach(name => assertEquals(view.manifest.byPath(p(name)), source.manifest.byPath(p(name))))
      _ = assert(view.activity.projection.get.receipt.get.matches(view.manifest))
      _ <- checked(s.repository.exportFull(s.context, view.ref, new DirectoryExport[IO](root.resolve("analysis-view"))))
      other <- s.repository.create(s.context, s.dataset.custodian)
      roundtrip <- checked(s.repository.importNative(s.context, other.id, new NativeDirectorySource[IO](root.resolve("analysis-view"))))
      _ = assert(roundtrip.candidate.assessment.admissible, roundtrip.candidate.assessment.errors.toString)
      _ = assertEquals(roundtrip.candidate.interpretation.artifacts, view.interpretation.artifacts)
      _ = assertEquals(roundtrip.candidate.interpretation.relationships.get.relationships, view.interpretation.relationships.get.relationships)
    yield () }
  }

  test("analysis input disclosure excludes masked stimulus bytes and refuses loss of echo metadata") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      selection <- checked(s.catalog.select(s.context, Vector(source.ref), query))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      preview <- checked(views(s).prepare(s.context, source.ref, selection.id, destination.id,
        mask.copy(eventFields = Some(Set("onset", "duration")))))
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      _ = assert(!preview.candidate.manifest.files.exists(_.path.value.startsWith("stimuli/")))
      _ = assert(!preview.candidate.interpretation.relationships.get.relationships.exists(_.kind == "bids.event-stimuli"))
      table = preview.candidate.manifest.files.find(_.path.fileName.endsWith("_events.tsv")).get
      text <- s.store.read(table.blob).through(fs2.text.utf8.decode).compile.string
      _ = assertEquals(text, "onset\tduration\n0\t1\n2\t1\n4\t1\n5\t0\n")
      refused <- views(s).prepare(s.context, source.ref, selection.id, destination.id, mask.copy(metadataFields = mask.metadataFields - "EchoTime"))
      _ = assert(refused.isLeft)
    yield () }
  }

  test("analysis input curation moves the complete family and rewrites shared stimulus references") {
    fixture { (s, root) =>
      val images = members.flatMap(n => Vector(n, n.stripSuffix(".nii") + ".json"))
      val edits = images.map(n => FileEdit.Move(p(n), p(n.replace("run-01", "run-2")))) :+
        FileEdit.Move(p(assets.head), p("stimuli/images/renamed.ppm"))
      for
        _ <- install(root)
        source <- original(s, root)
        partial <- changes(s).plan(s.context, source.ref, edits.take(1))
        removed <- changes(s).plan(s.context, source.ref, Vector(FileEdit.Remove(p(assets.head))))
        split <- changes(s).plan(s.context, source.ref, members.zipWithIndex.map((n, i) =>
          FileEdit.Move(p(n), p(n.replace(s"run-01_echo-${i + 1}", s"run-${i + 1}")))))
        _ = assert(partial.isLeft && removed.isLeft && split.isLeft)
        plan <- checked(changes(s).plan(s.context, source.ref, edits))
        stale <- changes(s).prepare(s.context, source.ref, edits, Digest.bytes(Array[Byte](7)), "Wrong plan")
        _ = assert(stale.isLeft)
        preview <- checked(changes(s).prepare(s.context, source.ref, edits, plan.id, "Move complete echo acquisition and its explicit asset"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        accepted <- checked(s.repository.accept(s.context, preview.candidate.id, key("analysis-moves")))
        changed <- checked(s.catalog.release(s.context, accepted.release))
        _ = members.foreach { n =>
          val a = source.manifest.byPath(p(n)).asInstanceOf[Entry.File]
          val b = changed.manifest.byPath(p(n.replace("run-01", "run-2"))).asInstanceOf[Entry.File]
          assertEquals(b.artifact, a.artifact)
          assertEquals(b.blob, a.blob)
        }
        table <- s.store.read(changed.manifest.byPath(p(events)).asInstanceOf[Entry.File].blob).through(fs2.text.utf8.decode).compile.string
        _ = assertEquals(table, new String(MriFixtures.bytes("analysis-inputs/dataset/" + events), java.nio.charset.StandardCharsets.UTF_8).replace("images/red.ppm", "images/renamed.ppm"))
        historical <- checked(s.catalog.release(s.context, source.ref))
        _ = assertEquals(historical.interpretation.id, source.interpretation.id)
        _ <- checked(s.repository.exportFull(s.context, source.ref, new DirectoryExport[IO](root.resolve("analysis-history"))))
        _ <- IO.blocking(assertEquals(Files.readAllBytes(root.resolve("analysis-history").resolve(assets.head)).toVector,
          MriFixtures.bytes("analysis-inputs/dataset/" + assets.head).toVector))
      yield ()
    }
  }

  test("analysis input header coherence rejects a changed echo grid without advancing the head") {
    fixture { (s, root) => for
      _ <- install(root)
      _ <- IO.blocking {
        val path = root.resolve("source").resolve(members(1))
        val bytes = Files.readAllBytes(path)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(80, 5f)
        val _ = Files.write(path, bytes)
      }
      preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      _ = assert(preview.candidate.assessment.errors.exists(_.code == "MultiEchoImageCoherence"))
      denied <- s.repository.accept(s.context, preview.candidate.id, key("analysis-invalid-grid"))
      _ = assert(denied.isLeft)
      head <- checked(s.catalog.dataset(s.context, s.dataset.id))
      _ = assertEquals(head.head, None)
    yield () }
  }

  test("analysis inputs run per-echo QC, publish retained results and preserve history under correction and revocation") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      reader <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
      grant <- checked(s.catalog.grant(s.context, source.ref.dataset, reader.principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
      selection <- checked(s.catalog.select(reader, Vector(source.ref), query))
      destination <- s.repository.create(reader, s.dataset.custodian)
      preview <- checked(inputs(s).preparePrivate(reader, source.ref, selection.id, destination.id, mask, MriInputPlanner.Contract))
      _ = assertEquals(preview.candidate.activity.bundle.get.readiness.roots.toSet, members.map(p).toSet)
      accepted <- checked(s.repository.accept(reader, preview.candidate.id, key("analysis-private")))
      input <- checked(inputs(s).bundle(reader, accepted.release))
      output <- s.repository.create(reader, s.dataset.custodian)
      producer = new MriQcProducer[IO](s.catalog, s.store, s.repository, IdSource.random[IO], Path.of("/usr/bin/python3"))
      runner = new LocalQcRuns[IO](s.catalog, s.store, producer)
      spec = RunSpec(input.ref, input.activity.bundle.get.id, output.id, None, MriInputPlanner.Contract)
      result <- checked(runner.execute(reader, spec, key("analysis-run"), root.resolve("analysis-run")))
      _ = assertEquals(result.targets.toSet, members.map(p).toSet)
      qc <- s.store.read(result.expected.find(_.path == p("qc.tsv")).get.blob).through(fs2.text.utf8.decode).compile.string
      _ = assertEquals(qc, QcDerivativeFormat.Columns.mkString("\t") + "\n" + members.sorted.map(_ + "\t2\t2\t2\t3\t2\t2\n").mkString)
      _ <- TestFiles.remove(root.resolve("analysis-run"))
      derivatives = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), IdSource.random[IO])
      submissions = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, IdSource.random[IO])
      draft <- checked(submissions.publish(reader, PublicationIntent(result.invocation, result.id, reader.principal, output.id, None, key("analysis-publication"))))
      _ <- result.expected.traverse_(f => checked(submissions.collectStaged(reader, draft.id, f.path)))
      proposed <- checked(submissions.prepare(reader, draft.id))
      published <- checked(derivatives.accept(reader, proposed.candidate.id, key("analysis-published")))
      correction <- checked(s.repository.correctMetadata(s.context, source.ref, p(members(1).stripSuffix(".nii") + ".json"), """{"EchoTime":0.025}""", "Correct authored second echo time"))
      _ = assert(correction.candidate.assessment.admissible, correction.candidate.assessment.errors.toString)
      _ = assert(correction.relationshipChanges.nonEmpty)
      _ <- checked(s.repository.accept(s.context, correction.candidate.id, key("analysis-correction")))
      old <- checked(inputs(s).bundle(reader, input.ref))
      _ = assertEquals(old.activity.bundle, input.activity.bundle)
      retry <- checked(runner.execute(reader, spec, key("analysis-run"), root.resolve("must-not-run")))
      _ = assertEquals(retry.id, result.id)
      _ <- checked(inputs(s).materialize(reader, input.ref, new DirectoryExport[IO](root.resolve("analysis-bundle"))))
      _ <- checked(derivatives.exportFull(reader, published.release, new DirectoryExport[IO](root.resolve("analysis-output"))))
      _ <- IO.blocking {
        assert(!Files.exists(root.resolve("must-not-run")))
        assertEquals(Files.readString(root.resolve("analysis-output/qc.tsv")), qc)
        (members ++ assets).foreach(n => assertEquals(Files.readAllBytes(root.resolve("analysis-bundle").resolve(n)).toVector,
          MriFixtures.bytes("analysis-inputs/dataset/" + n).toVector))
      }
      _ <- checked(s.catalog.revoke(s.context, source.ref.dataset, grant.id))
      revoked <- inputs(s).bundle(reader, input.ref)
      denied <- inputs(s).materialize(reader, input.ref, new DirectoryExport[IO](root.resolve("revoked-analysis-input")))
      publicationDenied <- submissions.publish(reader, PublicationIntent(result.invocation, result.id, reader.principal, output.id, Some(published.release.release), key("analysis-revoked-publication")))
      _ = assert(revoked.isLeft && denied.isLeft && publicationDenied.isLeft)
    yield () }
  }
