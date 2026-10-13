package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, FileBlobStore, NativeDirectorySource}
import sanctity.postgres.{DatabaseConfig, PostgresDatabase}
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import java.nio.file.{Files, Path}

/** Process-boundary publication and restore proof; run through check-publication-recovery.sh. */
object PublicationRecoveryDemo extends IOApp:
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def string(j: Json, field: String): IO[String] = IO.fromEither(j.hcursor.get[String](field))
  private def id[A](j: Json, field: String): IO[Id[A]] = string(j, field).flatMap(s => Checked.lift[IO, Id[A]](Id.parse[A](s)))
  private def digest(j: Json, field: String): IO[Digest] = string(j, field).flatMap(s => Checked.lift[IO, Digest](Digest.from(s)))
  private def save(root: Path, j: Json): IO[Unit] = IO.blocking(Files.writeString(root.resolve("publication-checkpoint.json"), j.spaces2 + "\n")).void
  private def load(root: Path): IO[Json] = IO.blocking(Files.readString(root.resolve("publication-checkpoint.json"))).flatMap(s => IO.fromEither(new JawnParser().parse(s)))
  private def intent(j: Json): IO[PublicationIntent] = for {
    run <- id[RunTag](j, "run")
    receipt <- digest(j, "receipt")
    owner <- id[PrincipalTag](j, "owner")
    destination <- id[DatasetTag](j, "destination")
    expected <- digest(j, "publication")
    value = PublicationIntent(run, receipt, owner, destination, None, key("recover-publication"))
    _ <- IO.raiseUnless(value.id == expected)(new IllegalStateException("checkpoint publication mismatch"))
  } yield value

  def run(args: List[String]): IO[ExitCode] = for {
    config <- Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env))
    _ <- PostgresDatabase.open[IO](config).use { db =>
      db.migrate *> (args match
        case mode :: directory :: Nil =>
          val root = Path.of(directory).toAbsolutePath
          if mode == "stage" then stage(db, root)
          else continue(db, root, mode)
        case _ => IO.raiseError(new IllegalArgumentException("usage: PublicationRecoveryDemo stage|resume|revoke|closed|revoked <workspace>")))
    }
  } yield ExitCode.Success

  private def stage(db: PostgresDatabase[IO], root: Path): IO[Unit] =
    val ids = IdSource.random[IO]
    val catalog = db.catalog(ids)
    for
      _ <- IO.blocking(Files.createDirectory(root))
      _ <- SyntheticFixture.create(root.resolve("source"))
      store <- FileBlobStore.open[IO](root.resolve("storage"))
      repository = new NativeRepository[IO](catalog, store, new BidsInterpreter[IO](store), ids)
      curator <- ids.next[PrincipalTag].map(AccessContext(_))
      owner <- ids.next[PrincipalTag].map(AccessContext(_))
      custodian <- ids.next[CustodianTag]
      source <- repository.create(curator, custodian)
      imported <- checked(repository.importNative(curator, source.id, new NativeDirectorySource[IO](root.resolve("source"))))
      original <- checked(repository.accept(curator, imported.candidate.id, key("source")))
      grant <- checked(catalog.grant(curator, source.id, owner.principal, Some(original.release.release), Set(Action.ReadMetadata, Action.ReadContent)))
      selected <- checked(catalog.select(owner, Vector(original.release), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
        ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("sub", "01"))))))
      bundleDataset <- repository.create(owner, custodian)
      inputs = new ProcessingInputs[IO](catalog, repository, new ScientificViews[IO](catalog, store, repository, new MriViewCompiler, ids), new MriInputPlanner)
      proposal <- checked(inputs.preparePrivate(owner, original.release, selected.id, bundleDataset.id,
        DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime")), MriInputPlanner.Contract))
      bundle <- checked(repository.accept(owner, proposal.candidate.id, key("bundle")))
      originalDestination <- repository.create(owner, custodian)
      destination <- repository.create(owner, custodian)
      producer = new MriQcProducer[IO](catalog, store, repository, ids, Path.of("/usr/bin/python3"))
      receipt <- checked(new LocalQcRuns[IO](catalog, store, producer).execute(owner,
        RunSpec(bundle.release, proposal.candidate.activity.bundle.get.id, originalDestination.id, None, MriInputPlanner.Contract),
        key("execution"), root.resolve("execution")))
      publication = PublicationIntent(receipt.invocation, receipt.id, owner.principal, destination.id, None, key("recover-publication"))
      derivatives = new NativeRepository[IO](catalog, store, new QcDerivativeInterpreter[IO](store), ids)
      submissions = new DerivativeSubmissions[IO](catalog, store, derivatives, ids)
      draft <- checked(submissions.publish(owner, publication))
      _ <- checked(submissions.collectStaged(owner, draft.id, receipt.expected.head.path))
      _ <- IO.blocking {
        val walk = Files.walk(root.resolve("execution"))
        try walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => { Files.delete(p); () })
        finally walk.close()
      }
      checkpoint = Json.obj("owner" -> Json.fromString(owner.principal.render), "curator" -> Json.fromString(curator.principal.render),
        "source" -> Json.fromString(source.id.render), "grant" -> Json.fromString(grant.id.render),
        "run" -> Json.fromString(receipt.invocation.render), "receipt" -> Json.fromString(receipt.id.value),
        "destination" -> Json.fromString(destination.id.render), "publication" -> Json.fromString(publication.id.value),
        "draft" -> Json.fromString(draft.id.render))
      _ <- save(root, checkpoint)
      _ <- IO.println(s"Staged partial publication ${publication.id.value}; execution workspace removed. $root")
    yield ()

  private def continue(db: PostgresDatabase[IO], root: Path, mode: String): IO[Unit] =
    val ids = IdSource.random[IO]
    val catalog = db.catalog(ids)
    for
      checkpoint <- load(root)
      publication <- intent(checkpoint)
      ctx = AccessContext(publication.publisher)
      store <- FileBlobStore.open[IO](root.resolve("storage"))
      repository = new NativeRepository[IO](catalog, store, new QcDerivativeInterpreter[IO](store), ids)
      submissions = new DerivativeSubmissions[IO](catalog, store, repository, ids)
      _ <- mode match
        case "revoke" => for {
          curator <- id[PrincipalTag](checkpoint, "curator")
          source <- id[DatasetTag](checkpoint, "source")
          grant <- id[GrantTag](checkpoint, "grant")
          _ <- checked(catalog.revoke(AccessContext(curator), source, grant))
          _ <- IO.println("Revoked original source after backup")
        } yield ()
        case "closed" | "revoked" => for {
          run <- catalog.completedRun(ctx, publication.run)
          retry <- submissions.publish(ctx, publication)
          candidate <- id[CandidateTag](checkpoint, "candidate")
          acceptance <- repository.accept(ctx, candidate, key("accepted-publication"))
          _ <- IO.raiseUnless(run.isLeft && retry.isLeft && acceptance.isLeft)(new IllegalStateException(s"$mode authority did not deny all publication paths"))
          _ <- IO.println(s"$mode gate denied completed-run access, publication and acceptance retry")
        } yield ()
        case "resume" => for {
          run <- checked(catalog.completedRun(ctx, publication.run))
          result <- Checked.lift[IO, ProcessingEvidence](publication.validate(ctx, run))
          draft <- checked(submissions.publish(ctx, publication))
          expectedDraft <- id[DraftTag](checkpoint, "draft")
          _ <- IO.raiseUnless(draft.id == expectedDraft)(new IllegalStateException("publication retry changed draft"))
          candidate <- checkpoint.hcursor.get[String]("candidate").toOption match
            case Some(_) => id[CandidateTag](checkpoint, "candidate")
            case None => for {
              _ <- result.expected.traverse_(o => checked(submissions.collectStaged(ctx, draft.id, o.path)))
              prepared <- checked(submissions.prepare(ctx, draft.id))
              _ <- save(root, checkpoint.mapObject(_.add("candidate", Json.fromString(prepared.candidate.id.render))))
            } yield prepared.candidate.id
          accepted <- checked(repository.accept(ctx, candidate, key("accepted-publication")))
          repeated <- checked(repository.accept(ctx, candidate, key("accepted-publication")))
          _ <- IO.raiseUnless(accepted == repeated)(new IllegalStateException("acceptance retry changed receipt"))
          release <- checked(catalog.release(ctx, accepted.release))
          _ <- IO.raiseUnless(release.activity.publication.contains(publication) && result.matches(release.manifest))(
            new IllegalStateException("publication evidence or bytes changed"))
          _ <- result.expected.traverse_(o => store.verify(o.blob))
          exported <- IO.blocking(Files.createTempDirectory(root, "publication-export-"))
          _ <- checked(repository.exportFull(ctx, accepted.release, new DirectoryExport[IO](exported.resolve("files"))))
          current <- load(root)
          _ <- save(root, current.mapObject(_.add("release", Json.fromString(accepted.release.release.render))))
          _ <- IO.println(s"Recovered publication ${publication.id.value}; accepted ${accepted.release.release.render}; verified ${result.expected.size} retained outputs")
        } yield ()
        case _ => IO.raiseError(new IllegalArgumentException("unknown publication recovery mode"))
    yield ()
