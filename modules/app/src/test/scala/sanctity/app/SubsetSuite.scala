package sanctity.app

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.{BlobStore, Checked, IdSource, ScientificViews}
import sanctity.bids.MriViewCompiler
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** The same materialization contracts run on the reference and PostgreSQL catalogs. */
abstract class CatalogSubsetSuite extends CatalogChangeSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](a: IO[Either[Problem, A]]): IO[A] = a.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val bold = "sub-01/ses-task/func/sub-01_ses-task_task-motor_run-1_bold.nii"
  private val other = "sub-01/ses-task/func/sub-01_ses-task_task-motor_run-2_bold.nii"
  private val anat = "sub-01/ses-task/anat/sub-01_ses-task_T1w.nii"
  private val dwi = "sub-01/ses-task/dwi/sub-01_ses-task_dwi.nii"
  private val ap = "sub-01/ses-cal/fmap/sub-01_ses-cal_dir-AP_epi.nii"
  private val pa = "sub-01/ses-cal/fmap/sub-01_ses-cal_dir-PA_epi.nii"
  private val events = "task-motor_events.tsv"
  private val localized = bold.stripSuffix("_bold.nii") + "_events.tsv"
  private val eventText = "onset\tduration\ttrial_type\tprivate_note\r\n-1.000000000000000000001\t0\t\"a\tb\"\tsecret\r\n0\t1\tn/a\thidden\r\n"
  private val profile = DisclosureProfile(Set("age"), bids4s.EventTiming.Fields ++ Set("TaskName", "PhaseEncodingDirection",
    "TotalReadoutTime", "B0FieldSource", "B0FieldIdentifier", "IntendedFor"),
    Some(Set("onset", "duration", "trial_type")), Set("note"), Set("acq_time"))

  private def write(root: Path, name: String, text: String): Unit =
    val at = root.resolve(name)
    Files.createDirectories(at.getParent)
    val _ = Files.writeString(at, text)
  private def install(root: Path): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
    val source = root.resolve("source")
    Files.createDirectories(source)
    write(source, "dataset_description.json", """{"Name":"Private source","BIDSVersion":"1.11.2","DatasetType":"raw"}""")
    write(source, "participants.tsv", "participant_id\tage\tsecret\nsub-01\t27\thidden\nsub-02\t28\thidden\n")
    write(source, "participants.json", """{"age":{"Units":"year"},"secret":{"Description":"private dictionary"}}""")
    write(source, "task-motor_bold.json", """{"TaskName":"motor","RepetitionTime":1.5,"PrivateNote":"hidden"}""")
    write(source, events, eventText)
    write(source, "task-motor_events.json", """{"trial_type":{"Description":"approved category"},"private_note":{"Description":"hidden"}}""")
    Vector(bold, other, anat, dwi, ap, pa, "sub-02/func/sub-02_task-motor_bold.nii").foreach { name =>
      Files.createDirectories(source.resolve(name).getParent)
      val _ = Files.write(source.resolve(name), DwiTestFiles.nifti(volumes = if name == anat then 1 else 2))
    }
    write(source, bold.stripSuffix(".nii") + ".json", """{"B0FieldSource":"pair","Subset":"yes"}""")
    write(source, other.stripSuffix(".nii") + ".json", """{"B0FieldSource":"pair"}""")
    Vector(anat, dwi).foreach(n => write(source, n.stripSuffix(".nii") + ".json", """{"Subset":"yes"}"""))
    write(source, ap.stripSuffix(".nii") + ".json", s"""{"B0FieldIdentifier":["pair","unused"],"PhaseEncodingDirection":"j","TotalReadoutTime":0.05,"IntendedFor":["bids::$bold","bids::$other"]}""")
    write(source, pa.stripSuffix(".nii") + ".json", """{"B0FieldIdentifier":"pair","PhaseEncodingDirection":"j-","TotalReadoutTime":0.05}""")
    write(source, "dwi.bval", "0 1000\n")
    write(source, "dwi.bvec", "0 1\n0 0\n0 0\n")
    write(source, "sub-01/sub-01_sessions.tsv", "session_id\tnote\tsecret\nses-task\t\"task\tnote\"\thidden\nses-cal\tcalibration\thidden\nses-unused\tunused\thidden\n")
    write(source, "sub-01/sub-01_sessions.json", """{"note":{"Description":"approved session note"},"secret":{"Description":"hidden"}}""")
    write(source, "sub-01/ses-task/sub-01_ses-task_scans.tsv", "filename\tacq_time\tsecret\n" +
      Vector(bold, other, anat, dwi).map(_.stripPrefix("sub-01/ses-task/") + "\t2025-01-01T10:00:00\thidden\n").mkString)
    write(source, "sub-01/ses-task/sub-01_ses-task_scans.json", """{"acq_time":{"Description":"approved scan time"},"secret":{"Description":"hidden"}}""")
    write(source, "sub-01/ses-cal/sub-01_ses-cal_scans.tsv", "filename\tacq_time\tsecret\n" +
      Vector(ap, pa).map(_.stripPrefix("sub-01/ses-cal/") + "\t2025-01-01T09:00:00\thidden\n").mkString)
  }
  private def acceptSource(s: RepositorySystem, root: Path): IO[AcceptanceReceipt] =
    checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      .flatMap(v => checked(s.repository.accept(s.context, v.candidate.id, key("source"))))
  private def select(s: RepositorySystem, at: ReleaseRef): IO[FrozenSelection] =
    checked(s.catalog.select(s.context, Vector(at), ScientificQuery(artifacts = ArtifactPredicate.MetadataEquals("Subset", "yes"))))
  private def views(s: RepositorySystem): ScientificViews[IO] =
    new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, IdSource.random[IO])
  private def prepare(s: RepositorySystem, at: ReleaseRef, selection: FrozenSelection,
      mask: DisclosureProfile = profile): IO[Preview] =
    s.repository.create(s.context, s.dataset.custodian).flatMap(d => checked(views(s).prepare(s.context, at, selection.id, d.id, mask)))
  private def fileMap(r: Release): Map[String, BlobRef] = r.manifest.files.map(f => f.path.value -> f.blob).toMap
  private def checkMeaning(a: Assessment): Vector[(String, String, Map[String, MetadataValue])] =
    a.checks.map(c => (c.target.value, c.rule, c.facts - "timingMetadata"))

  test("MRI subset round trip preserves images, supported meaning and exact transformation evidence") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        sourceReceipt <- acceptSource(s, root)
        source <- checked(s.catalog.release(s.context, sourceReceipt.release))
        selection <- select(s, source.ref)
        preview <- prepare(s, source.ref, selection)
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        accepted <- checked(s.repository.accept(s.context, preview.candidate.id, key("subset")))
        subset <- checked(s.catalog.release(s.context, accepted.release))
        _ = assertEquals(subset.activity.projection.get.compiler, "sanctity-mri-view-v8:" + source.interpretation.profile.context.get.id.value)
        receipt = subset.activity.projection.get.receipt.get
        _ = assert(receipt.matches(subset.manifest))
        _ = assertEquals(receipt.sourceContent, source.manifest.id)
        _ = assertEquals(receipt.sourceInterpretation, source.interpretation.id)
        _ = assertEquals(receipt.files.map(_.step.output).toSet, subset.manifest.files.map(_.path).toSet)
        _ = Vector(bold, anat, dwi, ap, pa).foreach(n => assertEquals(fileMap(subset)(n), fileMap(source)(n)))
        _ = assertEquals(fileMap(subset)(dwi.stripSuffix(".nii") + ".bval"), fileMap(source)("dwi.bval"))
        _ = assertEquals(fileMap(subset)(dwi.stripSuffix(".nii") + ".bvec"), fileMap(source)("dwi.bvec"))
        _ = assert(!fileMap(subset).contains(other))
        _ = assert(!fileMap(subset).keys.exists(_.contains("sub-02")))
        _ = assert(!fileMap(subset).contains(events))
        links = subset.interpretation.relationships.get.relationships
        _ = assert(links.exists(r => r.from == p(bold) && r.kind == "bids.b0-field-source" && r.to.toSet == Set(p(ap), p(pa))))
        _ = assert(links.exists(r => r.from == p(bold) && r.kind == "bids.task-events" && r.to == Vector(p(localized))))
        _ = assertEquals(subset.interpretation.byPath(p(bold)).fields("RepetitionTime").value,
          source.interpretation.byPath(p(bold)).fields("RepetitionTime").value)
        _ = assertEquals(checkMeaning(subset.assessment), checkMeaning(source.assessment).filter(x => Set(bold, anat, dwi, ap, pa)(x._1)).map { (target, rule, facts) =>
          (target, rule, if target == bold && facts.contains("columns") then facts.updated("columns", MetadataValue.Array(Vector("onset", "duration", "trial_type").map(MetadataValue.Text.apply))) else facts)
        })
        _ <- checked(s.repository.exportFull(s.context, subset.ref, new DirectoryExport[IO](root.resolve("subset"))))
        _ <- IO.blocking {
          val out = root.resolve("subset")
          val event = Files.readString(out.resolve(localized))
          assert(event.contains("-1.000000000000000000001"))
          assert(event.contains("\"a\tb\""))
          assert(!event.contains("private_note"))
          assertEquals(Files.readString(out.resolve("participants.tsv")), "participant_id\tage\nsub-01\t27\n")
          assertEquals(Files.readString(out.resolve("sub-01/sub-01_sessions.tsv")),
            "session_id\tnote\nses-task\t\"task\tnote\"\nses-cal\tcalibration\n")
          val scans = Files.readString(out.resolve("sub-01/ses-task/sub-01_ses-task_scans.tsv"))
          assert(!scans.contains("run-2"))
          assert(!scans.contains("secret"))
          val fieldmap = Files.readString(out.resolve(ap.stripSuffix(".nii") + ".json"))
          assert(fieldmap.contains("bids::" + bold))
          assert(!fieldmap.contains(other))
          assert(!fieldmap.contains("unused"))
          assert(Files.readString(out.resolve("participants.json")).contains("year"))
          assert(!Files.readString(out.resolve("participants.json")).contains("secret"))
        }
        destination <- s.repository.create(s.context, s.dataset.custodian)
        again <- checked(s.repository.importNative(s.context, destination.id, new NativeDirectorySource[IO](root.resolve("subset"))))
        againReceipt <- checked(s.repository.accept(s.context, again.candidate.id, key("reimport")))
        roundtrip <- checked(s.catalog.release(s.context, againReceipt.release))
        _ = assertEquals(fileMap(roundtrip), fileMap(subset))
        _ = assertEquals(roundtrip.interpretation.artifacts, subset.interpretation.artifacts)
        _ = assertEquals(roundtrip.interpretation.relationships.get.relationships, links)
        _ = assertEquals(checkMeaning(roundtrip.assessment), checkMeaning(subset.assessment))
        _ = assertEquals(roundtrip.assessment.findings, subset.assessment.findings)
      yield ()
    }
  }

  test("one inherited event table localizes twice with exact bytes and distinct artifact identities") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- acceptSource(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(source.release), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
          ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
        preview <- prepare(s, source.release, selection, profile.copy(eventFields = Some(Set("onset", "duration", "trial_type", "private_note"))))
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("shared-events")))
        result <- checked(s.catalog.release(s.context, receipt.release))
        copies = result.manifest.files.filter(_.path.fileName.endsWith("events.tsv"))
        _ = assertEquals(copies.size, 2)
        _ = assertEquals(copies.map(_.artifact).distinct.size, 2)
        _ = assertEquals(copies.map(_.blob.digest).distinct, Vector(Digest.bytes(eventText.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        _ = assertEquals(result.activity.projection.get.receipt.get.files.filter(_.step.output.fileName.endsWith("events.tsv"))
          .map(_.step.inputs.head.path).distinct, Vector(p(events)))
      yield ()
    }
  }

  test("subset masks cannot omit required timing, fieldmap metadata or event columns") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- acceptSource(s, root)
        selection <- select(s, source.release)
        target <- s.repository.create(s.context, s.dataset.custodian)
        _ <- Vector(profile.copy(eventFields = None), profile.copy(eventFields = Some(Set("onset"))),
          profile.copy(metadataFields = profile.metadataFields - "RepetitionTime"),
          profile.copy(metadataFields = profile.metadataFields - "PhaseEncodingDirection")).traverse_ { mask =>
            views(s).prepare(s.context, source.release, selection.id, target.id, mask).map(r =>
              assert(r.left.toOption.exists(_.isInstanceOf[Problem.Unsupported])))
          }
        unchanged <- checked(s.catalog.dataset(s.context, target.id))
        _ = assertEquals(unchanged.head, None)
      yield ()
    }
  }

  test("missing stimulus assets block admission before a masked subset can be prepared") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root.resolve("source"), events, "onset\tduration\tstim_file\n0\t1\timage.png\n"))
        preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "StimulusMissingFile"))
        result <- s.repository.accept(s.context, preview.candidate.id, key("missing-stimulus"))
        _ = assert(result.isLeft)
      yield ()
    }
  }

  test("summary projection refuses duplicate keys, missing selected rows and unsafe scan paths") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- acceptSource(s, root)
        release <- checked(s.catalog.release(s.context, source.release))
        selection <- select(s, source.release)
        compiler = new MriViewCompiler
        paths <- Checked.lift[IO, Vector[RelPath]](compiler.documents(release, selection, profile))
        documents <- paths.traverse(p => s.store.read(release.manifest.byPath(p).asInstanceOf[Entry.File].blob)
          .through(fs2.text.utf8.decode).compile.string.map(p -> _)).map(_.toMap)
        scan = p("sub-01/ses-task/sub-01_ses-task_scans.tsv")
        _ = Vector("filename\tacq_time\n../outside.nii\tn/a\n", "filename\tacq_time\n", "filename\tacq_time\na.nii\tn/a\na.nii\tn/a\n")
          .foreach(text => assert(compiler.plan(release, selection, profile, documents.updated(scan, text)).isLeft))
        plan <- Checked.lift[IO, ProjectionPlan](compiler.plan(release, selection, profile, documents))
        _ = assertEquals(plan.decisions.find(_.path == p(bold)).get.disposition, "selected")
        _ = assertEquals(plan.decisions.find(_.path == p(ap)).get.disposition, "dependency")
        _ = assertEquals(plan.decisions.find(_.path == p(other)).get.disposition, "excluded")
        _ = assert(plan.steps.find(_.output == p(localized)).get.inputs.exists(_.path == p(events)))
      yield ()
    }
  }

  test("hidden event edits and later source releases leave accepted subset bytes and receipts intact") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- acceptSource(s, root)
        selection <- select(s, source.release)
        first <- prepare(s, source.release, selection)
        accepted <- checked(s.repository.accept(s.context, first.candidate.id, key("first-view")))
        old <- checked(s.catalog.release(s.context, accepted.release))
        edit <- checked(s.repository.correctCompanion(s.context, source.release, p(events), eventText.replace("secret", "changed"), "Change excluded values"))
        next <- checked(s.repository.accept(s.context, edit.candidate.id, key("changed-source")))
        selected <- select(s, next.release)
        second <- prepare(s, next.release, selected)
        _ = assertEquals(second.candidate.manifest.files.map(f => f.path -> f.blob).toMap, old.manifest.files.map(f => f.path -> f.blob).toMap)
        retained <- checked(s.catalog.release(s.context, accepted.release))
        _ = assertEquals(retained.activity.projection, old.activity.projection)
        _ = assertEquals(retained.manifest.id, old.manifest.id)
        _ = assertEquals(retained.activity.projection.get.source, source.release)
        _ = assertNotEquals(second.candidate.activity.projection.get.source, source.release)
      yield ()
    }
  }

  test("variable acquisition timing survives subset materialization without an invented TR") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root.resolve("source"), "task-motor_bold.json",
          """{"TaskName":"motor","VolumeTiming":[5,6.5],"FrameAcquisitionDuration":0.5}"""))
        source <- acceptSource(s, root)
        selection <- select(s, source.release)
        view <- prepare(s, source.release, selection)
        metadata = view.candidate.interpretation.byPath(p(bold)).fields
        _ = assert(!metadata.contains("RepetitionTime"))
        check = view.candidate.assessment.checks.find(c => c.target == p(bold) && c.rule.startsWith(bids4s.CheckedEvents.Version)).get
        _ = assertEquals(check.facts("timingMode"), MetadataValue.Text("Variable"))
        _ = assertEquals(check.facts("comparisonEndSeconds"), MetadataValue.Number(2))
        _ = assert(view.candidate.assessment.admissible)
      yield ()
    }
  }

  test("subset document acquisition reads shared tables once and releases resources on cancellation") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- acceptSource(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(source.release), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
          ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
        original <- checked(s.catalog.release(s.context, source.release))
        eventBlob = fileMap(original)(events)
        counts <- Ref.of[IO, Map[Digest, Int]](Map.empty)
        active <- Ref.of[IO, Int](0)
        block <- Ref.of[IO, Boolean](false)
        started <- Deferred[IO, Unit]
        tracked = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
          def read(blob: BlobRef): Stream[IO, Byte] = Stream.resource(Resource.make(
            counts.update(m => m.updated(blob.digest, m.getOrElse(blob.digest, 0) + 1)) *> active.update(_ + 1))(_ => active.update(_ - 1))) >>
            Stream.eval(block.get).flatMap { wait =>
              if wait && blob == eventBlob then Stream.eval(started.complete(())).drain ++ Stream.never[IO]
              else s.store.read(blob)
            }
        compiler = new ScientificViews[IO](s.catalog, tracked, s.repository, new MriViewCompiler, IdSource.random[IO])
        firstTarget <- s.repository.create(s.context, s.dataset.custodian)
        _ <- checked(compiler.prepare(s.context, source.release, selection.id, firstTarget.id, profile))
        readCounts <- counts.get
        _ = assertEquals(readCounts(eventBlob.digest), 1)
        closed <- active.get
        _ = assertEquals(closed, 0)
        _ <- block.set(true)
        target <- s.repository.create(s.context, s.dataset.custodian)
        fiber <- compiler.prepare(s.context, source.release, selection.id, target.id, profile).start
        _ <- started.get.timeout(scala.concurrent.duration.DurationInt(10).seconds)
        _ <- fiber.cancel
        after <- active.get
        _ = assertEquals(after, 0)
        state <- checked(s.catalog.dataset(s.context, target.id))
        _ = assertEquals(state.head, None)
      yield ()
    }
  }

  test("oversized summary inputs fail before any source-document stream is acquired") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root.resolve("source"), "sub-01/ses-task/sub-01_ses-task_scans.tsv", "x" * (4 * 1024 * 1024 + 1)))
        source <- acceptSource(s, root)
        selection <- select(s, source.release)
        reads <- Ref.of[IO, Int](0)
        tracked = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
          def read(blob: BlobRef): Stream[IO, Byte] = Stream.eval(reads.update(_ + 1)).drain ++ s.store.read(blob)
        target <- s.repository.create(s.context, s.dataset.custodian)
        result <- new ScientificViews[IO](s.catalog, tracked, s.repository, new MriViewCompiler, IdSource.random[IO])
          .prepare(s.context, source.release, selection.id, target.id, profile)
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.LimitExceeded]))
        count <- reads.get
        _ = assertEquals(count, 0)
      yield ()
    }
  }

  test("EPI gradient dependencies outside this profile cannot be silently dropped") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root.resolve("source"), "epi.bval", "0 0\n"))
        source <- acceptSource(s, root)
        selection <- select(s, source.release)
        target <- s.repository.create(s.context, s.dataset.custodian)
        result <- views(s).prepare(s.context, source.release, selection.id, target.id, profile)
        _ = assert(result.left.toOption.exists(_.message.contains("EPI gradients")))
      yield ()
    }
  }

  test("subset projection cannot silently change participant values interpreted by the native profile") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root.resolve("source"), "participants.tsv", "participant_id\tage\nsub-01\t\"27\"\nsub-02\t28\n"))
        source <- acceptSource(s, root)
        selection <- select(s, source.release)
        target <- s.repository.create(s.context, s.dataset.custodian)
        result <- views(s).prepare(s.context, source.release, selection.id, target.id, profile)
        _ = assert(result.left.toOption.exists(_.message.contains("participant values differ")))
      yield ()
    }
  }

  test("ordinary DWI suffix selections may include local gradients without treating them as acquisition roots") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.move(source.resolve("dwi.bval"), source.resolve(dwi.stripSuffix(".nii") + ".bval"))
          val _ = Files.move(source.resolve("dwi.bvec"), source.resolve(dwi.stripSuffix(".nii") + ".bvec"))
        }
        source <- acceptSource(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(source.release), ScientificQuery(artifacts = ArtifactPredicate.Suffix("dwi"))))
        _ = assertEquals(selection.hits.size, 3)
        subset <- prepare(s, source.release, selection)
        _ = assert(subset.candidate.assessment.admissible)
        _ = assertEquals(subset.candidate.manifest.files.count(_.path.fileName.endsWith(".bval")), 1)
        _ = assertEquals(subset.candidate.manifest.files.count(_.path.fileName.endsWith(".bvec")), 1)
        _ = assertEquals(subset.candidate.manifest.files.count(_.path.fileName.endsWith(".nii")), 1)
      yield ()
    }
  }
