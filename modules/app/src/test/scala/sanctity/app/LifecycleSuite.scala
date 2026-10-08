package sanctity.app

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.{Checked, Failure, IdSource}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

abstract class CatalogLifecycleSuite extends FunSuite:
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
            val _ = Files.copy(source.resolve(from), target)
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
      val compiler = new sanctity.bids.MriViewCompiler
      val profile = DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime"))
      val query = ScientificQuery(artifacts = ArtifactPredicate.Entity("sub", "01"))
      for
        first <- accepted(s, root)
        release <- checked(s.repository.release(s.context, first.release))
        selection <- checked(s.catalog.select(s.context, Vector(first.release), query))
        a <- Checked.lift[IO, ProjectionPlan](compiler.plan(release, selection, profile))
        edit <- correction(s, first.release, """{"TaskName":"rest","RepetitionTime":1.5,"PrivateNote":"excluded information"}""")
        second <- checked(s.repository.accept(s.context, edit.candidate.id, key("hidden-change")))
        newer <- checked(s.repository.release(s.context, second.release))
        newSelection <- checked(s.catalog.select(s.context, Vector(second.release), query))
        b <- Checked.lift[IO, ProjectionPlan](compiler.plan(newer, newSelection, profile))
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
        edit <- correction(s, initial.release)
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
        result = new sanctity.bids.MriViewCompiler().plan(release, selection,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")))
        _ = assert(result.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
        closure <- Checked.lift[IO, RelationshipClosure](release.interpretation.relationshipClosure(Vector(path(SyntheticFixture.firstImage))))
        _ = assert(closure.artifacts.contains(path(image)))
        legacy <- Checked.lift[IO, Interpretation](Interpretation.from(release.manifest.id, release.interpretation.profile,
          release.interpretation.artifacts, release.interpretation.facts, release.interpretation.participants, release.interpretation.queryVersion))
        unsupported = new sanctity.bids.MriViewCompiler().plan(release.copy(interpretation = legacy), selection,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")))
        _ = assert(unsupported.left.toOption.exists(_.isInstanceOf[Problem.Unsupported]))
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
