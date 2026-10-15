package sanctity.app

import cats.effect.{ExitCode, IO}
import java.nio.file.Path

/** Operator maintenance only. No barrier release or restored-catalog reopen path. */
object PolicyCatalogCli extends ProtectedIOApp:
  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    ServiceConfiguration.database(sys.env).flatMap { config =>
      PolicyClientConfiguration.database(config,sys.env).use { db =>
        db.requireOperator *> (args match
          case "enroll" :: writerPath :: recoveryPath :: Nil =>
            PolicyClientConfiguration.load(Path.of(writerPath)).use { writer =>
              PolicyClientConfiguration.load(Path.of(recoveryPath)).use { recovery =>
                IO.raiseWhen(writer.writer == recovery.writer)(ConfigurationFailure("separate recovery identity required")) *>
                  db.policy.enroll(writer,recovery.client).flatMap(j => IO.println(j.spaces2))
              }
            }
          case "readiness" :: Nil => db.checkCompatibility() *> db.policy.readiness.flatMap(j => IO.println(j.spaces2))
          case "resolve" :: Nil => db.checkCompatibility() *> db.policy.resolve.flatMap(j => IO.println(j.spaces2))
          case _ => IO.raiseError(new IllegalArgumentException("policy enroll WRITER_CONFIG RECOVERY_CONFIG | readiness | resolve")))
      }
    }
  }
