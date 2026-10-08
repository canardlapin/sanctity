package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{Checked, IdSource}
import sanctity.kernel.*
import sanctity.postgres.{DatabaseConfig, PostgresDatabase}
import java.nio.file.{Files, Path}

object RecoveryDemo extends IOApp:
  private def field[A](json: Json, name: String): IO[Id[A]] =
    IO.fromEither(json.hcursor.get[String](name)).flatMap(v => Checked.lift[IO, Id[A]](Id.parse[A](v)))
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])

  def run(args: List[String]): IO[ExitCode] =
    args match
      case command :: directory :: Nil =>
        val root = Path.of(directory)
        for
          config <- Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env))
          text <- IO.blocking(Files.readString(root.resolve("checkpoint.json")))
          checkpoint <- IO.fromEither(new JawnParser().parse(text))
          principal <- field[PrincipalTag](checkpoint, "principal")
          dataset <- field[DatasetTag](checkpoint, "dataset")
          _ <- PostgresDatabase.open[IO](config).use { db =>
            val ids = IdSource.random[IO]
            val catalog = db.catalog(ids)
            val ctx = AccessContext(principal)
            db.migrate *> (command match
              case "grant" =>
                for
                  guest <- ids.next[PrincipalTag]
                  grant <- checked(catalog.grant(ctx, dataset, guest, None, Set(Action.ReadMetadata, Action.ReadContent)))
                  json = Json.obj("guest" -> Json.fromString(guest.render), "grant" -> Json.fromString(grant.id.render))
                  _ <- IO.blocking(Files.writeString(root.resolve("guest.json"), json.noSpaces))
                yield ()
              case "revoke" | "verify" =>
                for
                  text <- IO.blocking(Files.readString(root.resolve("guest.json")))
                  json <- IO.fromEither(new JawnParser().parse(text))
                  guest <- field[PrincipalTag](json, "guest")
                  grant <- field[GrantTag](json, "grant")
                  _ <- if command == "revoke" then checked(catalog.revoke(ctx, dataset, grant)) else IO.unit
                  denied <- catalog.dataset(AccessContext(guest), dataset)
                  _ <- IO.raiseUnless(denied == Left(Problem.Denied))(new IllegalStateException("revoked grant revived"))
                  _ <- checked(catalog.dataset(ctx, dataset))
                  _ <- IO.println("Current curator can read; revoked guest remains denied.")
                yield ()
              case "closed" =>
                catalog.dataset(ctx, dataset).flatMap(result =>
                  IO.raiseUnless(result.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))(new IllegalStateException("restore gate was open")))
              case _ => IO.raiseError(new IllegalArgumentException("unknown recovery demonstration command")))
          }
        yield ExitCode.Success
      case _ => IO.raiseError(new IllegalArgumentException("usage: RecoveryDemo grant|revoke|closed|verify WORKSPACE"))
