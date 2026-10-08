package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.BidsInterpreter
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, FileBlobStore, NativeDirectorySource}
import sanctity.postgres.{DatabaseConfig, PostgresDatabase}

import java.nio.file.{Files, Path}

object PostgresDemo extends IOApp:
  private val correctionKey = IdempotencyKey.from("durable-correction-v1").toOption.get
  private def path(value: String): RelPath = RelPath.from(value).toOption.get
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])

  def run(args: List[String]): IO[ExitCode] =
    for
      config <- Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env))
      _ <- PostgresDatabase.open[IO](config).use { database =>
        database.migrate *> (args match
          case "stage" :: directory :: Nil => stage(database, Path.of(directory).toAbsolutePath)
          case "resume" :: directory :: Nil => resume(database, Path.of(directory).toAbsolutePath)
          case _ => IO.raiseError(new IllegalArgumentException("usage: PostgresDemo stage|resume <workspace>")))
      }
    yield ExitCode.Success

  private def repository(database: PostgresDatabase[IO], root: Path): IO[NativeRepository[IO]] =
    val ids = IdSource.random[IO]
    FileBlobStore.open[IO](root.resolve("storage")).map { store =>
      new NativeRepository[IO](database.catalog(ids), store, new BidsInterpreter[IO](store), ids)
    }

  private def stage(database: PostgresDatabase[IO], root: Path): IO[Unit] =
    val ids = IdSource.random[IO]
    for
      _ <- IO.blocking(Files.createDirectory(root))
      _ <- SyntheticFixture.create(root.resolve("source"))
      repo <- repository(database, root)
      principal <- ids.next[PrincipalTag]
      custodian <- ids.next[CustodianTag]
      context = AccessContext(principal)
      dataset <- repo.create(context, custodian)
      preview <- checked(repo.importNative(context, dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      initial <- checked(repo.accept(context, preview.candidate.id, IdempotencyKey.from("durable-import-v1").toOption.get))
      correction <- checked(repo.correctMetadata(context, initial.release, path(SyntheticFixture.sharedSidecar),
        SyntheticFixture.correctedJson, "Demonstrate acceptance after a process and database restart"))
      checkpoint = Json.obj(
        "version" -> Json.fromInt(1), "principal" -> Json.fromString(principal.render),
        "dataset" -> Json.fromString(dataset.id.render), "initial" -> Json.fromString(initial.release.release.render),
        "candidate" -> Json.fromString(correction.candidate.id.render)
      )
      _ <- IO.blocking(Files.writeString(root.resolve("checkpoint.json"), checkpoint.spaces2 + "\n"))
      _ <- IO.println(s"Staged durable correction ${correction.candidate.id.render} in schema ${database.schema}.\nWorkspace: $root\nThe process can now exit; resume uses persisted preparation.")
    yield ()

  private def resume(database: PostgresDatabase[IO], root: Path): IO[Unit] =
    for
      text <- IO.blocking(Files.readString(root.resolve("checkpoint.json")))
      json <- IO.fromEither(new JawnParser().parse(text))
      version <- IO.fromEither(json.hcursor.get[Int]("version"))
      _ <- IO.raiseUnless(version == 1)(new IllegalArgumentException("unsupported demo checkpoint"))
      principal <- field[PrincipalTag](json, "principal")
      dataset <- field[DatasetTag](json, "dataset")
      initial <- field[ReleaseTag](json, "initial")
      candidate <- field[CandidateTag](json, "candidate")
      ctx = AccessContext(principal)
      repo <- repository(database, root)
      accepted <- checked(repo.accept(ctx, candidate, correctionKey))
      retried <- checked(repo.accept(ctx, candidate, correctionKey))
      _ <- IO.raiseUnless(accepted == retried)(new IllegalStateException("receipt changed on retry"))
      before <- checked(repo.explain(ctx, ReleaseRef(dataset, initial), path(SyntheticFixture.firstImage)))
      after <- checked(repo.explain(ctx, accepted.release, path(SyntheticFixture.firstImage)))
      _ <- IO.raiseUnless(before.fields("RepetitionTime").value == MetadataValue.Number(BigDecimal("1.5")) &&
        after.fields("RepetitionTime").value == MetadataValue.Number(BigDecimal("1.8")))(new IllegalStateException("historical metadata changed"))
      exports <- IO.blocking(Files.createTempDirectory(root, "exports-"))
      _ <- checked(repo.exportFull(ctx, ReleaseRef(dataset, initial), new DirectoryExport[IO](exports.resolve("release-1"))))
      _ <- checked(repo.exportFull(ctx, accepted.release, new DirectoryExport[IO](exports.resolve("release-2"))))
      report = s"""Durable PostgreSQL lifecycle demonstration
Schema: ${database.schema}
Initial release: ${initial.render}
Accepted release: ${accepted.release.release.render}
Candidate: ${candidate.render}
Accepted at: ${accepted.at}
RepetitionTime: 1.5 -> 1.8; historical interpretation retained.
Both releases exported with verified bytes; retry returned its original receipt.
Exports: $exports
Authority: explicit demonstration principal, not institutional authentication.
"""
      _ <- IO.blocking(Files.writeString(exports.resolve("receipt.txt"), report))
      _ <- IO.println(report)
    yield ()

  private def field[A](json: Json, name: String): IO[Id[A]] =
    IO.fromEither(json.hcursor.get[String](name)).flatMap(value => Checked.lift[IO, Id[A]](Id.parse[A](value)))
