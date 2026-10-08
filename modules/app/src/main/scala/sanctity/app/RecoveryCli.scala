package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import sanctity.application.Checked
import sanctity.kernel.*
import sanctity.objects.FileBlobStore
import sanctity.postgres.{DatabaseConfig, PostgresDatabase, PolicySnapshot}
import java.nio.file.{Files, Path, StandardOpenOption}

/** Operator-only CLI. The policy file must come from a trusted current authority source. */
object RecoveryCli extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env)).flatMap { config =>
      PostgresDatabase.open[IO](config).use { db =>
        db.migrate *> (args match
          case "capture-policy" :: output :: Nil =>
            for
              snapshot <- db.recovery.capturePolicy
              _ <- IO.blocking(Files.writeString(Path.of(output), db.recovery.encodePolicy(snapshot), StandardOpenOption.CREATE_NEW))
              _ <- IO.blocking(Files.writeString(Path.of(output + ".sha256"), snapshot.digest.value, StandardOpenOption.CREATE_NEW))
              _ <- IO.println(s"Captured policy digest: ${snapshot.digest.value}")
            yield ()
          case "reconcile" :: input :: expected :: storage :: Nil =>
            for
              text <- IO.blocking(Files.readString(Path.of(input)))
              snapshot <- Checked.lift[IO, PolicySnapshot](db.recovery.decodePolicy(text))
              digest <- Checked.lift[IO, Digest](Digest.from(expected))
              store <- FileBlobStore.open[IO](Path.of(storage))
              _ <- db.recovery.reconcile(snapshot, digest, store).flatMap(Checked.lift[IO, Unit])
              _ <- IO.println("Recovery reconciled; blob verification passed and current authority applied.")
            yield ()
          case _ => IO.raiseError(new IllegalArgumentException(
            "usage: RecoveryCli capture-policy NEW_FILE | reconcile POLICY TRUSTED_DIGEST STORAGE")))
      }.as(ExitCode.Success)
    }
