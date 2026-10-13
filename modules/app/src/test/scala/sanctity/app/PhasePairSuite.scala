package sanctity.app

import cats.effect.IO
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

abstract class CatalogPhasePairSuite extends CatalogProcessingSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val provenance = MriFixtures.json("phase-pair/provenance.json").hcursor
  private val inventory = provenance.downField("files").focus.get.asObject.get.keys.toVector.sorted
  private val members = provenance.get[Vector[String]]("expectedMembers").toOption.get
  private val bold = provenance.get[String]("target").toOption.get
  private val other = provenance.get[String]("excludedTarget").toOption.get
  private val mask = DisclosureProfile(Set.empty, Set("License", "TaskName", "RepetitionTime", "EchoTime", "B0FieldIdentifier", "B0FieldSource", "IntendedFor"))
  private val query = ScientificQuery(artifacts = ArtifactPredicate.All(Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("run", "1"))))
  private def json(s: String): String = s.stripSuffix(".nii") + ".json"
  private def install(root: Path): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
    inventory.foreach { name =>
      val out = root.resolve("source").resolve(name)
      Files.createDirectories(out.getParent)
      val _ = Files.write(out, MriFixtures.bytes("phase-pair/dataset/" + name))
    }
  }
  private def original(s: RepositorySystem, root: Path): IO[Release] = for {
    imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
    _ = assert(imported.candidate.assessment.admissible, imported.candidate.assessment.errors.toString)
    accepted <- checked(s.repository.accept(s.context, imported.candidate.id, key("phase-pair-source")))
    release <- checked(s.catalog.release(s.context, accepted.release))
  } yield release
  private def views(s: RepositorySystem) = new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, IdSource.random[IO])
  private def changes(s: RepositorySystem) = new ScientificChanges[IO](s.catalog, s.store, s.repository, new BidsChangeCompiler)

  test("phase-pair cross-session view retains all members, origins and bytes through re-import") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- original(s, root)
        closure <- Checked.lift[IO, RelationshipClosure](source.interpretation.relationshipClosure(Vector(p(bold))))
        _ = assertEquals(closure.artifacts.toSet, (members :+ bold).map(p).toSet)
        _ = assertEquals(closure.relationships.count(_.kind == "bids.phase-pair-family"), 4)
        _ = members.foreach { member =>
          val family = source.interpretation.relationshipClosure(Vector(p(member))).toOption.get
          assertEquals(family.artifacts.toSet, members.map(p).toSet)
          assert(family.relationships.flatMap(_.evidence).exists {
            case RelationshipEvidence.MetadataField(_, _, "EchoTime") => true
            case _ => false
          })
        }
        selection <- checked(s.catalog.select(s.context, Vector(source.ref), query))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        proposed <- checked(views(s).prepare(s.context, source.ref, selection.id, destination.id, mask))
        _ = assert(proposed.candidate.assessment.admissible, proposed.candidate.assessment.errors.toString)
        accepted <- checked(s.repository.accept(s.context, proposed.candidate.id, key("phase-pair-view")))
        view <- checked(s.catalog.release(s.context, accepted.release))
        _ = assertEquals(view.manifest.files.filter(_.path.value.endsWith(".nii")).map(_.path).toSet, (members :+ bold).map(p).toSet)
        _ = (members :+ bold).foreach { path =>
          assertEquals(view.manifest.byPath(p(path)).asInstanceOf[Entry.File].blob, source.manifest.byPath(p(path)).asInstanceOf[Entry.File].blob)
        }
        _ = assert(view.activity.projection.get.receipt.get.matches(view.manifest))
        _ <- checked(s.repository.exportFull(s.context, view.ref, new DirectoryExport[IO](root.resolve("phase-view"))))
        _ <- IO.blocking {
          members.take(2).foreach { phase =>
            val text = Files.readString(root.resolve("phase-view").resolve(json(phase)))
            assert(text.contains("bids::" + bold))
            assert(!text.contains(other))
          }
        }
        reimportDestination <- s.repository.create(s.context, s.dataset.custodian)
        reimported <- checked(s.repository.importNative(s.context, reimportDestination.id, new NativeDirectorySource[IO](root.resolve("phase-view"))))
        _ = assert(reimported.candidate.assessment.admissible, reimported.candidate.assessment.errors.toString)
        _ = assertEquals(reimported.candidate.interpretation.artifacts, view.interpretation.artifacts)
        _ = assertEquals(reimported.candidate.interpretation.relationships.get.relationships, view.interpretation.relationships.get.relationships)
        maskedDestination <- s.repository.create(s.context, s.dataset.custodian)
        masked <- views(s).prepare(s.context, source.ref, selection.id, maskedDestination.id, mask.copy(metadataFields = mask.metadataFields - "EchoTime"))
        _ = assert(masked.isLeft)
      yield ()
    }
  }

  test("phase-pair duplicate declarations identify one complete required processing family") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- original(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(source.ref), query))
        inputs = new ProcessingInputs[IO](s.catalog, s.repository, views(s), new MriInputPlanner)
        contract = MriInputPlanner.Contract.copy(parameters = Map("fieldmaps" -> "required"))
        ready <- checked(inputs.readiness(s.context, source.ref, selection.id, contract))
        _ = assert(ready.ready, ready.requirements.toString)
        _ = assertEquals(ready.requirements.find(_.code == "fieldmaps").get.evidence.toSet, members.map(p).toSet)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        prepared <- checked(inputs.preparePrivate(s.context, source.ref, selection.id, destination.id, mask, contract))
        _ = assert(prepared.candidate.assessment.admissible, prepared.candidate.assessment.errors.toString)
        _ = assert(prepared.candidate.activity.bundle.get.readiness.ready)
        _ <- checked(s.repository.accept(s.context, prepared.candidate.id, key("phase-pair-bundle")))
      yield ()
    }
  }

  test("phase-pair member removal blocks admission and preserves historical closure") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        source <- original(s, root)
        edits = Vector(FileEdit.Remove(p(members(3))))
        plan <- checked(changes(s).plan(s.context, source.ref, edits))
        preview <- checked(changes(s).prepare(s.context, source.ref, edits, plan.id, "Inspect missing magnitude2"))
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "PhasePairMissingMember"))
        _ = assert((members :+ bold).map(p).toSet.subsetOf(preview.dependencyImpact.before.toSet))
        denied <- s.repository.accept(s.context, preview.candidate.id, key("missing-phase-pair-member"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, Some(source.ref.release))
        historical <- checked(s.catalog.release(s.context, source.ref))
        _ = assertEquals(historical.interpretation.id, source.interpretation.id)
        _ <- checked(s.repository.exportFull(s.context, source.ref, new DirectoryExport[IO](root.resolve("historical-phase-pair"))))
        _ <- IO.blocking(assertEquals(Files.readAllBytes(root.resolve("historical-phase-pair").resolve(members(3))).toVector,
          MriFixtures.bytes("phase-pair/dataset/" + members(3)).toVector))
      yield ()
    }
  }

  test("phase-pair scientific changes repair both target references and scan rows and preserve member lineage") {
    fixture { (s, root) =>
      val renamed = bold.replace("run-1", "run-3")
      val scanPath = "sub-01/ses-task/sub-01_ses-task_scans.tsv"
      val memberMoves = members.flatMap { old =>
        Vector(FileEdit.Move(p(old), p(old.replace("acq-gre", "acq-new"))), FileEdit.Move(p(json(old)), p(json(old.replace("acq-gre", "acq-new")))))
      }
      for
        _ <- install(root)
        _ <- IO.blocking(Files.writeString(root.resolve("source").resolve(scanPath),
          "filename\tacq_time\nfunc/" + p(bold).fileName + "\tn/a\nfunc/" + p(other).fileName + "\tn/a\n"))
        source <- original(s, root)
        edits = Vector(FileEdit.Move(p(bold), p(renamed))) ++ memberMoves
        plan <- checked(changes(s).plan(s.context, source.ref, edits))
        stale <- changes(s).prepare(s.context, source.ref, edits, Digest.bytes(Array[Byte](42)), "Wrong acknowledgment")
        _ = assert(stale.isLeft)
        preview <- checked(changes(s).prepare(s.context, source.ref, edits, plan.id, "Rename family and consumer"))
        _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
        _ = assert(preview.dependencyImpact.before.contains(p(bold)))
        _ = assert(preview.dependencyImpact.after.contains(p(renamed)))
        accepted <- checked(s.repository.accept(s.context, preview.candidate.id, key("rename-phase-pair")))
        changed <- checked(s.catalog.release(s.context, accepted.release))
        _ = (Vector(bold -> renamed) ++ members.map(old => old -> old.replace("acq-gre", "acq-new"))).foreach { (old, next) =>
          val before = source.manifest.byPath(p(old)).asInstanceOf[Entry.File]
          val after = changed.manifest.byPath(p(next)).asInstanceOf[Entry.File]
          assertEquals(after.artifact, before.artifact)
          assertEquals(after.blob, before.blob)
        }
        _ <- checked(s.repository.exportFull(s.context, changed.ref, new DirectoryExport[IO](root.resolve("changed-phase-pair"))))
        _ <- IO.blocking {
          members.take(2).foreach { old =>
            val text = Files.readString(root.resolve("changed-phase-pair").resolve(json(old.replace("acq-gre", "acq-new"))))
            assert(text.contains(renamed))
            assert(!text.contains(bold))
          }
          val scans = Files.readString(root.resolve("changed-phase-pair").resolve(scanPath))
          assert(scans.contains(p(renamed).fileName))
          assert(!scans.contains(p(bold).fileName))
        }
        destination <- s.repository.create(s.context, s.dataset.custodian)
        roundtrip <- checked(s.repository.importNative(s.context, destination.id, new NativeDirectorySource[IO](root.resolve("changed-phase-pair"))))
        _ = assert(roundtrip.candidate.assessment.admissible, roundtrip.candidate.assessment.errors.toString)
        _ = assertEquals(roundtrip.candidate.interpretation.relationships.get.relationships, changed.interpretation.relationships.get.relationships)
      yield ()
    }
  }
