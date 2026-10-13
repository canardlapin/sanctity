package sanctity.app

import bids4s.*
import cats.effect.{IO, Ref}
import fs2.Stream
import sanctity.application.*
import sanctity.bids.{BidsChangeCompiler, BidsInterpreter, MriViewCompiler}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import java.nio.file.{Files, Path}

/** The same profile-evolution laws are exercised by both catalog implementations. */
abstract class CatalogContextSuite extends CatalogMriSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](a: IO[Either[Problem, A]]): IO[A] = a.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val custom = "sub-01/perfusion/sub-01_perfusion.nii"
  private val spec = BidsDatatypeSpec("perfusion", "perfusion", DatatypeScope.Raw,
    Vector(EntityRule(EntityKey.Subject, required = true)), Vector(BidsKind.from("perfusion", Vector("nii", "json")).toOption.get))
  private val context = BidsContext.from("pilot-perfusion-v1", BidsRegistry.Builtin.withDatatype(spec), Some("1.10.0"),
    "Experimental perfusion filename registry; no perfusion acquisition qualification").toOption.get
  private def importRelease(s: RepositorySystem, root: Path): IO[Release] =
    for
      preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("initial-context")))
      release <- checked(s.catalog.release(s.context, receipt.release))
    yield release
  private def next(s: RepositorySystem, store: BlobStore[IO]): NativeRepository[IO] =
    new NativeRepository[IO](s.catalog, store, new BidsInterpreter[IO](store, context), IdSource.random[IO])

  test("explicit profile adoption preserves bytes, old query meaning, explanations and assessments") {
    fixture { (s, root) =>
      for
        _ <- IO.blocking {
          val out = root.resolve("source").resolve(custom)
          Files.createDirectories(out.getParent)
          val _ = Files.copy(root.resolve("source").resolve(SyntheticFixture.firstImage), out)
        }
        old <- importRelease(s, root)
        query = ScientificQuery(artifacts = ArtifactPredicate.Suffix("perfusion"))
        frozen <- checked(s.catalog.select(s.context, Vector(old.ref), query))
        _ = assertEquals(frozen.hits.size, 0)
        writes <- Ref.of[IO, Int](0)
        tracked = new BlobStore[IO]:
          def put(bytes: Stream[IO, Byte]): IO[BlobRef] = writes.update(_ + 1) *> s.store.put(bytes)
          def read(blob: BlobRef): Stream[IO, Byte] = s.store.read(blob)
          def verify(blob: BlobRef): IO[Unit] = s.store.verify(blob)
        repository = next(s, tracked)
        preview <- checked(repository.reinterpret(s.context, old.ref, "Adopt the qualified filename extension profile"))
        _ = assertEquals(preview.contentChanges, Vector.empty)
        _ = assertEquals(preview.candidate.manifest.id, old.manifest.id)
        _ = assertNotEquals(preview.candidate.interpretation.id, old.interpretation.id)
        _ = assertNotEquals(preview.candidate.assessment.id, old.assessment.id)
        _ = assertEquals(preview.candidate.interpretation.declaredBidsVersion, Some("1.10.0"))
        _ = assertEquals(old.interpretation.profile.context.flatMap(_.specificationVersion), None)
        _ = assertEquals(preview.candidate.interpretation.profile.context, Some(BidsInterpreter.describe(context)))
        receipt <- checked(repository.accept(s.context, preview.candidate.id, key("adopt-profile")))
        retry <- checked(repository.accept(s.context, preview.candidate.id, key("adopt-profile")))
        _ = assertEquals(retry, receipt)
        fresh <- checked(s.catalog.release(s.context, receipt.release))
        _ = assertEquals(fresh.activity.kind, ActivityKind.Reinterpretation)
        _ = assertEquals(fresh.manifest.entries, old.manifest.entries)
        _ = assert(new BidsChangeCompiler(context).documents(fresh, Vector(FileEdit.Remove(p(SyntheticFixture.firstImage)))).isRight)
        _ = assert(new BidsChangeCompiler().documents(fresh, Vector(FileEdit.Remove(p(SyntheticFixture.firstImage)))).isLeft)
        hits <- checked(s.catalog.select(s.context, Vector(fresh.ref), query))
        _ = assertEquals(hits.hits.map(_.path), Vector(p(custom)))
        oldAgain <- checked(s.catalog.release(s.context, old.ref))
        selection <- checked(s.catalog.selection(s.context, frozen.id))
        oldQuery <- checked(s.catalog.select(s.context, Vector(old.ref), query))
        _ = assertEquals(selection.hits, frozen.hits)
        _ = assertEquals(oldQuery.hits, frozen.hits)
        _ = assertEquals(oldAgain.interpretation.id, old.interpretation.id)
        _ = assertEquals(oldAgain.interpretation.artifacts, old.interpretation.artifacts)
        _ = assertEquals(oldAgain.assessment.id, old.assessment.id)
        _ <- checked(repository.exportFull(s.context, old.ref, new DirectoryExport[IO](root.resolve("old-context"))))
        _ <- checked(repository.exportFull(s.context, fresh.ref, new DirectoryExport[IO](root.resolve("new-context"))))
        _ <- IO.blocking(old.manifest.files.foreach { f =>
          assertEquals(Files.mismatch(root.resolve("old-context").resolve(f.path.value), root.resolve("new-context").resolve(f.path.value)), -1L)
        })
        count <- writes.get
        _ = assertEquals(count, 0)
      yield ()
    }
  }

  test("edits and view compilers reject context drift until explicit adoption") {
    fixture { (s, root) =>
      for
        old <- importRelease(s, root)
        selection <- checked(s.catalog.select(s.context, Vector(old.ref), ScientificQuery()))
        edit = Vector(FileEdit.Remove(p(SyntheticFixture.firstImage)))
        _ = assert(new BidsChangeCompiler(context).documents(old, edit).isLeft)
        _ = assert(new MriViewCompiler(context).documents(old, selection,
          DisclosureProfile(Set.empty, Set.empty)).isLeft)
        result <- next(s, s.store).correctMetadata(s.context, old.ref, p(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson, "Profile drift")
        _ = assert(result.swap.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        head <- checked(s.catalog.dataset(s.context, s.dataset.id))
        _ = assertEquals(head.head, Some(old.ref.release))
      yield ()
    }
  }

  test("reinterpretation retains ordinary authorization, acceptance and stale-head checks") {
    fixture { (s, root) =>
      for
        old <- importRelease(s, root)
        principal <- IdSource.random[IO].next[PrincipalTag]
        denied <- next(s, s.store).reinterpret(AccessContext(principal), old.ref, "Unauthorized")
        _ = assertEquals(denied, Left(Problem.Denied))
        preview <- checked(next(s, s.store).reinterpret(s.context, old.ref, "Profile upgrade"))
        competing <- checked(s.repository.correctMetadata(s.context, old.ref, p(SyntheticFixture.sharedSidecar), SyntheticFixture.correctedJson, "Concurrent correction"))
        _ <- checked(s.repository.accept(s.context, competing.candidate.id, key("competing")))
        stale <- next(s, s.store).accept(s.context, preview.candidate.id, key("stale-profile"))
        _ = assert(stale.swap.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        changed = old.manifest.change(Vector(Change.Remove(p("README")))).toOption.get
        _ <- checked(s.catalog.revise(s.context, preview.candidate.draft, preview.candidate.generation, changed, preview.candidate.activity))
        invalid <- next(s, s.store).prepare(s.context, preview.candidate.draft)
        _ = assert(invalid.swap.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        changedMeaning <- new BidsInterpreter[IO](s.store, context).prepare(changed)
        bypass <- s.catalog.prepared(s.context, preview.candidate.draft, preview.candidate.generation + 1,
          changedMeaning._1, changedMeaning._2)
        _ = assert(bypass.swap.toOption.exists(_.isInstanceOf[Problem.Conflict]))
      yield ()
    }
  }
