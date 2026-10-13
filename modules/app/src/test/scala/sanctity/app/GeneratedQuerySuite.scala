package sanctity.app

import cats.effect.IO
import cats.syntax.all.*
import sanctity.application.*
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.file.Path

abstract class CatalogGeneratedQuerySuite extends CatalogTemporalSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def p(s: String): RelPath = RelPath.from(s).toOption.get

  test("seeded scientific query oracle agrees with frozen membership, history, paging and current authority") {
    fixture { (s, root) =>
      val seed = 17029L
      val corpora = Vector(QueryOracle.generate(seed, 4), QueryOracle.generate(seed + 1, 4))
      val cases = QueryOracle.queries(seed)
      def expected(refs: Vector[ReleaseRef], data: Vector[QueryOracle.Corpus], query: ScientificQuery): Vector[SelectionHit] =
        refs.zip(data).flatMap { (ref, corpus) =>
          corpus.expected(query).map(i => SelectionHit(ref, p(i.path), "sub-" + i.subject, i.session.map("ses-" + _)))
        }.sortBy(h => (h.at.dataset.render, h.at.release.render, h.path.value))
      for
        refs <- corpora.zipWithIndex.traverse { (corpus, index) =>
          for
            _ <- corpus.install(root.resolve(s"oracle-$index"))
            destination <- s.repository.create(s.context, s.dataset.custodian)
            preview <- checked(s.repository.importNative(s.context, destination.id, new NativeDirectorySource[IO](root.resolve(s"oracle-$index"))))
            _ = assert(preview.candidate.assessment.admissible, s"seed=${corpus.seed}: ${preview.candidate.assessment.errors}")
            accepted <- checked(s.repository.accept(s.context, preview.candidate.id, key(s"oracle-$index")))
          yield accepted.release
        }
        reader <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
        grants <- refs.traverse(ref => checked(s.catalog.grant(s.context, ref.dataset, reader.principal, Some(ref.release), Set(Action.ReadMetadata))))
        selections <- cases.traverse { c =>
          for
            selection <- checked(s.catalog.select(reader, refs.reverse, c.query))
            _ = assertEquals(selection.hits, expected(refs, corpora, c.query), s"seed=$seed case=${c.name} query=${c.query} corpus=${corpora.map(_.images)}")
            stored <- checked(s.catalog.selection(reader, selection.id))
            _ = assertEquals(stored.hits, selection.hits)
            _ = {
              val pages = (0 until (stored.hits.size + 7) by 7).toVector.flatMap(offset => stored.page(offset, 7).toOption.get)
              assertEquals(pages, stored.hits, s"seed=$seed case=${c.name} paging")
              assertEquals(pages.distinct, pages)
            }
          yield selection
        }
        original = selections.head
        path = corpora.head.images.head.path
        changed = corpora.head.change(path)
        edit <- checked(s.repository.correctMetadata(s.context, refs.head, p(corpora.head.images.head.sidecar),
          changed.images.find(_.path == path).get.document.noSpaces, "Seeded query history correction"))
        accepted <- checked(s.repository.accept(s.context, edit.candidate.id, key("oracle-correction")))
        historical <- checked(s.catalog.selection(reader, original.id))
        _ = assertEquals(historical.hits, original.hits)
        _ <- cases.traverse_ { c =>
          checked(s.catalog.select(s.context, Vector(refs.head, accepted.release), c.query)).map { selection =>
            assertEquals(selection.hits, expected(Vector(refs.head, accepted.release), Vector(corpora.head, changed), c.query),
              s"seed=$seed case=${c.name} historical/current releases")
          }
        }
        latestDenied <- s.catalog.select(reader, Vector(refs.head, accepted.release), ScientificQuery())
        _ = assertEquals(latestDenied, Left(Problem.Denied))
        _ <- checked(s.catalog.revoke(s.context, refs(1).dataset, grants(1).id))
        denied <- s.catalog.selection(reader, original.id)
        recomputed <- s.catalog.select(reader, refs, ScientificQuery())
        _ = assertEquals(denied, Left(Problem.Denied))
        _ = assertEquals(recomputed, Left(Problem.Denied))
      yield ()
    }
  }
