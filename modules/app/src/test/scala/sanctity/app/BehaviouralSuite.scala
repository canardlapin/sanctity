package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The same bounded behavioural journeys run against the reference and durable catalogs. */
abstract class CatalogBehaviouralSuite extends FunSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private val first = "sub-01/ses-a/beh/sub-01_ses-a_task-choice_run-01_beh.tsv"
  private val second = "sub-01/ses-a/beh/sub-01_ses-a_task-choice_run-02_beh.tsv"
  private val other = "sub-02/ses-b/beh/sub-02_ses-b_task-choice_run-01_beh.tsv"
  private val events = "task-choice_events.tsv"
  private val text = "trial\tresponse_time\tstim_file\tprivate_note\nleft\t-0.125000000000000000001\timages/cat.txt\tsecret\nright\tn/a\tn/a\thidden\n"
  private val mask = DisclosureProfile(Set("age"), Set("TaskName", "License"), Some(Set("onset", "duration", "trial_type")),
    Set("note"), Set("acq_time"), Some(Set("trial", "response_time", "stim_file")))
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def repo(s: RepositorySystem): NativeRepository[IO] =
    new NativeRepository[IO](s.catalog, s.store, new RepositoryBidsInterpreter[IO](s.store), IdSource.random[IO])
  private def write(root: Path, path: String, text: String): Unit =
    val at = root.resolve(path)
    Files.createDirectories(at.getParent)
    val _ = Files.writeString(at, text)
  private def install(root: Path, companion: Boolean = true): IO[Unit] =
    IO.blocking(Files.exists(root.resolve("source"))).flatMap(exists => if exists then TestFiles.remove(root.resolve("source")) else IO.unit) *> IO.blocking {
      val at = root.resolve("source")
      write(at, "dataset_description.json", """{"Name":"Private behavioural study","BIDSVersion":"1.11.2","DatasetType":"raw","License":"CC0"}""")
      write(at, "participants.tsv", "participant_id\tage\tprivate_note\nsub-01\t27\thidden\nsub-02\t28\thidden\n")
      write(at, "participants.json", """{"age":{"Units":"year"},"private_note":{"Description":"secret"}}""")
      write(at, "task-choice_beh.json", """{"TaskName":"choice","trial":{"Description":"response category"},"response_time":{"Units":"s"},"private_note":{"Description":"secret dictionary"}}""")
      Vector(first, second, other).foreach(n => write(at, n, text))
      write(at, first.stripSuffix(".tsv") + ".json", """{"TaskName":"local choice"}""")
      write(at, "stimuli/images/cat.txt", "permitted synthetic stimulus\n")
      write(at, "stimuli/unrelated.txt", "not selected\n")
      write(at, "sub-01/sub-01_sessions.tsv", "session_id\tnote\n ses-a\twrong\n".replace(" ses-a", "ses-a"))
      write(at, "sub-01/ses-a/sub-01_ses-a_scans.tsv", "filename\tacq_time\nbeh/" + p(first).fileName + "\tn/a\nbeh/" + p(second).fileName + "\tn/a\n")
      if companion then
        write(at, events, "onset\tduration\ttrial_type\tprivate_note\n-0.1\t0\tleft\tsecret\n0\t1\tright\thidden\n")
        write(at, "task-choice_events.json", """{"trial_type":{"Description":"category"},"private_note":{"Description":"secret"}}""")
    }
  private def imported(s: RepositorySystem, root: Path): IO[Preview] =
    checked(repo(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
  private def accepted(s: RepositorySystem, root: Path): IO[Release] =
    for
      preview <- imported(s, root)
      _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
      receipt <- checked(repo(s).accept(s.context, preview.candidate.id, key("initial-behaviour")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def select(s: RepositorySystem, r: Release): IO[FrozenSelection] =
    checked(s.catalog.select(s.context, Vector(r.ref), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
      ArtifactPredicate.Suffix("beh"), ArtifactPredicate.Entity("sub", "01"), ArtifactPredicate.Entity("run", "01"))))))
  private def views(s: RepositorySystem): ScientificViews[IO] =
    new ScientificViews[IO](s.catalog, s.store, repo(s), new BehaviouralViewCompiler, IdSource.random[IO])

  test("standalone behaviour without event timing accepts and preserves effective metadata and exact bytes") {
    fixture { (s, root) =>
      for
        _ <- install(root, false)
        release <- accepted(s, root)
        _ = assertEquals(release.interpretation.byPath(p(first)).fields("TaskName").value, MetadataValue.Text("local choice"))
        _ = assertEquals(release.interpretation.byPath(p(second)).fields("TaskName").origin, p("task-choice_beh.json"))
        _ = assertEquals(release.assessment.checks.count(_.rule == BehaviouralChecks.Rule), 3)
        _ = assert(release.assessment.checks.filter(_.rule == BehaviouralChecks.Rule).forall(_.facts("timingBasis") == MetadataValue.Text("unavailable; no recording clock inferred")))
        _ <- checked(repo(s).exportFull(s.context, release.ref, new DirectoryExport[IO](root.resolve("full"))))
        _ <- IO.blocking(release.manifest.files.foreach(f => assertEquals(Files.mismatch(root.resolve("source").resolve(f.path.value), root.resolve("full").resolve(f.path.value)), -1L)))
      yield ()
    }
  }

  test("inherited events and both behavioural and event stimuli have deterministic evidence") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root.resolve("source"), events, "onset\tduration\tstim_file\n0\t1\timages/cat.txt\n"))
        release <- accepted(s, root)
        links = release.interpretation.relationships.get.relationships
        _ = assertEquals(links.count(_.kind == BehaviouralChecks.EventsKind), 3)
        _ = assert(links.exists(r => r.from == p(events) && r.kind == BehaviouralChecks.StimulusKind))
        closure <- Checked.lift[IO, RelationshipClosure](release.interpretation.relationshipClosure(Vector(p(first))))
        _ = assertEquals(closure.artifacts.toSet, Set(p(first), p(events), p("stimuli/images/cat.txt")))
        _ = assert(!closure.artifacts.contains(p(second)))
      yield ()
    }
  }

  test("behavioural subset roundtrips exact meaning, lexical values and receipt-bound disclosure") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- accepted(s, root)
        selection <- select(s, source)
        destination <- repo(s).create(s.context, s.dataset.custodian)
        preview <- checked(views(s).prepare(s.context, source.ref, selection.id, destination.id, mask))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        receipt <- checked(repo(s).accept(s.context, preview.candidate.id, key("behaviour-view")))
        release <- checked(s.catalog.release(s.context, receipt.release))
        _ = assert(release.activity.projection.get.receipt.get.matches(release.manifest))
        _ = assert(!release.manifest.files.exists(f => f.path == p(second) || f.path == p(other) || f.path == p(events) || f.path == p("stimuli/unrelated.txt")))
        _ <- checked(repo(s).exportFull(s.context, release.ref, new DirectoryExport[IO](root.resolve("subset"))))
        _ <- IO.blocking {
          val table = Files.readString(root.resolve("subset").resolve(first))
          assert(table.contains("-0.125000000000000000001"))
          assert(!table.contains("secret"))
          assertEquals(Files.readString(root.resolve("subset/participants.tsv")), "participant_id\tage\nsub-01\t27\n")
          assert(!Files.readString(root.resolve("subset").resolve(first.stripSuffix(".tsv") + ".json")).contains("private_note"))
        }
        again <- repo(s).create(s.context, s.dataset.custodian)
        imported <- checked(repo(s).importNative(s.context, again.id, new NativeDirectorySource[IO](root.resolve("subset"))))
        _ = assert(imported.candidate.assessment.admissible, imported.candidate.assessment.errors.toString)
        _ = assertEquals(imported.candidate.interpretation.artifacts, release.interpretation.artifacts)
        _ = assertEquals(imported.candidate.interpretation.relationships.get.relationships, release.interpretation.relationships.get.relationships)
        _ = assertEquals(imported.candidate.assessment.checks.map(c => c.target -> c.facts), release.assessment.checks.map(c => c.target -> c.facts))
      yield ()
    }
  }

  test("shared event correction and behavioural table correction preserve frozen history") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        old <- accepted(s, root)
        frozen <- select(s, old)
        correction <- checked(repo(s).correctCompanion(s.context, old.ref, p(events), "onset\tduration\n0\t0\n", "fix shared events"))
        _ = Vector(first, second, other).foreach(n => assert(correction.dependencyImpact.after.contains(p(n))))
        nextReceipt <- checked(repo(s).accept(s.context, correction.candidate.id, key("fix-events")))
        table <- checked(repo(s).correctCompanion(s.context, nextReceipt.release, p(first), text.replace("left", "right"), "fix response"))
        _ <- checked(repo(s).accept(s.context, table.candidate.id, key("fix-response")))
        previous <- checked(s.catalog.release(s.context, old.ref))
        membership <- checked(s.catalog.selection(s.context, frozen.id))
        _ = assertEquals(previous.interpretation.id, old.interpretation.id)
        _ = assertEquals(previous.assessment.id, old.assessment.id)
        _ = assertEquals(membership.hits, frozen.hits)
      yield ()
    }
  }

  test("bad behavioural correction cannot advance the accepted head") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        old <- accepted(s, root)
        bad <- checked(repo(s).correctCompanion(s.context, old.ref, p(first), "trial\tresponse_time\nleft\tNaN\n", "invalid response"))
        _ = assert(bad.candidate.assessment.errors.exists(_.code == "BehaviouralResponseTime"))
        denied <- repo(s).accept(s.context, bad.candidate.id, key("bad-response"))
        _ = assert(denied.isLeft)
        dataset <- checked(s.catalog.dataset(s.context, old.ref.dataset))
        _ = assertEquals(dataset.head, Some(old.ref.release))
      yield ()
    }
  }

  test("behavioural view requires separate column and corresponding-event disclosure") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- accepted(s, root)
        selection <- select(s, source)
        _ = Vector(mask.copy(behaviouralFields = None), mask.copy(eventFields = None), mask.copy(eventFields = Some(Set("onset"))),
          mask.copy(behaviouralFields = Some(Set("absent")))).foreach { invalid =>
            val compiler = new BehaviouralViewCompiler
            val pre = compiler.documents(source, selection, invalid)
            if invalid.behaviouralFields.contains(Set("absent")) then assert(pre.isRight) else assert(pre.isLeft)
          }
        target <- repo(s).create(s.context, s.dataset.custodian)
        missing <- views(s).prepare(s.context, source.ref, selection.id, target.id, mask.copy(behaviouralFields = Some(Set("absent"))))
        _ = assert(missing.isLeft)
      yield ()
    }
  }

  test("dropping stimulus column drops unreferenced stimulus bytes and dictionaries") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- accepted(s, root)
        selection <- select(s, source)
        target <- repo(s).create(s.context, s.dataset.custodian)
        preview <- checked(views(s).prepare(s.context, source.ref, selection.id, target.id,
          mask.copy(behaviouralFields = Some(Set("trial", "response_time")))))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        _ = assert(!preview.candidate.manifest.files.exists(_.path.value.startsWith("stimuli/")))
      yield ()
    }
  }

  test("revoked fixed-release access prevents frozen selection retrieval and scientific view acceptance") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- accepted(s, root)
        principal <- IdSource.random[IO].next[PrincipalTag]
        guest = AccessContext(principal)
        grant <- checked(s.catalog.grant(s.context, source.ref.dataset, principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
        selection <- checked(s.catalog.select(guest, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.Suffix("beh"))))
        target <- repo(s).create(guest, s.dataset.custodian)
        preview <- checked(views(s).preparePrivate(guest, source.ref, selection.id, target.id, mask))
        _ <- checked(s.catalog.revoke(s.context, source.ref.dataset, grant.id))
        denied <- repo(s).accept(guest, preview.candidate.id, key("revoked-view"))
        frozen <- s.catalog.selection(guest, selection.id)
        _ = assertEquals(denied, Left(Problem.Denied))
        _ = assertEquals(frozen, Left(Problem.Denied))
      yield ()
    }
  }

  test("stimulus move repairs behavioural references atomically and rejects dangling removal") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- accepted(s, root)
        changes = new ScientificChanges[IO](s.catalog, s.store, repo(s), new BidsChangeCompiler(RepositoryBidsContext.Behavioural))
        edits = Vector(FileEdit.Move(p("stimuli/images/cat.txt"), p("stimuli/images/dog.txt")))
        plan <- checked(changes.plan(s.context, source.ref, edits))
        _ = assertEquals(plan.repairs.count(_.rule == "bids.stim-file.v1"), 3)
        preview <- checked(changes.prepare(s.context, source.ref, edits, plan.id, "rename stimulus"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        receipt <- checked(repo(s).accept(s.context, preview.candidate.id, key("stimulus-move")))
        denied <- changes.plan(s.context, receipt.release, Vector(FileEdit.Remove(p("stimuli/images/dog.txt"))))
        _ = assert(denied.isLeft)
      yield ()
    }
  }

  test("a behavioural rename cannot lose its local metadata or inherited event applicability") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- accepted(s, root)
        changes = new ScientificChanges[IO](s.catalog, s.store, repo(s), new BidsChangeCompiler(RepositoryBidsContext.Behavioural))
        next = p(first.replace("run-01", "run-03"))
        partial <- changes.plan(s.context, source.ref, Vector(FileEdit.Move(p(first), next)))
        _ = assert(partial.isLeft)
        edits = Vector(FileEdit.Move(p(first), next), FileEdit.Move(p(first.stripSuffix(".tsv") + ".json"), p(next.value.stripSuffix(".tsv") + ".json")))
        plan <- checked(changes.plan(s.context, source.ref, edits))
        preview <- checked(changes.prepare(s.context, source.ref, edits, plan.id, "correct run label"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        _ = assert(preview.candidate.manifest.byPath.contains(next))
        _ = assert(preview.candidate.manifest.byPath(next).asInstanceOf[Entry.File].blob == source.manifest.byPath(p(first)).asInstanceOf[Entry.File].blob)
      yield ()
    }
  }

  test("malformed, misplaced, orphaned and unsupported modalities are explicit admission blockers") {
    fixture { (s, root) =>
      val cases = Vector(
        first -> "trial\tresponse_time\nleft\tNaN\n",
        first -> "trial\ttrial\nleft\tright\n",
        "sub-01/ses-a/beh/sub-01_ses-a_task-choice_events.tsv" -> "onset\tduration\n0\t-1\n",
        "sub-01/ses-a/beh/sub-99_ses-a_task-choice_beh.tsv" -> "trial\nleft\n",
        "sub-01/eeg/sub-01_task-choice_eeg.edf" -> "not a qualified EEG recording"
      )
      cases.traverse_ { (at, value) =>
        for
          _ <- install(root, false)
          _ <- IO.blocking(write(root.resolve("source"), at, value))
          preview <- imported(s, root)
          _ = assert(!preview.candidate.assessment.admissible, at)
        yield ()
      }
    }
  }

  test("orphan events and escaping or missing stimulus references cannot be admitted") {
    fixture { (s, root) =>
      for
        _ <- install(root, false)
        _ <- IO.blocking {
          write(root.resolve("source"), "sub-01/ses-a/beh/sub-01_ses-a_task-other_events.tsv", "onset\tduration\n0\t1\n")
          write(root.resolve("source"), first, text.replace("images/cat.txt", "../private.txt"))
        }
        preview <- imported(s, root)
        codes = preview.candidate.assessment.errors.map(_.code).toSet
        _ = assert(codes("BehaviouralEventsOrphan"))
        _ = assert(codes("BehaviouralStimulusUnsafePath"))
      yield ()
    }
  }

  test("same-directory event ambiguity is reported without silently selecting the more specific filename") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking {
          write(root.resolve("source"), "sub-01/ses-a/beh/sub-01_ses-a_task-choice_events.tsv", "onset\tduration\n0\t1\n")
          write(root.resolve("source"), first.stripSuffix("_beh.tsv") + "_events.tsv", "onset\tduration\n0\t1\n")
        }
        preview <- imported(s, root)
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "BehaviouralEventsAmbiguous"))
        repeat <- new RepositoryBidsInterpreter[IO](s.store).prepare(preview.candidate.manifest)
        _ = assertEquals(repeat._1.id, preview.candidate.interpretation.id)
        _ = assertEquals(repeat._2.id, preview.candidate.assessment.id)
      yield ()
    }
  }

  test("column dictionary errors bind their exact inherited source") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        old <- accepted(s, root)
        bad <- checked(repo(s).correctMetadata(s.context, old.ref, p("task-choice_beh.json"),
          """{"TaskName":"choice","response_time":12}""", "invalid dictionary"))
        _ = assert(bad.candidate.assessment.errors.exists(f => f.code == "BehaviouralColumnMetadata" && f.path.contains(p("task-choice_beh.json"))))
        _ = assert(bad.candidate.assessment.checks.filter(_.target.fileName.endsWith("_beh.tsv"))
          .forall(_.inputs.exists(_.path == p("task-choice_beh.json"))))
      yield ()
    }
  }

  test("acquired CC0 response excerpt preserves source bytes, dependencies and opaque HED definitions") {
    fixture { (s, root) =>
      val resource = Path.of(getClass.getResource("/multimodal-conformance/face-behavioural/dataset").toURI)
      val primary = "sub-002/ses-1/beh/sub-002_ses-1_task-FaceRecognition_beh.tsv"
      val disclosure = DisclosureProfile(Set.empty, Set("License", "HEDVersion"),
        behaviouralFields = Some(Set("response_time", "button_press", "stim_file")))
      for
        _ <- IO.blocking(Files.exists(root.resolve("source"))).flatMap(exists => if exists then TestFiles.remove(root.resolve("source")) else IO.unit)
        _ <- IO.blocking {
          val walk = Files.walk(resource)
          try walk.iterator().asScala.foreach { from =>
            val to = root.resolve("source").resolve(resource.relativize(from))
            if Files.isDirectory(from) then Files.createDirectories(to) else { Files.createDirectories(to.getParent); Files.copy(from, to) }
          }
          finally walk.close()
        }
        source <- accepted(s, root)
        _ = assertEquals(source.interpretation.declaredBidsVersion, Some("1.11.1"))
        _ = assertEquals(source.assessment.checks.find(_.target == p(primary)).get.facts("rowCount"), MetadataValue.Number(8))
        selection <- checked(s.catalog.select(s.context, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.Suffix("beh"))))
        target <- repo(s).create(s.context, s.dataset.custodian)
        forbidden <- views(s).prepare(s.context, source.ref, selection.id, target.id, disclosure.copy(metadataFields = Set("License")))
        _ = assert(forbidden.isLeft)
        subset <- checked(views(s).prepare(s.context, source.ref, selection.id, target.id, disclosure))
        _ = assert(subset.candidate.assessment.admissible, subset.candidate.assessment.errors.toString)
        receipt <- checked(repo(s).accept(s.context, subset.candidate.id, key("acquired-behaviour")))
        view <- checked(s.catalog.release(s.context, receipt.release))
        _ = assertEquals(view.manifest.byPath(p(primary)).asInstanceOf[Entry.File].blob, source.manifest.byPath(p(primary)).asInstanceOf[Entry.File].blob)
        _ = assertEquals(view.interpretation.byPath(p(primary)).fields("button_press").value, source.interpretation.byPath(p(primary)).fields("button_press").value)
        _ = assertEquals(view.manifest.files.count(_.path.value.startsWith("stimuli/")), 8)
        _ <- checked(repo(s).exportFull(s.context, view.ref, new DirectoryExport[IO](root.resolve("acquired"))))
        _ <- IO.blocking {
          assert(Files.readString(root.resolve("acquired/dataset_description.json")).contains("8.4.0"))
          assertEquals(Files.readString(root.resolve("acquired").resolve(primary)).split("\r?\n")(1), "1.553\t3\tf123.bmp")
        }
      yield ()
    }
  }

final class BehaviouralSuite extends CatalogBehaviouralSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-behavioural-test-")))(TestFiles.remove)
      .use(root => ReferenceSystem.create(root.resolve("storage")).flatMap(s => run(s, root))).unsafeRunSync()
