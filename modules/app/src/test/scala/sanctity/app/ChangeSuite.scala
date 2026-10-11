package sanctity.app

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.*
import sanctity.bids.BidsChangeCompiler
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** Shared scientific edit laws run against both catalogs. */
abstract class CatalogChangeSuite extends CatalogContextSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def checked[A](a: IO[Either[Problem, A]]): IO[A] = a.flatMap(Checked.lift[IO, A])
  private val bold = SyntheticFixture.firstImage
  private val renamed = "sub-01/func/sub-01_task-rest_run-2_bold.nii"
  private val epi = "sub-01/fmap/sub-01_dir-AP_epi.nii"
  private val epiJson = epi.stripSuffix(".nii") + ".json"
  private val boldJson = bold.stripSuffix(".nii") + ".json"
  private val scans = "sub-01/sub-01_scans.tsv"
  private def changes(s: RepositorySystem, store: Option[BlobStore[IO]] = None): ScientificChanges[IO] =
    new ScientificChanges[IO](s.catalog, store.getOrElse(s.store), s.repository, new BidsChangeCompiler)
  private def write(root: Path, name: String, value: String): Unit =
    val target = root.resolve("source").resolve(name)
    Files.createDirectories(target.getParent)
    val _ = Files.writeString(target, value)
  private def install(root: Path): IO[Unit] = IO.blocking {
    val target = root.resolve("source").resolve(epi)
    Files.createDirectories(target.getParent)
    Files.copy(root.resolve("source").resolve(bold), target)
    write(root, epiJson, """{"B0FieldIdentifier":"pair","IntendedFor":"func/sub-01_task-rest_bold.nii","PhaseEncodingDirection":"j","TotalReadoutTime":0.05}""")
    write(root, boldJson, """{"B0FieldSource":"pair"}""")
    write(root, scans, "filename\tacq_time\tnote\r\nfunc/sub-01_task-rest_bold.nii\tn/a\t\"line 1\nline 2\"\r\n")
  }
  private def initial(s: RepositorySystem, root: Path): IO[Release] =
    for
      preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("source")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def text(s: RepositorySystem, r: Release, path: String): IO[String] =
    s.store.read(r.manifest.byPath(p(path)).asInstanceOf[Entry.File].blob).through(fs2.text.utf8.decode).compile.string
  private def prepare(s: RepositorySystem, r: Release, edits: Vector[FileEdit]): IO[Preview] =
    for
      plan <- checked(changes(s).plan(s.context, r.ref, edits))
      result <- checked(changes(s).prepare(s.context, r.ref, edits, plan.id, "Scientific curation test"))
    yield result

  test("rename repairs references and scan rows, preserves identity, exports history and records the exact plan") {
    fixture { (s, root) =>
      val edits = Vector(FileEdit.Move(p(bold), p(renamed)), FileEdit.Move(p(boldJson), p(renamed.stripSuffix(".nii") + ".json")))
      for
        _ <- install(root)
        before <- initial(s, root)
        writes <- Ref.of[IO, Int](0)
        reads <- Ref.of[IO, Vector[Digest]](Vector.empty)
        counted = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = writes.update(_ + 1) *> s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = Stream.eval(reads.update(_ :+ blob.digest)).drain ++ s.store.read(blob)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
        service = changes(s, Some(counted))
        plan <- checked(service.plan(s.context, before.ref, edits))
        _ = assertEquals(plan.repairs.map(_.output).toSet, Set(p(epiJson), p(scans)))
        _ = assert(plan.repairs.find(_.output == p(epiJson)).get.text.contains("bids::" + renamed))
        count <- writes.get
        _ = assertEquals(count, 0)
        read <- reads.get
        _ = assert(!read.contains(before.manifest.byPath(p(bold)).asInstanceOf[Entry.File].blob.digest))
        preview <- checked(service.prepare(s.context, before.ref, edits, plan.id, "Correct run label"))
        _ = assertEquals(preview.candidate.manifest.id, plan.manifest.id)
        _ = assert(preview.candidate.assessment.admissible)
        _ = assert(preview.dependencyImpact.before.contains(p(bold)))
        _ = assert(preview.dependencyImpact.after.contains(p(renamed)))
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("rename")))
        same <- checked(s.repository.accept(s.context, preview.candidate.id, key("rename")))
        _ = assertEquals(same, receipt)
        after <- checked(s.catalog.release(s.context, receipt.release))
        moved = after.manifest.byPath(p(renamed)).asInstanceOf[Entry.File]
        old = before.manifest.byPath(p(bold)).asInstanceOf[Entry.File]
        _ = assertEquals(moved.artifact, old.artifact)
        _ = assertEquals(moved.blob, old.blob)
        _ = assertEquals(after.activity.kind, ActivityKind.ScientificChange)
        evidence <- s.store.read(after.activity.evidence.get).through(fs2.text.utf8.decode).compile.string
        _ = assertEquals(evidence, plan.receipt)
        _ = assert(evidence.contains(before.interpretation.id.value.value))
        scanned <- text(s, after, scans)
        table = bids4s.CheckedTsv.parse(scanned).toOption.get.table
        _ = assertEquals(table.rows.head, Vector(Some("func/" + p(renamed).fileName), None, Some("line 1\nline 2")))
        _ <- checked(s.repository.exportFull(s.context, before.ref, new DirectoryExport[IO](root.resolve("old"))))
        _ <- checked(s.repository.exportFull(s.context, after.ref, new DirectoryExport[IO](root.resolve("new"))))
        _ <- IO.blocking {
          assert(Files.exists(root.resolve("old").resolve(bold)))
          assert(!Files.exists(root.resolve("new").resolve(bold)))
          assertEquals(Files.readAllBytes(root.resolve("old").resolve(bold)).toVector, Files.readAllBytes(root.resolve("new").resolve(renamed)).toVector)
        }
        target <- s.repository.create(s.context, s.dataset.custodian)
        imported <- checked(s.repository.importNative(s.context, target.id, new NativeDirectorySource[IO](root.resolve("new"))))
        _ = assert(imported.candidate.assessment.admissible)
        _ = assertEquals(imported.candidate.interpretation.relationships.get.relationships, after.interpretation.relationships.get.relationships)
      yield ()
    }
  }

  test("removing a referenced image proposes explicit reference and table deletion without erasing source history") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        before <- initial(s, root)
        edits = Vector(FileEdit.Remove(p(bold)), FileEdit.Remove(p(boldJson)))
        plan <- checked(changes(s).plan(s.context, before.ref, edits))
        _ = assertEquals(plan.repairs.size, 2)
        _ = assert(!plan.repairs.find(_.output == p(epiJson)).get.text.contains("IntendedFor"))
        preview <- checked(changes(s).prepare(s.context, before.ref, edits, plan.id, "Exclude acquisition"))
        _ = assert(preview.candidate.assessment.admissible)
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("remove")))
        after <- checked(s.catalog.release(s.context, receipt.release))
        scanText <- text(s, after, scans)
        _ = assertEquals(bids4s.CheckedTsv.parse(scanText).toOption.get.table.rows.size, 0)
        oldText <- text(s, before, epiJson)
        _ = assert(oldText.contains("IntendedFor"))
        _ = assertEquals(after.interpretation.relationships.get.relationships.size, 0)
      yield ()
    }
  }

  test("removing a B0 provider leaves declarations intact and cannot accept a dangling group") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        before <- initial(s, root)
        preview <- prepare(s, before, Vector(FileEdit.Remove(p(epi)), FileEdit.Remove(p(epiJson))))
        _ = assert(!preview.candidate.assessment.admissible)
        _ = assert(preview.relationshipChanges.exists(_.path == p(bold)))
        _ = assert(preview.dependencyImpact.before.contains(p(bold)))
        rejected <- s.repository.accept(s.context, preview.candidate.id, key("missing-provider"))
        _ = assert(rejected.left.exists(_.isInstanceOf[Problem.Rejected]))
        dataset <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(dataset.head, Some(before.ref.release))
      yield ()
    }
  }

  test("moving shared metadata reports old and new applicability while preserving a local override") {
    fixture { (s, root) =>
      for
        before <- initial(s, root)
        preview <- prepare(s, before, Vector(FileEdit.Move(p(SyntheticFixture.sharedSidecar), p("sub-01/task-rest_bold.json"))))
        _ = assert(preview.candidate.assessment.admissible)
        _ = assertEquals(preview.dependencyImpact.before.toSet, Set(p(bold), p(SyntheticFixture.secondImage)))
        _ = assertEquals(preview.dependencyImpact.after.toSet, Set(p(bold)))
        second = preview.candidate.interpretation.byPath(p(SyntheticFixture.secondImage))
        _ = assertEquals(second.fields("RepetitionTime").value, MetadataValue.Number(BigDecimal(2)))
        _ = assert(!second.fields.contains("TaskName"))
        _ = assert(preview.metadataChanges.exists(i => i.path == p(SyntheticFixture.secondImage) && i.fields.exists(_.name == "TaskName")))
      yield ()
    }
  }

  test("plan acknowledgment and head conflicts prevent applying different or stale scientific edits") {
    fixture { (s, root) =>
      for
        before <- initial(s, root)
        edits = Vector(FileEdit.Move(p(bold), p(renamed)))
        plan <- checked(changes(s).plan(s.context, before.ref, edits))
        mismatch <- changes(s).prepare(s.context, before.ref, Vector(FileEdit.Remove(p(bold))), plan.id, "Mismatched edit")
        _ = assert(mismatch.left.exists(_.isInstanceOf[Problem.Conflict]))
        competing <- checked(s.repository.correctMetadata(s.context, before.ref, p(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson, "Competing edit"))
        pending <- checked(changes(s).prepare(s.context, before.ref, edits, plan.id, "Prepared before race"))
        accepted <- checked(s.repository.accept(s.context, competing.candidate.id, key("competing")))
        stale <- changes(s).prepare(s.context, before.ref, edits, plan.id, "Stale plan")
        _ = assert(stale.left.exists(_.isInstanceOf[Problem.Conflict]))
        race <- s.repository.accept(s.context, pending.candidate.id, key("race"))
        _ = assert(race.left.exists(_.isInstanceOf[Problem.Conflict]))
        fresh <- checked(changes(s).plan(s.context, accepted.release, edits))
        _ = assertNotEquals(fresh.id, plan.id)
      yield ()
    }
  }

  test("companion changes are explicit and orphaning DWI gradients blocks admission") {
    fixture { (s, root) =>
      val image = "sub-01/dwi/sub-01_run-1_dwi.nii"
      val gradient = image.stripSuffix(".nii") + ".bval"
      for
        _ <- IO.blocking {
          val target = root.resolve("source").resolve(image)
          Files.createDirectories(target.getParent)
          Files.write(target, DwiTestFiles.nifti())
          write(root, gradient, "0 1000\n")
          write(root, image.stripSuffix(".nii") + ".bvec", "0 1\n0 0\n0 0\n")
        }
        before <- initial(s, root)
        orphan <- prepare(s, before, Vector(FileEdit.Move(p(gradient), p(gradient.replace("run-1", "run-2")))))
        _ = assert(!orphan.candidate.assessment.admissible)
        edits = Vector(image, gradient, image.stripSuffix(".nii") + ".bvec").map(n => FileEdit.Move(p(n), p(n.replace("run-1", "run-2"))))
        coherent <- prepare(s, before, edits)
        _ = assert(coherent.candidate.assessment.admissible)
        _ = assert(coherent.candidate.assessment.checks.exists(_.target == p(image.replace("run-1", "run-2"))))
      yield ()
    }
  }

  test("unsupported references, subject relabeling and unsafe scans rows fail during planning") {
    fixture { (s, root) =>
      for
        _ <- install(root)
        _ <- IO.blocking(write(root, "README.json", """{"Sources":["arbitrary.nii"]}"""))
        before <- initial(s, root)
        unsupported <- changes(s).plan(s.context, before.ref, Vector(FileEdit.Move(p(bold), p(renamed))))
        _ = assert(unsupported.left.exists(_.isInstanceOf[Problem.Unsupported]))
        relabel <- changes(s).plan(s.context, before.ref, Vector(FileEdit.Move(p(bold), p(bold.replace("sub-01", "sub-03")))))
        _ = assert(relabel.left.exists(_.isInstanceOf[Problem.Unsupported]))
        docs = Map(p(scans) -> "filename\n../secret.nii\n")
        compiler = new BidsChangeCompiler
        sourceDocs <- compiler.documents(before, Vector(FileEdit.Remove(p(bold)))).toOption.get.traverse { path => text(s, before, path.value).map(path -> _) }
        unsafe = compiler.repairs(before, Vector(FileEdit.Remove(p(bold))), sourceDocs.toMap.updated(p("README.json"), "{}") ++ docs)
        _ = assert(unsafe.isLeft)
      yield ()
    }
  }

  test("planning and preparation recheck current access, including revocation after inspection") {
    fixture { (s, root) =>
      for
        before <- initial(s, root)
        id <- IdSource.random[IO].next[PrincipalTag]
        guest = AccessContext(id)
        grant <- checked(s.catalog.grant(s.context, s.dataset.id, id, None, Set(Action.Propose, Action.ReadMetadata, Action.ReadContent)))
        edits = Vector(FileEdit.Move(p(bold), p(renamed)))
        plan <- checked(changes(s).plan(guest, before.ref, edits))
        _ <- checked(s.catalog.revoke(s.context, s.dataset.id, grant.id))
        denied <- changes(s).prepare(guest, before.ref, edits, plan.id, "Revoked edit")
        _ = assertEquals(denied, Left(Problem.Denied))
      yield ()
    }
  }

  test("revising a prepared draft cannot attach its old change receipt to new content") {
    fixture { (s, root) =>
      for
        before <- initial(s, root)
        preview <- prepare(s, before, Vector(FileEdit.Move(p(bold), p(renamed))))
        draft <- checked(s.catalog.readDraft(s.context, preview.candidate.draft))
        changed <- Checked.lift[IO, Manifest](FileEdits.applyTo(draft.manifest, Vector(FileEdit.Remove(p(SyntheticFixture.secondImage)))))
        _ <- checked(s.catalog.revise(s.context, draft.id, draft.generation, changed, draft.activity))
        stale <- s.repository.prepare(s.context, draft.id)
        _ = assert(stale.left.exists(_.isInstanceOf[Problem.Conflict]))
      yield ()
    }
  }

  test("planning verifies document digests and rejects oversized inventories before reading") {
    fixture { (s, root) =>
      for
        before <- initial(s, root)
        reads <- Ref.of[IO, Int](0)
        corrupt = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = Stream.eval(reads.update(_ + 1)).drain ++ Stream.emits(Array.fill(blob.size.toInt)(0.toByte)).covary[IO]
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
        edits = Vector(FileEdit.Move(p(bold), p(renamed)))
        invalid <- changes(s, Some(corrupt)).plan(s.context, before.ref, edits)
        _ = assert(invalid.left.exists(_.isInstanceOf[Problem.Integrity]))
        _ <- IO.blocking(write(root, scans, "filename\n" + "x" * (4 * 1024 * 1024)))
        second <- s.repository.create(s.context, s.dataset.custodian)
        large <- checked(s.repository.importNative(s.context, second.id, new NativeDirectorySource[IO](root.resolve("source"))))
        receipt <- checked(s.repository.accept(s.context, large.candidate.id, key("large-scans")))
        _ <- reads.set(0)
        denied <- changes(s, Some(corrupt)).plan(s.context, receipt.release, edits)
        _ = assert(denied.left.exists(_.isInstanceOf[Problem.LimitExceeded]))
        count <- reads.get
        _ = assertEquals(count, 0)
      yield ()
    }
  }
