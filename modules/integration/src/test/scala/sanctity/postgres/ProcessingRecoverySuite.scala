package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import java.nio.file.Path

final class ProcessingRecoverySuite extends FunSuite:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get

  test("publication rolls back atomically and resumes across independent pools and a closed recovery gate") {
    PostgresFixture.resource.use { f =>
      val s = f.system
      val ids = IdSource.random[IO]
      val derivatives = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
      val submissions = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, ids)
      val inputs = new ProcessingInputs[IO](s.catalog, s.repository,
        new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids), new MriInputPlanner)
      for
        imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        original <- checked(s.repository.accept(s.context, imported.candidate.id, key("source")))
        selection <- checked(s.catalog.select(s.context, Vector(original.release), ScientificQuery(artifacts = ArtifactPredicate.All(
          Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
        inputDataset <- s.repository.create(s.context, s.dataset.custodian)
        prepared <- checked(inputs.preparePrivate(s.context, original.release, selection.id, inputDataset.id,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), MriInputPlanner.Contract))
        bundle <- checked(s.repository.accept(s.context, prepared.candidate.id, key("private")))
        old <- s.repository.create(s.context, s.dataset.custodian)
        destination <- s.repository.create(s.context, s.dataset.custodian)
        producer = new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3"))
        result <- checked(new LocalQcRuns[IO](s.catalog, s.store, producer).execute(s.context,
          RunSpec(bundle.release, prepared.candidate.activity.bundle.get.id, old.id, None, MriInputPlanner.Contract), key("run"), f.root.resolve("producer")))
        _ <- sanctity.app.TestFiles.remove(f.root.resolve("producer"))
        intent = PublicationIntent(result.invocation, result.id, s.context.principal, destination.id, None, key("publication"))
        before <- sql"SELECT count(*) FROM draft".query[Long].unique.transact(f.database.transactor)
        _ <- sql"ALTER TABLE processing_publication ADD CONSTRAINT injected_failure CHECK (request_key <> 'publication')".update.run.transact(f.database.transactor)
        failed <- submissions.publish(s.context, intent)
        _ = assert(failed.isLeft)
        after <- sql"SELECT count(*) FROM draft".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(after, before)
        bindings <- sql"SELECT count(*) FROM processing_publication".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(bindings, 0L)
        _ <- sql"ALTER TABLE processing_publication DROP CONSTRAINT injected_failure".update.run.transact(f.database.transactor)
        draft <- PostgresDatabase.open[IO](f.config).use { other =>
          val catalog = other.catalog(ids)
          val repo = new NativeRepository[IO](catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
          val independent = new DerivativeSubmissions[IO](catalog, s.store, repo, ids)
          (checked(submissions.publish(s.context, intent)), checked(independent.publish(s.context, intent))).parTupled.map { (a, b) =>
            assertEquals(a.id, b.id)
            a
          }
        }
        _ <- checked(submissions.collectStaged(s.context, draft.id, result.expected.head.path))
        policy <- f.database.recovery.capturePolicy
        _ <- f.database.recovery.requireReconciliation
        closed <- submissions.publish(s.context, intent)
        closedRun <- s.catalog.completedRun(s.context, result.invocation)
        _ = assert(closed.isLeft && closedRun.isLeft)
        _ <- checked(f.database.recovery.reconcile(policy, policy.digest, s.store))
        accepted <- PostgresDatabase.open[IO](f.config).use { fresh =>
          val catalog = fresh.catalog(ids)
          val repo = new NativeRepository[IO](catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
          val resumed = new DerivativeSubmissions[IO](catalog, s.store, repo, ids)
          for
            recovered <- checked(resumed.publish(s.context, intent))
            _ = assertEquals(recovered.id, draft.id)
            _ = assertEquals(recovered.manifest.files.size, 1)
            _ <- result.expected.traverse_(o => checked(resumed.collectStaged(s.context, draft.id, o.path)))
            preview <- checked(resumed.prepare(s.context, draft.id))
            accepted <- checked(repo.accept(s.context, preview.candidate.id, key("accepted-publication")))
          yield accepted
        }
        retry <- checked(derivatives.accept(s.context, accepted.candidate, key("accepted-publication")))
        _ = assertEquals(retry, accepted)
        counts <- sql"""SELECT (SELECT count(*) FROM processing_publication),
          (SELECT count(*) FROM outbox WHERE dataset_id=${Records.uuid(destination.id)}),
          (SELECT count(*) FROM release WHERE dataset_id=${Records.uuid(destination.id)})"""
          .query[(Long, Long, Long)].unique.transact(f.database.transactor)
        _ = assertEquals(counts, (1L, 1L, 1L))
        release <- checked(s.catalog.release(s.context, accepted.release))
        _ = assertEquals(release.activity.publication, Some(intent))
        _ = assertEquals(release.manifest.files.map(f => f.path -> f.blob).toMap, result.expected.map(f => f.path -> f.blob).toMap)
      yield ()
    }.unsafeRunSync()
  }

  test("pending declared outputs do not poison recovery integrity verification") {
    PostgresFixture.resource.use { f =>
      val s = f.system
      val ids = IdSource.random[IO]
      val derivatives = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
      val submissions = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, ids)
      val inputs = new ProcessingInputs[IO](s.catalog, s.repository,
        new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids), new MriInputPlanner)
      for
        imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        original <- checked(s.repository.accept(s.context, imported.candidate.id, key("original")))
        selection <- checked(s.catalog.select(s.context, Vector(original.release), ScientificQuery(artifacts = ArtifactPredicate.All(
          Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
        inputDataset <- s.repository.create(s.context, s.dataset.custodian)
        prepared <- checked(inputs.prepare(s.context, original.release, selection.id, inputDataset.id,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), MriInputPlanner.Contract))
        bundle <- checked(s.repository.accept(s.context, prepared.candidate.id, key("bundle")))
        producer = new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3"))
        actual <- checked(producer.produce(s.context, bundle.release, f.root.resolve("producer")))
        absent = BlobRef.from(Digest.bytes("future-remote-output".getBytes), 20).toOption.get
        receipt = actual.copy(expected = Vector(OutputFile(RelPath.from("qc.tsv").toOption.get, absent)))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        draft <- checked(submissions.begin(s.context, destination.id, receipt))
        registered <- sql"SELECT count(*) FROM blob WHERE digest=${absent.digest.value}".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(registered, 0L)
        snapshot <- f.database.recovery.capturePolicy
        _ <- f.database.recovery.requireReconciliation
        _ <- checked(f.database.recovery.reconcile(snapshot, snapshot.digest, s.store))
        pending <- checked(s.catalog.readDraft(s.context, draft.id))
        _ = assertEquals(pending.activity.processing.map(_.id), Some(receipt.id))
        _ = assert(pending.manifest.files.isEmpty)
        refused <- submissions.prepare(s.context, draft.id)
        _ = assert(refused.isLeft)
      yield ()
    }.unsafeRunSync()
  }

  test("completed runs survive fresh catalogs and recovery before collection; observation indexes gate acceptance") {
    PostgresFixture.resource.use { f =>
      val s = f.system
      val ids = IdSource.random[IO]
      val derivatives = new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids)
      val submissions = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives, ids)
      val inputs = new ProcessingInputs[IO](s.catalog, s.repository,
        new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids), new MriInputPlanner)
      for
        imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        original <- checked(s.repository.accept(s.context, imported.candidate.id, key("source")))
        selection <- checked(s.catalog.select(s.context, Vector(original.release), ScientificQuery(artifacts = ArtifactPredicate.All(
          Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
        inputDataset <- s.repository.create(s.context, s.dataset.custodian)
        prepared <- checked(inputs.preparePrivate(s.context, original.release, selection.id, inputDataset.id,
          DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), MriInputPlanner.Contract))
        bundle <- checked(s.repository.accept(s.context, prepared.candidate.id, key("private")))
        destination <- s.repository.create(s.context, s.dataset.custodian)
        spec = RunSpec(bundle.release, prepared.candidate.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract)
        producer = new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3"))
        starts <- (checked(s.catalog.startRun(s.context, spec, key("run"))), checked(s.catalog.startRun(s.context, spec, key("run")))).parTupled
        _ = assertEquals(starts._1.id, starts._2.id)
        actual <- checked(new LocalQcRuns[IO](s.catalog, s.store, producer).execute(s.context, spec, key("run"), f.root.resolve("producer")))
        _ <- sanctity.app.TestFiles.remove(f.root.resolve("producer"))
        fresh = f.database.catalog(ids)
        recovered <- checked(fresh.readRun(s.context, actual.invocation))
        _ = assertEquals(recovered.result.map(_.id), Some(actual.id))
        registered <- actual.expected.traverse(o => sql"SELECT count(*) FROM blob WHERE digest=${o.blob.digest.value}".query[Long].unique.transact(f.database.transactor))
        _ = assert(registered.forall(_ == 1L))
        policy <- f.database.recovery.capturePolicy
        _ <- f.database.recovery.requireReconciliation
        closed <- fresh.readRun(s.context, actual.invocation)
        _ = assert(closed.isLeft)
        _ <- checked(f.database.recovery.reconcile(policy, policy.digest, s.store))
        restored <- checked(fresh.readRun(s.context, actual.invocation))
        draft <- checked(submissions.beginStaged(s.context, destination.id, restored.result.get))
        _ <- actual.expected.traverse_(o => checked(submissions.collectStaged(s.context, draft.id, o.path)))
        preview <- checked(submissions.prepare(s.context, draft.id))
        interpretation = preview.candidate.interpretation
        _ <- sql"DELETE FROM scientific_observation WHERE interpretation_id=${interpretation.id.value.value} AND ordinal=0".update.run.transact(f.database.transactor)
        incomplete <- derivatives.accept(s.context, preview.candidate.id, key("accepted"))
        _ = assert(incomplete.isLeft)
        head <- checked(s.catalog.dataset(s.context, destination.id))
        _ = assertEquals(head.head, None)
        _ <- (sql"DELETE FROM scientific_observation WHERE interpretation_id=${interpretation.id.value.value}".update.run *> ScientificSql.store(interpretation)).transact(f.database.transactor)
        accepted <- checked(derivatives.accept(s.context, preview.candidate.id, key("accepted")))
        observation = interpretation.observations.head
        found <- checked(fresh.observations(s.context, Vector(accepted.release), ObservationQuery(Vector(observation.source), observation.metric)))
        _ = assertEquals(found.map(_.observation), Vector(observation))
      yield ()
    }.unsafeRunSync()
  }
