package sanctity.app

import cats.effect.{ExitCode, IO}
import sanctity.application.Checked
import sanctity.kernel.*
import sanctity.postgres.PostgresOrganizationCatalog
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions

/** Explicit trusted schema and reviewed organization cutover; serving never migrates. */
object MigrationCli extends ProtectedIOApp:
  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    ServiceConfiguration.database(sys.env).flatMap { config =>
      PolicyClientConfiguration.database(config,sys.env).use { db => args match
        case Nil => db.migrate *> IO.println("Catalog migrations verified and applied.")
        case "--runtime-role" :: role :: Nil =>
          db.migrate *> db.grantRuntime(role) *> IO.println("Catalog migrations and runtime profile applied.")
        case "--organization-preview" :: person :: output :: Nil =>
          for
            _ <- db.requireOperator *> db.checkCompatibility()
            operator <- Checked.lift[IO, PrincipalId](Id.parse[PrincipalTag](person))
            report <- db.organization.preview(operator)
            _ <- IO.blocking {
              val file = Path.of(output)
              Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
              Files.writeString(file, report.spaces2 + "\n", StandardOpenOption.WRITE)
            }
            _ <- IO.println("Organization preview digest: " + PostgresOrganizationCatalog.previewDigest(report).value)
          yield ()
        case "--organization-bootstrap" :: person :: digest :: Nil =>
          for
            _ <- db.requireOperator *> db.checkCompatibility()
            operator <- Checked.lift[IO, PrincipalId](Id.parse[PrincipalTag](person))
            expected <- Checked.lift[IO, Digest](Digest.from(digest))
            _ <- db.organization.bootstrap(operator, expected)
            _ <- IO.println("Organization bootstrap applied; policy durability is local-engineering only.")
          yield ()
        case _ => IO.raiseError(ConfigurationFailure(
          "usage: migrate [--runtime-role ROLE | --organization-preview OPERATOR NEW_FILE | --organization-bootstrap OPERATOR DIGEST]"))
      }
    }
  }
