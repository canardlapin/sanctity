package sanctity.postgres

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import doobie.implicits.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.*
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource

final class OperationsSuite extends FunSuite:
  private def checked[A](action: IO[Either[Problem, A]]): IO[A] = action.flatMap(Checked.lift[IO, A])
  private def key(text: String): IdempotencyKey = IdempotencyKey.from(text).toOption.get

  test("interrupted imports resume verified files and retain candidate and publication identity") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val s = f.system
      val journal = f.database.journal(ids)
      val importer = new DurableImports[IO](s.catalog, journal, s.store, s.repository, ids)
      val source = new NativeDirectorySource[IO](f.root.resolve("source"))
      for
        reads <- Ref.of[IO, Map[RelPath, Int]](Map.empty)
        attempts <- Ref.of[IO, Int](0)
        wrapped = new NativeSource[IO]:
          val label: String = source.label
          def entries: Stream[IO, SourceEntry] = source.entries
          def read(file: SourceEntry.File): Stream[IO, Byte] =
            Stream.exec(reads.update(m => m.updated(file.path, m.getOrElse(file.path, 0) + 1))) ++
              Stream.eval(attempts.updateAndGet(_ + 1)).flatMap { attempt =>
                if attempt == 3 then Stream.raiseError[IO](Failure(Problem.Operational("injected transfer failure")))
                else source.read(file)
              }
        op <- checked(importer.begin(s.context, s.dataset.id, key("resumable"), wrapped))
        failed <- importer.resume(s.context, op.id, wrapped)
        _ = assert(failed.isLeft)
        prepared <- checked(importer.resume(s.context, op.id, wrapped))
        _ = assertEquals(prepared.acquired.size, op.inventory.count(_.isInstanceOf[SourceEntry.File]))
        before <- reads.get
        bypass <- s.repository.accept(s.context, prepared.candidate.get, key("bypass"))
        _ = assert(bypass.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        done <- checked(importer.resume(s.context, op.id, wrapped, publish = true))
        _ = assert(done.receipt.nonEmpty)
        _ = assertEquals(done.candidate, prepared.candidate)
        after <- reads.get
        _ = assertEquals(after, before)
        repeated <- checked(importer.resume(s.context, op.id, wrapped, publish = true))
        _ = assertEquals(repeated.receipt, done.receipt)
        count <- sql"SELECT count(*) FROM release".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(count, 1L)
      yield ()
    }.unsafeRunSync()
  }

  test("expired worker cannot checkpoint or publish after a replacement claims its operation") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val s = f.system
      val journal = f.database.journal(ids)
      val importer = new DurableImports[IO](s.catalog, journal, s.store, s.repository, ids)
      val source = new NativeDirectorySource[IO](f.root.resolve("source"))
      for
        op <- checked(importer.begin(s.context, s.dataset.id, key("fenced"), source))
        prepared <- checked(importer.resume(s.context, op.id, source))
        old <- checked(journal.claim(s.context, op.id, 60))
        busy <- journal.claim(s.context, op.id, 60)
        _ = assert(busy.isLeft)
        _ <- sql"UPDATE import_operation SET lease_until=clock_timestamp()-interval '1 second'".update.run.transact(f.database.transactor)
        replacement <- checked(journal.claim(s.context, op.id, 60))
        stale <- s.repository.accept(s.context, prepared.candidate.get, key("fenced-accept"), Some(old))
        _ = assert(stale.isLeft)
        staleCheckpoint <- journal.record(s.context, old, prepared.acquired.head)
        _ = assert(staleCheckpoint.isLeft)
        receipt <- checked(s.repository.accept(s.context, prepared.candidate.get, key("fenced-accept"), Some(replacement)))
        _ <- checked(journal.finish(s.context, replacement, receipt))
        done <- checked(journal.get(s.context, op.id))
        _ = assertEquals(done.receipt, Some(receipt))
      yield ()
    }.unsafeRunSync()
  }

  test("outbox targets retry independently and stale acknowledgements cannot complete a new lease") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val s = f.system
      val outbox = f.database.outbox(ids)
      for
        preview <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        receipt <- checked(s.repository.accept(s.context, preview.candidate.id, key("initial")))
        first <- checked(outbox.claim(s.context, s.dataset.id, "neurobagel-fixture", 60)).map(_.get)
        none <- checked(outbox.claim(s.context, s.dataset.id, "neurobagel-fixture", 60))
        _ = assertEquals(none, None)
        independent <- checked(outbox.claim(s.context, s.dataset.id, "other-fixture", 60)).map(_.get)
        _ = assertEquals(independent.release, receipt.release)
        _ <- sql"UPDATE outbox_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE target='neurobagel-fixture'".update.run.transact(f.database.transactor)
        second <- checked(outbox.claim(s.context, s.dataset.id, "neurobagel-fixture", 60)).map(_.get)
        stale <- outbox.acknowledge(s.context, first)
        _ = assert(stale.isLeft)
        _ <- checked(outbox.acknowledge(s.context, second))
        _ <- checked(outbox.acknowledge(s.context, independent))
        done <- checked(outbox.claim(s.context, s.dataset.id, "neurobagel-fixture", 60))
        _ = assertEquals(done, None)
      yield ()
    }.unsafeRunSync()
  }


  test("response loss after acceptance reconciles the journal without rereading a changed source") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val s = f.system
      val journal = f.database.journal(ids)
      val importer = new DurableImports[IO](s.catalog, journal, s.store, s.repository, ids)
      val source = new NativeDirectorySource[IO](f.root.resolve("source"))
      for
        op <- checked(importer.begin(s.context, s.dataset.id, key("response-loss"), source))
        prepared <- checked(importer.resume(s.context, op.id, source))
        lease <- checked(journal.claim(s.context, op.id, 60))
        receipt <- checked(s.repository.accept(s.context, prepared.candidate.get, op.key, Some(lease)))
        _ <- journal.surrender(s.context, lease)
        unavailable = new NativeSource[IO]:
          val label: String = source.label
          def entries: Stream[IO, SourceEntry] = Stream.raiseError[IO](new IllegalStateException("must not re-inventory committed work"))
          def read(file: SourceEntry.File): Stream[IO, Byte] = Stream.raiseError[IO](new IllegalStateException("must not reacquire committed work"))
        recovered <- checked(importer.resume(s.context, op.id, unavailable, publish = true))
        _ = assertEquals(recovered.receipt, Some(receipt))
      yield ()
    }.unsafeRunSync()
  }

  test("recovery gate denies reads, writes and worker claims") {
    PostgresFixture.resource.use { f =>
      val s = f.system
      for
        _ <- sql"UPDATE repository_state SET recovery_required=TRUE".update.run.transact(f.database.transactor)
        denied <- s.catalog.dataset(s.context, s.dataset.id)
        _ = assert(denied.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
        create <- s.repository.create(s.context, s.dataset.custodian).attempt
        _ = assert(create.isLeft)
        outbox <- f.database.outbox(IdSource.random[IO]).claim(s.context, s.dataset.id, "fixture", 60)
        _ = assert(outbox.isLeft)
      yield ()
    }.unsafeRunSync()
  }
