package sanctity.postgres

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import sanctity.processing.MriQcProducer
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

final class ContinuingAuthoritySuite extends FunSuite:
  import JsonCodecs.given
  override val munitTimeout = 2.minutes
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def key(value: String): IdempotencyKey = IdempotencyKey.from(value).toOption.get
  private def bootstrap(f: PgFixture): IO[Unit] = for
    preview <- f.database.organization.preview(f.system.context.principal)
    _ <- f.database.organization.bootstrap(f.system.context.principal, PostgresOrganizationCatalog.previewDigest(preview))
  yield ()
  private def disable(f: PgFixture, principal: PrincipalId): IO[Unit] = for
    before <- f.database.organization.snapshot
    ref = OrganizationRef.Principal(principal)
    _ <- checked(f.database.organization.execute(f.system.context, OrganizationMutation(key("offboard"),
      Map(ref -> before.generation(ref).get), OrganizationCommand.DisablePrincipal(principal))))
  yield ()

  private case class Prepared(reader: AccessContext, input: ReleaseRef, destination: DatasetId,
      sourceGrant: Grant, destinationGrant: Grant, evidence: ProcessingEvidence, spec: RunSpec)
  private def prepare(f: PgFixture): IO[Prepared] =
    val s = f.system
    val ids = IdSource.random[IO]
    val inputs = new ProcessingInputs[IO](s.catalog, s.repository,
      new ScientificViews[IO](s.catalog, s.store, s.repository, new MriViewCompiler, ids), new MriInputPlanner)
    for
      imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
      original <- checked(s.repository.accept(s.context, imported.candidate.id, key("source")))
      selection <- checked(s.catalog.select(s.context, Vector(original.release), ScientificQuery(artifacts = ArtifactPredicate.All(
        Vector(ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
      inputDataset <- s.repository.create(s.context, s.dataset.custodian)
      prepared <- checked(inputs.prepare(s.context, original.release, selection.id, inputDataset.id,
        DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), MriInputPlanner.Contract))
      bundle <- checked(s.repository.accept(s.context, prepared.candidate.id, key("bundle")))
      destination <- s.repository.create(s.context, s.dataset.custodian)
      reader <- ids.next[PrincipalTag].map(AccessContext(_))
      sourceGrant <- checked(s.catalog.grant(s.context, inputDataset.id, reader.principal, Some(bundle.release.release), Set(Action.ReadMetadata, Action.ReadContent)))
      destinationGrant <- checked(s.catalog.grant(s.context, destination.id, reader.principal, None, Set(Action.ReadMetadata, Action.Propose)))
      evidence <- checked(new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3"))
        .produce(reader, bundle.release, f.root.resolve("producer")))
      _ <- bootstrap(f)
    yield Prepared(reader, bundle.release, destination.id, sourceGrant, destinationGrant, evidence,
      RunSpec(bundle.release, prepared.candidate.activity.bundle.get.id, destination.id, None, MriInputPlanner.Contract))

  for loss <- Vector("offboarding", "credential expiry") do
    test(s"local runner kills its real child within five seconds of $loss and retains failure") {
      PostgresFixture.resource.use { f =>
        val s = f.system
        val ids = IdSource.random[IO]
        val workspace = f.root.resolve("waiting")
        def ready: IO[Unit] = IO.blocking(Files.exists(workspace.resolve("pid"))).flatMap(if _ then IO.unit else IO.sleep(10.millis) *> ready)
        for
          p <- prepare(f)
          token <- checked(f.database.credentials(ids).issue(p.reader.principal, "worker", 600))
          ctx <- checked(f.database.credentials(ids).authenticate(token.secret))
          run <- checked(s.catalog.startRun(ctx, p.spec, key("waiting-run")))
          worker <- new sanctity.processing.LocalProcessingRuns[IO](s.catalog, s.store, new sanctity.processing.WaitingRecipe(p.evidence))
            .execute(ctx, p.spec, key("waiting-run"), workspace).start
          _ <- ready.timeout(10.seconds)
          pid <- IO.blocking(Files.readString(workspace.resolve("pid")).toLong)
          _ <- if loss == "offboarding" then disable(f, p.reader.principal) else
            sql"UPDATE api_credential SET issued_at=clock_timestamp()-interval '2 hours', expires_at=clock_timestamp()-interval '1 hour' WHERE id=${Records.uuid(token.id)}".update.run.transact(f.database.transactor).void
          revoked <- IO.monotonic
          result <- worker.joinWithNever.timeout(5.seconds).guarantee(worker.cancel)
          stopped <- IO.monotonic
          alive <- IO.blocking(java.lang.ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false))
          _ = assertEquals(result, Left(Problem.Denied))
          _ = assert(!alive && stopped - revoked <= 5.seconds)
          payload <- sql"SELECT payload::text FROM processing_run WHERE id=${Records.uuid(run.id)}".query[String].unique.transact(f.database.transactor)
          retained <- checked(IO.pure(JsonCodecs.decode[ProcessingRun](payload)))
          _ = assertEquals(retained.status, RunStatus.Failed)
          _ <- IO.println(s"F03.4 $loss termination ms=${(stopped - revoked).toMillis}")
        yield ()
      }.unsafeRunSync()
    }

  test("cached completed work rechecks source use and destination deposit independently") {
    PostgresFixture.resource.use { f =>
      val s = f.system
      val ids = IdSource.random[IO]
      for
        p <- prepare(f)
        runner = new sanctity.processing.LocalQcRuns[IO](s.catalog, s.store,
          new MriQcProducer[IO](s.catalog, s.store, s.repository, ids, Path.of("/usr/bin/python3")))
        result <- checked(runner.execute(p.reader, p.spec, key("completed"), f.root.resolve("completed")))
        _ <- checked(s.catalog.revoke(s.context, p.destination, p.destinationGrant.id))
        cached <- runner.execute(p.reader, p.spec, key("completed"), f.root.resolve("never-created"))
        _ = assertEquals(cached, Left(Problem.Denied))
        retained <- checked(s.catalog.completedRun(p.reader, result.invocation))
        _ = assertEquals(retained.status, RunStatus.Completed)
        _ <- checked(s.catalog.revoke(s.context, p.input.dataset, p.sourceGrant.id))
        denied <- s.catalog.completedRun(p.reader, result.invocation)
        _ = assertEquals(denied, Left(Problem.Denied))
        _ <- checked(s.catalog.grant(s.context, p.input.dataset, p.reader.principal, Some(p.input.release), Set(Action.ReadMetadata, Action.ReadContent)))
        restored <- checked(s.catalog.completedRun(p.reader, result.invocation))
        _ = assertEquals(restored, retained)
        _ = assert(!Files.exists(f.root.resolve("never-created")))
      yield ()
    }.unsafeRunSync()
  }

  for loss <- Vector("input", "destination", "offboarding", "expiry") do
    test(s"worker heartbeat fails an active run after $loss; no outputs or metadata escape") {
      PostgresFixture.resource.use { f =>
        val c = f.system.catalog
        for
          p <- prepare(f)
          run <- checked(c.startRun(p.reader, p.spec, key("run")))
          preparing <- checked(c.advanceRun(p.reader, run.id, run.generation, RunUpdate(RunStatus.Preparing)))
          execution = ExecutionEvidence(p.evidence.producer, p.evidence.request, p.evidence.targets)
          running <- checked(c.advanceRun(p.reader, run.id, preparing.generation, RunUpdate(RunStatus.Executing, Some(execution))))
          _ <- checked(c.heartbeatRun(p.reader, run.id))
          _ <- loss match
            case "input" => checked(c.revoke(f.system.context, p.input.dataset, p.sourceGrant.id))
            case "destination" => checked(c.revoke(f.system.context, p.destination, p.destinationGrant.id))
            case "offboarding" => disable(f, p.reader.principal)
            case _ => sql"UPDATE access_grant SET created_at=clock_timestamp()-interval '2 hours', expires_at=clock_timestamp()-interval '1 hour' WHERE id=${Records.uuid(p.sourceGrant.id)}".update.run.transact(f.database.transactor).void
          denied <- c.heartbeatRun(p.reader, run.id)
          _ = assertEquals(denied, Left(Problem.Denied))
          record <- sql"SELECT payload::text FROM processing_run WHERE id=${Records.uuid(run.id)}".query[String].unique.transact(f.database.transactor)
          state <- checked(IO.pure(JsonCodecs.decode[ProcessingRun](record)))
          _ = assertEquals(state.status, RunStatus.Failed)
          _ = assert(state.valid && state.result.isEmpty)
          completed <- c.advanceRun(p.reader, run.id, running.generation,
            RunUpdate(RunStatus.Completed, Some(execution), Some(p.evidence.copy(invocation = run.id))))
          status <- c.readRun(p.reader, run.id)
          _ = assert(completed.isLeft && status.isLeft)
          _ <- loss match
            case "input" => checked(c.grant(f.system.context, p.input.dataset, p.reader.principal, Some(p.input.release), Set(Action.ReadMetadata, Action.ReadContent))).void
            case "destination" => checked(c.grant(f.system.context, p.destination, p.reader.principal, None, Set(Action.ReadMetadata, Action.Propose))).void
            case _ => IO.unit
          _ <- if Set("input", "destination")(loss) then checked(c.readRun(p.reader, run.id)).map(r => assertEquals(r.status, RunStatus.Failed)) else IO.unit
        yield ()
      }.unsafeRunSync()
    }

  for loss <- Vector("offboarding", "expiry") do
    test(s"real file stream stops after its admitted 64 KiB on $loss and cannot resume") {
      PostgresFixture.resource.use { f =>
        val s = f.system
        for
          _ <- IO.blocking {
            val image = f.root.resolve("source").resolve(sanctity.app.SyntheticFixture.firstImage)
            Files.write(image, java.util.Arrays.copyOf(Files.readAllBytes(image), 200000))
          }
          imported <- checked(s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
          accepted <- checked(s.repository.accept(s.context, imported.candidate.id, key("source")))
          reader <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
          grant <- checked(s.catalog.grant(s.context, s.dataset.id, reader.principal, Some(accepted.release.release), Set(Action.ReadMetadata, Action.ReadContent)))
          _ <- bootstrap(f)
          path = RelPath.from(sanctity.app.SyntheticFixture.firstImage).toOption.get
          file <- checked(s.repository.readFile(reader, accepted.release, path))
          received <- Ref.of[IO, Long](0)
          lose = if loss == "offboarding" then disable(f, reader.principal) else
            sql"UPDATE access_grant SET created_at=clock_timestamp()-interval '2 hours', expires_at=clock_timestamp()-interval '1 hour' WHERE id=${Records.uuid(grant.id)}".update.run.transact(f.database.transactor).void
          result <- file.bytes.chunks.evalTap(c => received.update(_ + c.size) *> lose).compile.drain.attempt
          count <- received.get
          _ = assertEquals(count, 65536L)
          _ = assert(result.left.toOption.exists { case Failure(Problem.Denied) => true; case _ => false })
          resumed <- s.repository.readFile(reader, accepted.release, path)
          _ = assertEquals(resumed.left.toOption, Some(Problem.Denied))
        yield ()
      }.unsafeRunSync()
    }

  for loss <- Vector("destination", "offboarding", "expiry") do
    test(s"output stream rechecks $loss at each chunk and leaves no collected output") {
      PostgresFixture.resource.use { f =>
        val s = f.system
        val ids = IdSource.random[IO]
        val submissions = new DerivativeSubmissions[IO](s.catalog, s.store,
          new NativeRepository[IO](s.catalog, s.store, new QcDerivativeInterpreter[IO](s.store), ids), ids)
        for
          p <- prepare(f)
          payload = Array.fill[Byte](200000)(42)
          blob = BlobRef.from(Digest.bytes(payload), payload.length.toLong).toOption.get
          output = OutputFile(RelPath.from("qc.tsv").toOption.get, blob)
          draft <- checked(submissions.begin(p.reader, p.destination, p.evidence.copy(expected = Vector(output))))
          supplied <- Ref.of[IO, Int](0)
          lose = loss match
            case "destination" => checked(s.catalog.revoke(s.context, p.destination, p.destinationGrant.id))
            case "offboarding" => disable(f, p.reader.principal)
            case _ => sql"UPDATE access_grant SET created_at=clock_timestamp()-interval '2 hours', expires_at=clock_timestamp()-interval '1 hour' WHERE id=${Records.uuid(p.destinationGrant.id)}".update.run.transact(f.database.transactor).void
          bytes = Stream.emits(payload).covary[IO].chunkN(65536).evalTap(_ => supplied.getAndUpdate(_ + 1).flatMap(n => if n == 1 then lose else IO.unit)).unchunks
          result <- submissions.collect(p.reader, draft.id, output.path, bytes)
          _ = assertEquals(result, Left(Problem.Denied))
          exists <- IO.blocking(Files.exists(f.root.resolve("storage/objects").resolve(blob.digest.value.take(2)).resolve(blob.digest.value)))
          _ = assert(!exists)
          manifest <- sql"SELECT manifest_id FROM draft WHERE id=${Records.uuid(draft.id)}".query[String].unique.transact(f.database.transactor)
          _ = assertEquals(manifest, draft.manifest.id.value.value)
        yield ()
      }.unsafeRunSync()
    }
