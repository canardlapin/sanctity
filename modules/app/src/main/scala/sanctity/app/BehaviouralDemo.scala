package sanctity.app

import cats.effect.{IO, IOApp}
import io.circe.Json
import sanctity.application.{Checked, IdSource, NativeRepository, ScientificViews}
import sanctity.bids.{BehaviouralViewCompiler, RepositoryBidsInterpreter}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** A retained synthetic behavioural journey; run the Python checker over its exported directories. */
object BehaviouralDemo extends IOApp.Simple:
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  def run: IO[Unit] = for
    root <- IO.blocking(Files.createTempDirectory("sanctity-behavioural-demo-"))
    _ <- execute(root)
    _ <- IO.println(root.toString)
  yield ()

  def execute(root: Path): IO[Unit] =
    val table = "sub-01/beh/sub-01_task-choice_beh.tsv"
    val event = "sub-01/beh/sub-01_task-choice_events.tsv"
    def write(name: String, content: String): Unit =
      val target = root.resolve("source").resolve(name)
      Files.createDirectories(target.getParent)
      val _ = Files.writeString(target, content)
    for
      _ <- IO.blocking {
        write("dataset_description.json", """{"Name":"Synthetic behavioural study","BIDSVersion":"1.11.2","DatasetType":"raw","License":"CC0"}""")
        write("participants.tsv", "participant_id\tage\tprivate_note\nsub-01\t27\tsecret\n")
        write("participants.json", """{"age":{"Units":"year"},"private_note":{"Description":"hidden"}}""")
        write(table, "trial\tresponse_time\tstim_file\tprivate_note\nleft\t0.125000000000000000001\tcat.txt\tsecret\nright\tn/a\tn/a\thidden\n")
        write(table.stripSuffix(".tsv") + ".json", """{"TaskName":"choice","trial":{"Description":"response category"},"response_time":{"Units":"s"},"private_note":{"Description":"hidden"}}""")
        write(event, "onset\tduration\ttrial_type\n-0.1\t0\tleft\n0\t1\tright\n")
        write(event.stripSuffix(".tsv") + ".json", """{"trial_type":{"Description":"choice"}}""")
        write("stimuli/cat.txt", "synthetic stimulus\n")
      }
      system <- ReferenceSystem.create(root.resolve("storage"))
      ids = IdSource.random[IO]
      repository = new NativeRepository[IO](system.catalog, system.store, new RepositoryBidsInterpreter[IO](system.store), ids)
      preview <- checked(repository.importNative(system.context, system.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      accepted <- checked(repository.accept(system.context, preview.candidate.id, key("behavioural-source")))
      source <- checked(system.catalog.release(system.context, accepted.release))
      selection <- checked(system.catalog.select(system.context, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.Suffix("beh"))))
      destination <- repository.create(system.context, system.dataset.custodian)
      mask = DisclosureProfile(Set("age"), Set("TaskName", "License"), Some(Set("onset", "duration", "trial_type")),
        behaviouralFields = Some(Set("trial", "response_time", "stim_file")))
      view <- checked(new ScientificViews[IO](system.catalog, system.store, repository, new BehaviouralViewCompiler, ids)
        .prepare(system.context, source.ref, selection.id, destination.id, mask))
      subset <- checked(repository.accept(system.context, view.candidate.id, key("behavioural-subset")))
      _ <- checked(repository.exportFull(system.context, source.ref, new DirectoryExport[IO](root.resolve("full"))))
      _ <- checked(repository.exportFull(system.context, subset.release, new DirectoryExport[IO](root.resolve("subset"))))
      target <- repository.create(system.context, system.dataset.custodian)
      again <- checked(repository.importNative(system.context, target.id, new NativeDirectorySource[IO](root.resolve("subset"))))
      _ <- checked(repository.accept(system.context, again.candidate.id, key("behavioural-reimport")))
      _ <- IO.blocking {
        val report = Json.obj("fixtureKind" -> Json.fromString("synthetic"), "source" -> Json.fromString(source.ref.release.render),
          "subset" -> Json.fromString(subset.release.release.render), "reimportAdmissible" -> Json.fromBoolean(again.candidate.assessment.admissible),
          "profile" -> Json.fromString(source.interpretation.profile.name), "table" -> Json.fromString(table), "events" -> Json.fromString(event))
        val _ = Files.writeString(root.resolve("report.json"), report.spaces2 + "\n")
      }
    yield ()
