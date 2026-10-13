package sanctity.app

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.{Checked, Failure, IdSource}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

abstract class CatalogLifecycleSuite extends CatalogSubsetSuite:
  private def path(value: String): RelPath = RelPath.from(value).fold(p => fail(p.message), identity)
  private def key(value: String): IdempotencyKey = IdempotencyKey.from(value).fold(p => fail(p.message), identity)
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def remove(root: Path): IO[Unit] = IO.blocking {
    val walk = Files.walk(root)
    try walk.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(p => Files.deleteIfExists(p))
    finally walk.close()
  }
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit

  private def imported(system: RepositorySystem, root: Path): IO[Preview] =
    checked(system.repository.importNative(system.context, system.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))

  private def accepted(system: RepositorySystem, root: Path): IO[AcceptanceReceipt] =
    imported(system, root).flatMap(p => checked(system.repository.accept(system.context, p.candidate.id, key("initial"))))

  private def correction(system: RepositorySystem, release: ReleaseRef, json: String = SyntheticFixture.correctedJson,
      at: String = SyntheticFixture.sharedSidecar): IO[Preview] =
    checked(system.repository.correctMetadata(system.context, release, path(at), json, "Test metadata correction"))

  private def planned(system: RepositorySystem, release: Release, selection: FrozenSelection,
      profile: DisclosureProfile): IO[Either[Problem, ProjectionPlan]] =
    val compiler = new sanctity.bids.MriViewCompiler
    compiler.documents(release, selection, profile) match
      case Left(problem) => IO.pure(Left(problem))
      case Right(paths) => paths.traverse { p =>
        val file = release.manifest.byPath(p).asInstanceOf[Entry.File]
        system.store.read(file.blob).through(fs2.text.utf8.decode).compile.string.map(p -> _)
      }.map(v => compiler.plan(release, selection, profile, v.toMap))

  private def tree(root: Path): IO[Map[String, Option[Vector[Byte]]]] = IO.blocking {
    val walk = Files.walk(root)
    try walk.iterator().asScala.filter(_ != root).map { p =>
      root.relativize(p).toString -> (if Files.isDirectory(p) then None else Some(Files.readAllBytes(p).toVector))
    }.toMap
    finally walk.close()
  }


  test("fixed-release read grants, content separation, frozen selections and revocation") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        principal <- IdSource.random[IO].next[PrincipalTag]
        guest = AccessContext(principal)
        grant <- checked(s.catalog.grant(s.context, s.dataset.id, principal, Some(initial.release.release), Set(Action.ReadMetadata)))
        selection <- checked(s.catalog.select(guest, Vector(initial.release), ScientificQuery()))
        _ = assertEquals(selection.hits.size, 2)
        contentDenied <- s.repository.exportFull(guest, initial.release, new DirectoryExport[IO](root.resolve("denied-export")))
        _ = assertEquals(contentDenied, Left(Problem.Denied))
        edit <- correction(s, initial.release)
        newer <- checked(s.repository.accept(s.context, edit.candidate.id, key("next")))
        futureDenied <- s.repository.release(guest, newer.release)
        _ = assertEquals(futureDenied, Left(Problem.Denied))
        same <- checked(s.catalog.selection(guest, selection.id))
        _ = assertEquals(same.hits, selection.hits)
        _ <- checked(s.catalog.revoke(s.context, s.dataset.id, grant.id))
        revoked <- s.catalog.selection(guest, selection.id)
        _ = assertEquals(revoked, Left(Problem.Denied))
        delegation <- s.catalog.grant(guest, s.dataset.id, principal, None, Set(Action.ReadContent))
        _ = assertEquals(delegation, Left(Problem.Denied))
      yield ()
    }
  }

  test("proposing and accepting are distinct powers and retry rechecks authority") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        principal <- IdSource.random[IO].next[PrincipalTag]
        proposer = AccessContext(principal)
        _ <- checked(s.catalog.grant(s.context, s.dataset.id, principal, None, Set(Action.ReadMetadata, Action.Propose)))
        preview <- checked(s.repository.correctMetadata(proposer, initial.release, path(SyntheticFixture.sharedSidecar),
          SyntheticFixture.correctedJson, "Authorized proposal"))
        denied <- s.repository.accept(proposer, preview.candidate.id, key("proposer"))
        _ = assertEquals(denied, Left(Problem.Denied))
        approverId <- IdSource.random[IO].next[PrincipalTag]
        approver = AccessContext(approverId)
        grant <- checked(s.catalog.grant(s.context, s.dataset.id, approverId, None, Set(Action.Accept)))
        receipt <- checked(s.repository.accept(approver, preview.candidate.id, key("approver")))
        _ = assertEquals(receipt.candidate, preview.candidate.id)
        _ <- checked(s.catalog.revoke(s.context, s.dataset.id, grant.id))
        revokedRetry <- s.repository.accept(approver, preview.candidate.id, key("approver"))
        _ = assertEquals(revokedRetry, Left(Problem.Denied))
      yield ()
    }
  }

  test("correlated participant and session predicates agree with the pure interpreter") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.writeString(source.resolve("participants.tsv"), "participant_id\tage\tsex\nsub-01\t70\tM\nsub-02\t20\tF\n")
          def copy(from: String, to: String): Unit =
            val target = source.resolve(to)
            Files.createDirectories(target.getParent)
            val _ = if to.endsWith("_T1w.nii") then Files.write(target, DwiTestFiles.nifti(volumes = 1))
              else Files.copy(source.resolve(from), target)
          copy(SyntheticFixture.firstImage, "sub-01/ses-a/anat/sub-01_ses-a_T1w.nii")
          copy(SyntheticFixture.firstImage, "sub-01/ses-b/func/sub-01_ses-b_task-rest_bold.nii")
          copy(SyntheticFixture.firstImage, "sub-02/ses-a/anat/sub-02_ses-a_T1w.nii")
          copy(SyntheticFixture.secondImage, "sub-02/ses-a/func/sub-02_ses-a_task-rest_bold.nii")
        }
        initial <- accepted(s, root)
        release <- checked(s.catalog.release(s.context, initial.release))
        impossible = ScientificQuery(ParticipantPredicate.All(Vector(
          ParticipantPredicate.FieldEquals("sex", "F"), ParticipantPredicate.FieldAtLeast("age", BigDecimal(60)))))
        zero <- checked(s.catalog.select(s.context, Vector(initial.release), impossible))
        _ = assertEquals(zero.hits, Vector.empty)
        sessionQuery = ScientificQuery(ParticipantPredicate.HasSession(Vector(
          ArtifactPredicate.Suffix("T1w"), ArtifactPredicate.Suffix("bold"))))
        sameSession <- checked(s.catalog.select(s.context, Vector(initial.release), sessionQuery))
        _ = assertEquals(sameSession.hits.map(_.participant).distinct, Vector("sub-02"))
        queries = Vector(impossible, sessionQuery, ScientificQuery(
          ParticipantPredicate.HasArtifact(ArtifactPredicate.Entity("ses", "b")))) ++
          (0 to 10).toVector.map(n => ScientificQuery(artifacts = ArtifactPredicate.MetadataBelow("RepetitionTime", BigDecimal(n) / 4))) ++
          Vector(ScientificQuery(artifacts = ArtifactPredicate.MetadataEquals("TaskName", "rest")),
            ScientificQuery(artifacts = ArtifactPredicate.Entity("run", "01")),
            ScientificQuery(ParticipantPredicate.FieldAtLeast("missing", BigDecimal(0))))
        _ <- queries.traverse_ { query =>
          for
            actual <- checked(s.catalog.select(s.context, Vector(initial.release), query))
            expected <- Checked.lift[IO, Vector[SelectionHit]](ReferenceQuery.evaluate(initial.release, release.interpretation, query))
            _ = assertEquals(actual.hits.map(_.path).toSet, expected.map(_.path).toSet)
          yield ()
        }
      yield ()
    }
  }


  test("authorized views flatten inherited metadata, project participants and share without source access") {
    fixture { (s, root) =>
      val ids = IdSource.random[IO]
      val views = new sanctity.application.ScientificViews[IO](s.catalog, s.store, s.repository,
        new sanctity.bids.MriViewCompiler, ids)
      for
        initial <- accepted(s, root)
        query = ScientificQuery(artifacts = ArtifactPredicate.Entity("sub", "01"))
        selection <- checked(s.catalog.select(s.context, Vector(initial.release), query))
        custodian <- ids.next[CustodianTag]
        destination <- s.repository.create(s.context, custodian)
        profile = DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime"))
        view <- checked(views.prepare(s.context, initial.release, selection.id, destination.id, profile))
        receipt <- checked(s.repository.accept(s.context, view.candidate.id, key("view")))
        guestId <- ids.next[PrincipalTag]
        guest = AccessContext(guestId)
        grant <- checked(s.catalog.grant(s.context, destination.id, guestId, Some(receipt.release.release),
          Set(Action.ReadMetadata, Action.ReadContent)))
        _ <- checked(s.repository.exportFull(guest, receipt.release, new DirectoryExport[IO](root.resolve("view"))))
        content <- tree(root.resolve("view"))
        _ = assert(!content.keys.exists(_.contains("sub-02")))
        _ = assert(!content.contains(SyntheticFixture.sharedSidecar))
        _ = assert(!content.contains("README"))
        table <- IO.blocking(Files.readString(root.resolve("view/participants.tsv")))
        _ = assertEquals(table, "participant_id\nsub-01\n")
        explained <- checked(s.repository.explain(guest, receipt.release, path(SyntheticFixture.firstImage)))
        _ = assertEquals(explained.fields("RepetitionTime").value, MetadataValue.Number(BigDecimal("1.5")))
        _ = assertEquals(explained.sources, Vector(path("sub-01/func/sub-01_task-rest_bold.json")))
        sourceDenied <- s.repository.release(guest, initial.release)
        _ = assertEquals(sourceDenied, Left(Problem.Denied))
        _ <- checked(s.catalog.revoke(s.context, destination.id, grant.id))
        denied <- s.repository.exportFull(guest, receipt.release, new DirectoryExport[IO](root.resolve("revoked-view")))
        _ = assertEquals(denied, Left(Problem.Denied))
        _ = assert(!Files.exists(root.resolve("revoked-view")))
      yield ()
    }
  }

  test("view planning refuses forbidden required metadata and unsupported associations") {
    fixture { (s, root) =>
      val ids = IdSource.random[IO]
      val views = new sanctity.application.ScientificViews[IO](s.catalog, s.store, s.repository,
        new sanctity.bids.MriViewCompiler, ids)
      for
        initial <- accepted(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(initial.release), ScientificQuery()))
        custodian <- ids.next[CustodianTag]
        destination <- s.repository.create(s.context, custodian)
        masked <- views.prepare(s.context, initial.release, selection.id, destination.id, DisclosureProfile(Set.empty, Set("TaskName")))
        _ = assert(masked.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
        edit <- correction(s, initial.release, """{"TaskName":"rest","RepetitionTime":1.5,"B0FieldSource":"fieldmap1","B0FieldIdentifier":"fieldmap1"}""")
        linked <- checked(s.repository.accept(s.context, edit.candidate.id, key("linked")))
        linkedSelection <- checked(s.catalog.select(s.context, Vector(linked.release), ScientificQuery()))
        unsupported <- views.prepare(s.context, linked.release, linkedSelection.id, destination.id,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")))
        _ = assert(unsupported.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
        target <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(target.head, None)
      yield ()
    }
  }


  test("hidden metadata changes do not alter projected view bytes") {
    fixture { (s, root) =>
      val profile = DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime"))
      val query = ScientificQuery(artifacts = ArtifactPredicate.Entity("sub", "01"))
      for
        first <- accepted(s, root)
        release <- checked(s.repository.release(s.context, first.release))
        selection <- checked(s.catalog.select(s.context, Vector(first.release), query))
        a <- checked(planned(s, release, selection, profile))
        edit <- correction(s, first.release, """{"TaskName":"rest","RepetitionTime":1.5,"PrivateNote":"excluded information"}""")
        second <- checked(s.repository.accept(s.context, edit.candidate.id, key("hidden-change")))
        newer <- checked(s.repository.release(s.context, second.release))
        newSelection <- checked(s.catalog.select(s.context, Vector(second.release), query))
        b <- checked(planned(s, newer, newSelection, profile))
        _ = assertEquals(a.generated, b.generated)
        _ = assertEquals(a.retained.map(f => f.path -> f.blob), b.retained.map(f => f.path -> f.blob))
        _ = assertNotEquals(a.evidence.source, b.evidence.source)
      yield ()
    }
  }

  test("native import, explain, correction, acceptance retry, and two exact isolated exports") {
    fixture { (s, root) =>
      for
        originalTree <- tree(root.resolve("source"))
        initial <- accepted(s, root)
        before <- checked(s.repository.release(s.context, initial.release))
        metadata <- checked(s.repository.explain(s.context, initial.release, path(SyntheticFixture.firstImage)))
        _ = assertEquals(metadata.fields("RepetitionTime").value, MetadataValue.Number(BigDecimal("1.5")))
        _ = assertEquals(metadata.fields("RepetitionTime").origin, path(SyntheticFixture.sharedSidecar))
        edit <- correction(s, initial.release)
        _ = assertEquals(edit.contentChanges.map(_.path), Vector(path(SyntheticFixture.sharedSidecar)))
        _ = assertEquals(edit.metadataChanges.filter(_.fields.exists(_.valueChanged)).map(_.path), Vector(path(SyntheticFixture.firstImage)))
        _ = assertEquals(edit.metadataChanges.size, 2)
        receipt <- checked(s.repository.accept(s.context, edit.candidate.id, key("correction")))
        retry <- checked(s.repository.accept(s.context, edit.candidate.id, key("correction")))
        _ = assertEquals(retry, receipt)
        after <- checked(s.repository.release(s.context, receipt.release))
        _ = assertEquals(before.manifest.files.filter(_.path.fileName.endsWith(".nii")).map(_.blob),
          after.manifest.files.filter(_.path.fileName.endsWith(".nii")).map(_.blob))
        _ = assertEquals(before.manifest.files.map(_.artifact), after.manifest.files.map(_.artifact))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("old"))))
        _ <- checked(s.repository.exportFull(s.context, receipt.release, new DirectoryExport[IO](root.resolve("new"))))
        oldTree <- tree(root.resolve("old"))
        newTree <- tree(root.resolve("new"))
        sourceTree <- tree(root.resolve("source"))
        _ = assertEquals(oldTree, originalTree)
        _ = assertEquals(sourceTree, originalTree)
        _ = assertEquals(newTree, originalTree.updated(SyntheticFixture.sharedSidecar, Some(SyntheticFixture.correctedJson.getBytes("UTF-8").toVector)))
        _ <- IO.blocking(Files.writeString(root.resolve("old").resolve(SyntheticFixture.sharedSidecar), "{}"))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("old-again"))))
        again <- tree(root.resolve("old-again"))
        _ = assertEquals(again, originalTree)
        historical <- checked(s.repository.explain(s.context, initial.release, path(SyntheticFixture.firstImage)))
        _ = assertEquals(historical, metadata)
      yield ()
    }
  }

  test("a new sidecar discovers new applicability while preserving artifact identity") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        edit <- correction(s, initial.release, """{"RepetitionTime":1.2}""", "sub-01/func/sub-01_task-rest_bold.json")
        _ = assert(edit.candidate.assessment.admissible)
        _ = assertEquals(edit.metadataChanges.map(_.path), Vector(path(SyntheticFixture.firstImage)))
        field = edit.candidate.interpretation.byPath(path(SyntheticFixture.firstImage)).fields("RepetitionTime")
        _ = assertEquals(field.origin, path("sub-01/func/sub-01_task-rest_bold.json"))
        _ = assertEquals(field.value, MetadataValue.Number(BigDecimal("1.2")))
      yield ()
    }
  }

  test("ambiguous metadata cannot be accepted and leaves the previous head visible") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        edit <- correction(s, initial.release, """{"RepetitionTime":1.2}""", "bold.json")
        _ = assert(edit.candidate.assessment.errors.exists(_.code == "MetadataResolution"))
        result <- s.repository.accept(s.context, edit.candidate.id, key("ambiguous"))
        _ = assert(result.isLeft)
        dataset <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(dataset.head, Some(initial.release.release))
      yield ()
    }
  }

  test("malformed, duplicate-key, invalid, and numerically lossy metadata remain rejected candidates") {
    fixture { (s, root) =>
      accepted(s, root).flatMap { initial =>
        Vector("""{"RepetitionTime":}""", """{"RepetitionTime":1,"RepetitionTime":2}""",
          """{"RepetitionTime":-2}""", """{"RepetitionTime":1.5,"Large":9007199254740993}""",
          """{"RepetitionTime":1.5,}""").traverse_ { json =>
          correction(s, initial.release, json).flatMap { edit =>
            IO(assert(!edit.candidate.assessment.admissible)) *>
              s.repository.accept(s.context, edit.candidate.id, key(json)).map(result => assert(result.isLeft))
          }
        }
      }
    }
  }

  test("two concurrent writers cannot both advance the same head") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        first <- correction(s, initial.release)
        second <- correction(s, initial.release, """{"TaskName":"rest","RepetitionTime":1.7}""")
        results <- (s.repository.accept(s.context, first.candidate.id, key("first")),
          s.repository.accept(s.context, second.candidate.id, key("second"))).parTupled
        _ = assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
        _ = assert(Vector(results._1, results._2).exists(_.left.toOption.exists(_.isInstanceOf[Problem.Conflict])))
      yield ()
    }
  }

  test("a prepared candidate becomes stale when its draft generation changes") {
    fixture { (s, root) =>
      for
        preview <- imported(s, root)
        candidate = preview.candidate
        _ <- checked(s.catalog.revise(s.context, candidate.draft, candidate.generation, candidate.manifest,
          candidate.activity.copy(reason = "Revised draft")))
        outcome <- s.repository.accept(s.context, candidate.id, key("stale"))
        _ = assert(outcome.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        fresh <- checked(s.repository.prepare(s.context, candidate.draft))
        _ <- checked(s.repository.accept(s.context, fresh.candidate.id, key("fresh")))
      yield ()
    }
  }

  test("an idempotency key cannot be reused for different prepared content") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        edit <- correction(s, initial.release)
        result <- s.repository.accept(s.context, edit.candidate.id, key("initial"))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
      yield ()
    }
  }

  test("another principal cannot inspect, accept, correct, or export private content") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        principal <- IdSource.random[IO].next[PrincipalTag]
        stranger = AccessContext(principal)
        reads <- s.repository.release(stranger, initial.release)
        _ = assertEquals(reads, Left(Problem.Denied))
        retry <- s.repository.accept(stranger, initial.candidate, key("initial"))
        _ = assertEquals(retry, Left(Problem.Denied))
        edit <- s.repository.correctMetadata(stranger, initial.release, path(SyntheticFixture.sharedSidecar), "{}", "Unauthorized")
        _ = assertEquals(edit, Left(Problem.Denied))
        exported <- s.repository.exportFull(stranger, initial.release, new DirectoryExport[IO](root.resolve("forbidden")))
        _ = assertEquals(exported, Left(Problem.Denied))
        _ = assert(!Files.exists(root.resolve("forbidden")))
      yield ()
    }
  }

  test("corrupt content blocks acceptance and export without exposing an incomplete destination") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        edit <- correction(s, initial.release)
        image = release.manifest.files.find(_.path.value == SyntheticFixture.firstImage).get
        location = root.resolve("storage/objects").resolve(image.blob.digest.value.take(2)).resolve(image.blob.digest.value)
        _ <- IO.blocking(Files.write(location, Array.fill[Byte](image.blob.size.toInt)(9)))
        exported <- s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("broken")))
        _ = assert(exported.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
        _ = assert(!Files.exists(root.resolve("broken")))
        _ <- IO.blocking {
          val listing = Files.list(root)
          try assert(!listing.iterator().asScala.exists(_.getFileName.toString.startsWith(".sanctity-export-")))
          finally listing.close()
        }
        failedPreparation <- s.repository.correctMetadata(s.context, initial.release, path(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson, "Corrupt source")
        _ = assert(failedPreparation.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
        outcome <- s.repository.accept(s.context, edit.candidate.id, key("corrupt"))
        _ = assert(outcome.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
      yield ()
    }
  }

  test("an existing export destination and its contents remain untouched") {
    fixture { (s, root) =>
      for
        initial <- accepted(s, root)
        result <- s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("source")))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        _ = assert(Files.exists(root.resolve("source/README")))
      yield ()
    }
  }

  test("symlinks are rejected and observed source mutation cannot silently succeed") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.createSymbolicLink(root.resolve("source/link"), root.resolve("source/README")))
        result <- s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source")))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
        _ <- IO.blocking(Files.delete(root.resolve("source/link")))
        source = new NativeDirectorySource[IO](root.resolve("source"))
        entries <- source.entries.compile.toVector
        file = entries.collectFirst { case f: sanctity.application.SourceEntry.File if f.path.value == "README" => f }.get
        _ <- IO.blocking(Files.writeString(root.resolve("source/README"), "changed", StandardOpenOption.APPEND))
        read <- source.read(file).compile.drain.attempt
        _ = assert(read.left.toOption.exists { case Failure(_: Problem.Integrity) => true; case _ => false })
      yield ()
    }
  }

  test("cancelling a blob upload removes its temporary file") {
    fixture { (s, root) =>
      for
        started <- Deferred[IO, Unit]
        bytes = Stream.emits(Array.fill[Byte](65536)(1)).covary[IO] ++
          Stream.exec(started.complete(()).void) ++ Stream.never[IO]
        fiber <- s.store.put(bytes).start
        _ <- started.get
        _ <- fiber.cancel
        _ <- IO.blocking {
          val listing = Files.list(root.resolve("storage/staging"))
          try assert(!listing.findAny().isPresent)
          finally listing.close()
        }
      yield ()
    }
  }

  test("reference profile rejects oversized JSON with a typed limit failure") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.write(root.resolve("source/participants.json"), Array.fill[Byte](4 * 1024 * 1024 + 1)(' '.toByte)))
        result <- s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source")))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.LimitExceeded]))
      yield ()
    }
  }

  test("missing dataset description yields findings and no accepted head") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.delete(root.resolve("source/dataset_description.json")))
        preview <- imported(s, root)
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "MissingDescription"))
        result <- s.repository.accept(s.context, preview.candidate.id, key("missing-description"))
        _ = assert(result.isLeft)
        dataset <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(dataset.head, None)
      yield ()
    }
  }

  test("unrecognized files and empty directories are preserved without a conformance claim") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.write(root.resolve("source/opaque.dat"), Array[Byte](0, -1, 2)))
        preview <- imported(s, root)
        _ = assert(preview.candidate.assessment.admissible)
        _ = assert(preview.candidate.assessment.findings.exists(f => f.severity == Severity.Warning && f.path.contains(path("opaque.dat"))))
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("opaque")))
        _ <- checked(s.repository.exportFull(s.context, receipt.release, new DirectoryExport[IO](root.resolve("opaque-export"))))
        original <- tree(root.resolve("source"))
        exported <- tree(root.resolve("opaque-export"))
        _ = assertEquals(exported, original)
        _ = assertEquals(exported("sourcedata/empty"), None)
      yield ()
    }
  }

  test("invalid UTF-8 and deeply nested metadata produce findings") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.write(root.resolve("source/participants.json"), Array[Byte](-61, 40)))
        invalid <- imported(s, root)
        _ = assert(invalid.candidate.assessment.errors.exists(_.code == "InvalidJson"))
        _ <- IO.blocking(Files.delete(root.resolve("source/participants.json")))
        initial <- accepted(s, root)
        deeplyNested = "{\"a\":" * 130 + "0" + "}" * 130
        edit <- correction(s, initial.release, deeplyNested)
        _ = assert(edit.candidate.assessment.errors.exists(_.message.contains("nesting limit")))
      yield ()
    }
  }

  test("unsupported nested and derivative dataset profiles remain explicit rejected candidates") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          val _ = Files.createDirectories(root.resolve("source/sourcedata/nested"))
          Files.writeString(root.resolve("source/sourcedata/nested/dataset_description.json"),
            """{"Name":"Nested","BIDSVersion":"1.10.0"}""")
        }
        nested <- imported(s, root)
        _ = assert(nested.candidate.assessment.errors.exists(_.code == "UnsupportedComposition"))
        _ <- remove(root.resolve("source/sourcedata/nested"))
        _ <- IO.blocking(Files.writeString(root.resolve("source/dataset_description.json"),
          """{"Name":"Derivative","BIDSVersion":"1.10.0","DatasetType":"derivative"}"""))
        derivative <- imported(s, root)
        _ = assert(derivative.candidate.assessment.errors.exists(_.code == "UnsupportedProfile"))
      yield ()
    }
  }


  private val fieldmapA = "sub-01/fmap/sub-01_dir-AP_epi.nii"
  private val fieldmapB = "sub-01/fmap/sub-01_dir-PA_epi.nii"
  private val fieldmapBJson = "sub-01/fmap/sub-01_dir-PA_epi.json"
  private def fieldmaps(root: Path): IO[Unit] = IO.blocking {
    val source = root.resolve("source")
    val _ = Files.createDirectories(source.resolve("sub-01/fmap"))
    val image = Files.readAllBytes(source.resolve(SyntheticFixture.firstImage))
    Vector(fieldmapA, fieldmapB).foreach(p => Files.write(source.resolve(p), image))
    val _ = Files.writeString(source.resolve("sub-01/fmap/sub-01_dir-AP_epi.json"),
      """{"PhaseEncodingDirection":"j","TotalReadoutTime":0.05,"B0FieldIdentifier":"pepolar"}""")
    val _ = Files.writeString(source.resolve(fieldmapBJson),
      """{"PhaseEncodingDirection":"j-","TotalReadoutTime":0.05,"B0FieldIdentifier":"unused"}""")
    val _ = Files.writeString(source.resolve("sub-01/func/sub-01_task-rest_bold.json"),
      """{"B0FieldSource":"pepolar"}""")
  }

  test("fieldmap correction changes grouped relationships while preserving prior scientific interpretation and bytes") {
    fixture { (s, root) =>
      for
        _ <- fieldmaps(root)
        initial <- accepted(s, root)
        before <- checked(s.repository.release(s.context, initial.release))
        original <- Checked.lift[IO, RelationshipClosure](before.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assertEquals(original.artifacts.toSet, Set(path(SyntheticFixture.firstImage), path(fieldmapA)))
        edit <- correction(s, initial.release,
          """{"PhaseEncodingDirection":"j-","TotalReadoutTime":0.05,"B0FieldIdentifier":"pepolar"}""", fieldmapBJson)
        _ = assertEquals(edit.relationshipChanges.map(_.path), Vector(path(SyntheticFixture.firstImage)))
        _ = assert(!edit.metadataChanges.exists(_.path == path(SyntheticFixture.firstImage)))
        next <- checked(s.repository.accept(s.context, edit.candidate.id, key("fieldmap-correction")))
        after <- checked(s.repository.release(s.context, next.release))
        closure <- Checked.lift[IO, RelationshipClosure](after.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assertEquals(closure.artifacts.toSet, Set(path(SyntheticFixture.firstImage), path(fieldmapA), path(fieldmapB)))
        _ = assertEquals(closure.relationships.head.to.toSet, Set(path(fieldmapA), path(fieldmapB)))
        _ = assert(closure.metadataDocuments.contains(path(fieldmapBJson)))
        _ = assert(closure.metadataDocuments.contains(path(SyntheticFixture.sharedSidecar)))
        _ = assertEquals(before.manifest.files.filter(_.path.fileName.endsWith(".nii")), after.manifest.files.filter(_.path.fileName.endsWith(".nii")))
        again <- checked(s.repository.release(s.context, initial.release))
        _ = assertEquals(again.interpretation.id, before.interpretation.id)
        retained <- Checked.lift[IO, RelationshipClosure](again.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assertEquals(retained, original)
        _ <- checked(s.repository.exportFull(s.context, next.release, new DirectoryExport[IO](root.resolve("fieldmap-export"))))
        exported <- IO.blocking(Files.readAllBytes(root.resolve("fieldmap-export").resolve(fieldmapB)))
        originalBytes <- IO.blocking(Files.readAllBytes(root.resolve("source").resolve(fieldmapB)))
        _ = assertEquals(exported.toVector, originalBytes.toVector)
      yield ()
    }
  }

  test("dangling fieldmap references remain inspectable and cannot be accepted") {
    fixture { (s, root) =>
      for
        _ <- fieldmaps(root)
        initial <- accepted(s, root)
        broken <- correction(s, initial.release, """{"B0FieldSource":"nonexistent"}""", "sub-01/func/sub-01_task-rest_bold.json")
        _ = assert(broken.candidate.assessment.errors.exists(_.code == "FieldmapMissingIdentifier"))
        denied <- s.repository.accept(s.context, broken.candidate.id, key("broken-fieldmap"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        unchanged <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(unchanged.head, Some(initial.release.release))
      yield ()
    }
  }

  test("legacy IntendedFor links resolve with a finding and malformed or external links block admission") {
    fixture { (s, root) =>
      for
        _ <- fieldmaps(root)
        initial <- accepted(s, root)
        legacy <- correction(s, initial.release,
          """{"B0FieldIdentifier":"unused","IntendedFor":"func/sub-01_task-rest_bold.nii"}""", fieldmapBJson)
        _ = assert(legacy.candidate.assessment.admissible)
        _ = assert(legacy.candidate.assessment.findings.exists(_.code == "FieldmapLegacyIntendedFor"))
        links = legacy.candidate.interpretation.relationships.get.relationships
        _ = assert(links.exists(r => r.kind == "bids.intended-for" && r.from == path(SyntheticFixture.firstImage) && r.to == Vector(path(fieldmapB))))
        broken <- correction(s, initial.release,
          """{"IntendedFor":["bids:other:sub-01/func/sub-01_task-rest_bold.nii","../escape.nii"]}""", fieldmapBJson)
        _ = assert(broken.candidate.assessment.errors.exists(_.code == "FieldmapUnsupportedExternalReference"))
        _ = assert(broken.candidate.assessment.errors.exists(_.code == "FieldmapInvalidReference"))
      yield ()
    }
  }


  test("MRI subsets cannot silently omit explicit fieldmap links from another session") {
    fixture { (s, root) =>
      val image = "sub-01/ses-calibration/fmap/sub-01_ses-calibration_dir-AP_epi.nii"
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          val _ = Files.createDirectories(source.resolve("sub-01/ses-calibration/fmap"))
          val _ = Files.write(source.resolve(image), Files.readAllBytes(source.resolve(SyntheticFixture.firstImage)))
          Files.writeString(source.resolve(image.stripSuffix(".nii") + ".json"),
            """{"PhaseEncodingDirection":"j","TotalReadoutTime":0.05,"IntendedFor":"bids::sub-01/func/sub-01_task-rest_bold.nii"}""")
        }
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        selection <- checked(s.catalog.select(s.context, Vector(initial.release),
          ScientificQuery(artifacts = ArtifactPredicate.Suffix("bold"))))
        result <- planned(s, release, selection,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
        closure <- Checked.lift[IO, RelationshipClosure](release.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assert(closure.artifacts.contains(path(image)))
        legacy <- Checked.lift[IO, Interpretation](Interpretation.from(release.manifest.id, release.interpretation.profile,
          release.interpretation.artifacts, release.interpretation.facts, release.interpretation.participants, release.interpretation.queryVersion))
        unsupported <- planned(s, release.copy(interpretation = legacy), selection,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")))
        _ = assert(unsupported.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
      yield ()
    }
  }

  private val eventTable = "onset\tduration\n0\t1\n"
  private val rootEvents = "task-rest_events.tsv"
  private val localEvents = "sub-01/func/sub-01_task-rest_events.tsv"

  test("inherited event correction changes only the applicable acquisition and retains old closure and exact exports") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          Files.writeString(root.resolve("source").resolve(rootEvents), eventTable)
          Files.writeString(root.resolve("source/task-rest_events.json"), """{"onset":{"Units":"s"},"duration":{"Units":"s"}}""")
        }
        initial <- accepted(s, root)
        before <- checked(s.repository.release(s.context, initial.release))
        prior <- Checked.lift[IO, RelationshipClosure](before.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assert(prior.artifacts.contains(path(rootEvents)))
        _ = assert(prior.metadataDocuments.contains(path("task-rest_events.json")))
        edit <- checked(s.repository.correctCompanion(s.context, initial.release, path(localEvents), "onset\tduration\n2\t1\n", "Use acquisition-specific events"))
        _ = assertEquals(edit.relationshipChanges.map(_.path), Vector(path(SyntheticFixture.firstImage)))
        _ = assert(!edit.metadataChanges.exists(_.path == path(SyntheticFixture.firstImage)))
        next <- checked(s.repository.accept(s.context, edit.candidate.id, key("events-override")))
        after <- checked(s.repository.release(s.context, next.release))
        closure <- Checked.lift[IO, RelationshipClosure](after.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assert(closure.artifacts.contains(path(localEvents)))
        _ = assert(!closure.artifacts.contains(path(rootEvents)))
        _ = assertEquals(closure.relationships.head.evidence.collect {
          case e: RelationshipEvidence.CompanionSelection => e.applicable
        }, Vector(Vector(path(rootEvents), path(localEvents))))
        other <- Checked.lift[IO, RelationshipClosure](after.interpretation.relationshipClosure(Vector(path(SyntheticFixture.secondImage))))
        _ = assert(other.artifacts.contains(path(rootEvents)))
        again <- checked(s.repository.release(s.context, initial.release))
        _ = assertEquals(again.interpretation.id, before.interpretation.id)
        retained <- Checked.lift[IO, RelationshipClosure](again.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assertEquals(retained, prior)
        _ = assertEquals(before.manifest.files.filter(_.path.fileName.endsWith(".nii")), after.manifest.files.filter(_.path.fileName.endsWith(".nii")))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("events-old"))))
        _ <- checked(s.repository.exportFull(s.context, next.release, new DirectoryExport[IO](root.resolve("events-new"))))
        old <- IO.blocking(Files.readString(root.resolve("events-old").resolve(rootEvents)))
        fresh <- IO.blocking(Files.readString(root.resolve("events-new").resolve(localEvents)))
        _ = assertEquals(old, eventTable)
        _ = assertEquals(fresh, "onset\tduration\n2\t1\n")
      yield ()
    }
  }

  test("editing selected companion bytes reports dependency impact without inventing a link change") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.writeString(root.resolve("source").resolve(rootEvents), eventTable))
        initial <- accepted(s, root)
        edit <- checked(s.repository.correctCompanion(s.context, initial.release, path(rootEvents), "onset\tduration\n4\t1\n", "Correct event timing"))
        _ = assertEquals(edit.relationshipChanges.map(_.path).toSet, Set(path(SyntheticFixture.firstImage), path(SyntheticFixture.secondImage)))
        _ = assert(edit.relationshipChanges.forall(d => d.before == d.after && d.changedDependencies == Vector(path(rootEvents))))
        next <- checked(s.repository.accept(s.context, edit.candidate.id, key("events-bytes")))
        before <- checked(s.repository.release(s.context, initial.release))
        after <- checked(s.repository.release(s.context, next.release))
        _ = assertEquals(before.manifest.byPath(path(rootEvents)).asInstanceOf[Entry.File].artifact, after.manifest.byPath(path(rootEvents)).asInstanceOf[Entry.File].artifact)
      yield ()
    }
  }

  test("ambiguous event companions block admission and never silently advance the head") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.writeString(root.resolve("source").resolve(rootEvents), eventTable))
        initial <- accepted(s, root)
        edit <- checked(s.repository.correctCompanion(s.context, initial.release, path("sub-01_task-rest_events.tsv"), eventTable, "Ambiguous root table"))
        _ = assert(edit.candidate.assessment.errors.exists(_.code == "CompanionAmbiguous"))
        denied <- s.repository.accept(s.context, edit.candidate.id, key("ambiguous-events"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        dataset <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(dataset.head, Some(initial.release.release))
      yield ()
    }
  }

  test("DWI gradients form one group and an incomplete pair remains a rejected candidate") {
    fixture { (s, root) =>
      val image = "sub-01/dwi/sub-01_dwi.nii"
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.createDirectories(source.resolve("sub-01/dwi"))
          Files.write(source.resolve(image), Files.readAllBytes(source.resolve(SyntheticFixture.firstImage)))
          Files.writeString(source.resolve("dwi.bval"), "0 1000\n")
          Files.writeString(source.resolve("sub-01/sub-01_dwi.bvec"), "0 1\n0 0\n0 0\n")
        }
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        closure <- Checked.lift[IO, RelationshipClosure](release.interpretation.relationshipClosure(Vector(path(image))))
        _ = assertEquals(closure.relationships.map(_.kind), Vector("bids.dwi-gradients"))
        _ = assertEquals(closure.relationships.head.to.toSet, Set(path("dwi.bval"), path("sub-01/sub-01_dwi.bvec")))
        _ <- IO.blocking(Files.delete(root.resolve("source/sub-01/sub-01_dwi.bvec")))
        dataset <- s.repository.create(s.context, s.dataset.custodian)
        preview <- checked(s.repository.importNative(s.context, dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "CompanionMissingBvec"))
        denied <- s.repository.accept(s.context, preview.candidate.id, key("missing-gradient"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        _ = assert(!preview.candidate.interpretation.relationships.get.relationships.exists(_.kind == "bids.dwi-gradients"))
      yield ()
    }
  }

  test("root-level events prevent a restricted MRI subset from silently omitting them") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.writeString(root.resolve("source").resolve(rootEvents), eventTable))
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        selection <- checked(s.catalog.select(s.context, Vector(initial.release), ScientificQuery(artifacts = ArtifactPredicate.Suffix("bold"))))
        result <- planned(s, release, selection, DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
      yield ()
    }
  }


  test("DWI content evidence survives storage; count rejection preserves the head and corrected history") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(DwiTestFiles.install(root.resolve("source")))
        initial <- accepted(s, root)
        before <- checked(s.repository.release(s.context, initial.release))
        evidence = before.assessment.checks.head
        _ = assertEquals(evidence.target, path(DwiTestFiles.image))
        _ = assertEquals(evidence.facts("volumeCount"), MetadataValue.Number(2))
        _ = assertEquals(evidence.inputs.map(_.role).toSet, Set("image", "bval", "bvec"))
        bad <- checked(s.repository.correctCompanion(s.context, initial.release, path(DwiTestFiles.bval),
          "0 1000 2000\n", "Incorrect extra gradient"))
        _ = assert(bad.candidate.assessment.errors.exists(_.code == "DwiBvalCount"))
        blocked <- s.repository.accept(s.context, bad.candidate.id, key("bad-dwi-count"))
        _ = assert(blocked.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, Some(initial.release.release))
        good <- checked(s.repository.correctCompanion(s.context, initial.release, path(DwiTestFiles.bval),
          "0 1200\n", "Correct diffusion weighting"))
        next <- checked(s.repository.accept(s.context, good.candidate.id, key("correct-dwi")))
        after <- checked(s.repository.release(s.context, next.release))
        historical <- checked(s.repository.release(s.context, initial.release))
        _ = assertEquals(historical.assessment.id, before.assessment.id)
        _ = assertEquals(historical.assessment.checks.head.inputs, evidence.inputs)
        _ = assertNotEquals(after.assessment.id, before.assessment.id)
        _ = assertNotEquals(after.assessment.checks.head.inputs.find(_.role == "bval"), evidence.inputs.find(_.role == "bval"))
        _ = assertEquals(after.manifest.byPath(path(DwiTestFiles.image)), before.manifest.byPath(path(DwiTestFiles.image)))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("dwi-before"))))
        _ <- checked(s.repository.exportFull(s.context, next.release, new DirectoryExport[IO](root.resolve("dwi-after"))))
        _ <- IO.blocking {
          assertEquals(Files.readString(root.resolve("dwi-before/dwi.bval")), DwiTestFiles.values)
          assertEquals(Files.readString(root.resolve("dwi-after/dwi.bval")), "0 1200\n")
        }
      yield ()
    }
  }

  test("DWI malformed and nonunit vectors remain inspectable rejected corrections") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(DwiTestFiles.install(root.resolve("source")))
        initial <- accepted(s, root)
        _ <- Vector(("0 NaN\n0 0\n0 0\n", "DwiNumber"), ("0 0.5\n0 0\n0 0\n", "DwiVectorNorm")).traverse_ { (text, code) =>
          for
            edit <- checked(s.repository.correctCompanion(s.context, initial.release, path(DwiTestFiles.bvec), text, "Invalid vector"))
            _ = assert(edit.candidate.assessment.errors.exists(f => f.code == code && f.path.contains(path(DwiTestFiles.bvec)) && f.message.contains("column 2")))
            stored <- checked(s.catalog.candidate(s.context, edit.candidate.id))
            _ = assertEquals(stored.assessment.id, edit.candidate.assessment.id)
            denied <- s.repository.accept(s.context, edit.candidate.id, key(code))
            _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
          yield ()
        }
      yield ()
    }
  }

  test("big-endian NIfTI-2 gzip headers are assessed without rewriting the image") {
    fixture { (s, root) =>
      val name = DwiTestFiles.image + ".gz"
      val bytes = DwiTestFiles.gzip(DwiTestFiles.nifti(version = 2, little = false))
      for
        _ <- IO.blocking(DwiTestFiles.install(root.resolve("source"), bytes, name))
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        facts = release.assessment.checks.head.facts
        _ = assertEquals(facts("niftiVersion"), MetadataValue.Number(2))
        _ = assertEquals(facts("byteOrder"), MetadataValue.Text("BigEndian"))
        _ = assertEquals(facts("volumeCount"), MetadataValue.Number(2))
        _ = assertEquals(facts("headerOnly"), MetadataValue.Flag(true))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("dwi-gzip"))))
        _ <- IO.blocking(assertEquals(Files.readAllBytes(root.resolve("dwi-gzip").resolve(name)).toVector, bytes.toVector))
      yield ()
    }
  }

  test("a short image payload is a content finding and cannot become an accepted release") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(DwiTestFiles.install(root.resolve("source"), DwiTestFiles.nifti().take(352)))
        preview <- imported(s, root)
        _ = assert(preview.candidate.assessment.errors.exists(_.code == "DwiHeader"))
        _ = assertEquals(preview.candidate.assessment.checks.head.inputs.size, 3)
        denied <- s.repository.accept(s.context, preview.candidate.id, key("truncated-dwi"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, None)
      yield ()
    }
  }

  test("shared gradients are checked independently against each acquisition's volume count") {
    fixture { (s, root) =>
      val other = "sub-02/dwi/sub-02_dwi.nii"
      for
        _ <- IO.blocking {
          DwiTestFiles.install(root.resolve("source"))
          DwiTestFiles.install(root.resolve("source"), DwiTestFiles.nifti(volumes = 3), other)
        }
        preview <- imported(s, root)
        checks = preview.candidate.assessment.checks.filter(_.rule.startsWith(bids4s.DwiConsistency.Version)).map(c => c.target -> c).toMap
        _ = assertEquals(checks(path(DwiTestFiles.image)).findings, Vector.empty)
        _ = assertEquals(checks(path(other)).findings.map(_.code).toSet, Set("DwiBvalCount", "DwiBvecCount"))
        denied <- s.repository.accept(s.context, preview.candidate.id, key("shared-dwi"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
      yield ()
    }
  }


  private def eventChecks(assessment: Assessment): Vector[ContentCheck] =
    assessment.checks.filter(_.rule.startsWith(bids4s.CheckedEvents.Version))

  test("event content correction rejects negative duration and preserves history and authored exports") {
    fixture { (s, root) =>
      val original = "onset\tduration\ttrial_type\tresponse_time\n-1\t2\tpractice\t-.1\n0\t0\tgo\tn/a\nn/a\tn/a\tunknown\tn/a\n"
      val revised = original.replace("practice", "training")
      for
        _ <- IO.blocking {
          Files.writeString(root.resolve("source").resolve(rootEvents), original)
          Files.writeString(root.resolve("source/task-rest_events.json"), """{"trial_type":{"Description":"Condition"}}""")
        }
        initial <- accepted(s, root)
        before <- checked(s.repository.release(s.context, initial.release))
        oldChecks = eventChecks(before.assessment)
        _ = assertEquals(oldChecks.size, 2)
        _ = assert(oldChecks.forall(_.facts("eventCount") == MetadataValue.Number(3)))
        _ = assert(oldChecks.forall(_.facts("missingOnsetCount") == MetadataValue.Number(1)))
        _ = assert(oldChecks.forall(_.inputs.exists(_.path == path("task-rest_events.json"))))
        bad <- checked(s.repository.correctCompanion(s.context, initial.release, path(rootEvents),
          "onset\tduration\n0\t-1\n", "Invalid duration"))
        _ = assertEquals(eventChecks(bad.candidate.assessment).flatMap(_.findings).count(_.code == "EventsDuration"), 2)
        blocked <- s.repository.accept(s.context, bad.candidate.id, key("bad-event-duration"))
        _ = assert(blocked.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, Some(initial.release.release))
        edit <- checked(s.repository.correctCompanion(s.context, initial.release, path(rootEvents), revised, "Clarify condition"))
        _ = assertEquals(edit.relationshipChanges.map(_.path).toSet, Set(path(SyntheticFixture.firstImage), path(SyntheticFixture.secondImage)))
        next <- checked(s.repository.accept(s.context, edit.candidate.id, key("correct-event-content")))
        after <- checked(s.repository.release(s.context, next.release))
        historical <- checked(s.repository.release(s.context, initial.release))
        _ = assertEquals(historical.assessment.id, before.assessment.id)
        _ = assertEquals(eventChecks(historical.assessment).map(_.inputs), oldChecks.map(_.inputs))
        _ = assertNotEquals(after.assessment.id, before.assessment.id)
        _ = assertEquals(before.manifest.files.filter(_.path.fileName.endsWith(".nii")), after.manifest.files.filter(_.path.fileName.endsWith(".nii")))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("checked-events-old"))))
        _ <- checked(s.repository.exportFull(s.context, next.release, new DirectoryExport[IO](root.resolve("checked-events-new"))))
        _ <- IO.blocking {
          assertEquals(Files.readString(root.resolve("checked-events-old").resolve(rootEvents)), original)
          assertEquals(Files.readString(root.resolve("checked-events-new").resolve(rootEvents)), revised)
        }
      yield ()
    }
  }

  test("timing corrections reassess inherited event windows using exact metadata origins") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking(Files.writeString(root.resolve("source").resolve(rootEvents), "onset\tduration\n3.5\t0\n"))
        initial <- accepted(s, root)
        before <- checked(s.repository.release(s.context, initial.release))
        prior = eventChecks(before.assessment).map(c => c.target -> c).toMap
        _ = assert(prior(path(SyntheticFixture.firstImage)).findings.exists(_.code == "EventsOutsideWindow"))
        _ = assert(!prior(path(SyntheticFixture.secondImage)).findings.exists(_.code == "EventsOutsideWindow"))
        _ = assertEquals(prior(path(SyntheticFixture.firstImage)).facts("comparisonEndSeconds"), MetadataValue.Number(3))
        edit <- correction(s, initial.release, """{"TaskName":"rest","RepetitionTime":3}""")
        next <- checked(s.repository.accept(s.context, edit.candidate.id, key("event-timing-correction")))
        after <- checked(s.repository.release(s.context, next.release))
        current = eventChecks(after.assessment).map(c => c.target -> c).toMap
        _ = assertEquals(current(path(SyntheticFixture.firstImage)).facts("comparisonEndSeconds"), MetadataValue.Number(6))
        _ = assertEquals(current(path(SyntheticFixture.secondImage)).facts("comparisonEndSeconds"), MetadataValue.Number(4))
        _ = assert(current.values.forall(!_.findings.exists(_.code == "EventsOutsideWindow")))
        _ = assertNotEquals(prior(path(SyntheticFixture.firstImage)).inputs.find(_.path == path(SyntheticFixture.sharedSidecar)),
          current(path(SyntheticFixture.firstImage)).inputs.find(_.path == path(SyntheticFixture.sharedSidecar)))
        history <- checked(s.repository.release(s.context, initial.release))
        _ = assertEquals(eventChecks(history.assessment).map(_.facts), eventChecks(before.assessment).map(_.facts))
      yield ()
    }
  }

  test("nonuniform event timing uses explicit volume times and rejects count disagreement") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.writeString(source.resolve(rootEvents), "onset\tduration\n1.8\t0\n")
          Files.writeString(source.resolve(SyntheticFixture.sharedSidecar),
            """{"TaskName":"rest","VolumeTiming":[5,6.5],"FrameAcquisitionDuration":0.5}""")
          Files.writeString(source.resolve("sub-02/func/sub-02_task-rest_bold.json"), "{}")
        }
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        checks = eventChecks(release.assessment)
        _ = assert(checks.forall(_.facts("timingMode") == MetadataValue.Text("Variable")))
        _ = assert(checks.forall(_.facts("comparisonEndSeconds") == MetadataValue.Number(2)))
        _ = assert(checks.forall(_.facts("volumeTimingOriginSeconds") == MetadataValue.Number(5)))
        edit <- correction(s, initial.release, """{"TaskName":"rest","VolumeTiming":[0,1,3],"FrameAcquisitionDuration":0.5}""")
        _ = assert(edit.candidate.assessment.errors.exists(_.code == "EventsVolumeCount"))
        denied <- s.repository.accept(s.context, edit.candidate.id, key("bad-event-volume-count"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, Some(initial.release.release))
      yield ()
    }
  }

  test("unavailable timing remains explicit while malformed event cells still block admission") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.writeString(source.resolve(rootEvents), "onset\tduration\n100\tn/a\n")
          Files.writeString(source.resolve(SyntheticFixture.sharedSidecar), """{"TaskName":"rest"}""")
          Files.writeString(source.resolve("sub-02/func/sub-02_task-rest_bold.json"), "{}")
        }
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        _ = assert(eventChecks(release.assessment).forall(_.facts("timingMode") == MetadataValue.Text("Unavailable")))
        _ = assert(eventChecks(release.assessment).forall(!_.facts.contains("comparisonEndSeconds")))
        edit <- checked(s.repository.correctCompanion(s.context, initial.release, path(rootEvents),
          "onset\tduration\nNaN\t1\n", "Malformed time"))
        _ = assert(edit.candidate.assessment.errors.exists(f => f.code == "EventsNumber" && f.message.contains("row 2 column 1")))
        denied <- s.repository.accept(s.context, edit.candidate.id, key("invalid-event-number"))
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Rejected]))
      yield ()
    }
  }

  test("variable slice timing without a frame duration never extrapolates a last interval") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.writeString(source.resolve(rootEvents), "onset\tduration\n100\t1\n")
          Files.writeString(source.resolve(SyntheticFixture.sharedSidecar), """{"TaskName":"rest","VolumeTiming":[0,1.5],"SliceTiming":[0]}""")
          Files.writeString(source.resolve("sub-02/func/sub-02_task-rest_bold.json"), "{}")
        }
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        checks = eventChecks(release.assessment)
        _ = assert(checks.forall(_.facts("timingMode") == MetadataValue.Text("Variable")))
        _ = assert(checks.forall(!_.facts.contains("comparisonEndSeconds")))
        _ = assert(checks.forall(_.findings.exists(_.code == "EventsTimingUnavailable")))
        _ = assert(!checks.exists(_.findings.exists(_.code == "EventsOutsideWindow")))
      yield ()
    }
  }

  test("event assessments use the shared gzip header reader and preserve quoted TSV bytes") {
    fixture { (s, root) =>
      val image = "sub-01/func/sub-01_task-rest_bold.nii.gz"
      val table = "onset\tduration\tnote\n0\t1\t\"a\tb\"\n"
      for
        _ <- IO.blocking {
          val source = root.resolve("source")
          Files.delete(source.resolve(SyntheticFixture.firstImage))
          Files.write(source.resolve(image), DwiTestFiles.gzip(DwiTestFiles.nifti(version = 2, little = false)))
          Files.writeString(source.resolve(rootEvents), table)
        }
        initial <- accepted(s, root)
        release <- checked(s.repository.release(s.context, initial.release))
        _ = assert(eventChecks(release.assessment).exists(c => c.target == path(image) && c.facts("volumeCount") == MetadataValue.Number(2)))
        _ <- checked(s.repository.exportFull(s.context, initial.release, new DirectoryExport[IO](root.resolve("events-gzip"))))
        _ <- IO.blocking(assertEquals(Files.readString(root.resolve("events-gzip").resolve(rootEvents)), table))
      yield ()
    }
  }

final class LifecycleSuite extends CatalogLifecycleSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-test-")))(TestFiles.remove).use { root =>
      SyntheticFixture.create(root.resolve("source")) *>
        ReferenceSystem.create(root.resolve("storage")).flatMap(run(_, root))
    }.unsafeRunSync()

object TestFiles:
  def remove(root: Path): IO[Unit] = IO.blocking {
    val walk = Files.walk(root)
    try walk.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(p => Files.deleteIfExists(p))
    finally walk.close()
  }
