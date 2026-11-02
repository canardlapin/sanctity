package sanctity.app

import cats.effect.{ExitCode,IO}
import cats.syntax.all.*
import io.circe.Json
import sanctity.postgres.{BackupBundle,OrganizationRecovery,PostgresDatabase}
import sanctity.policy.PolicyWire
import java.nio.file.Path

/** Packaged operator interface; independent trust files never come from the bundle. */
object OrganizationRecoveryCli extends ProtectedIOApp:
  import PolicyWire.{check,field,text}
  private val qualifiedPredecessor = "8c5f11f521e6e53d98f200758a909959243ff7606a3da880433715a3f6ea050d"
  private def target(value: String): Path =
    val path = Path.of(value).toAbsolutePath.normalize()
    path.getParent.toRealPath().resolve(path.getFileName)
  private def sourceArtifact(bundle: Path, artifact: Path): Unit =
    val manifest = BackupBundle.verify(bundle)
    val identity = BackupCli.artifact(artifact,requireRunning=false)
    check(identity == field(manifest,"artifact"),"recovery-source-artifact")
    val count = field(manifest,"migrations").asArray.get.size
    if count == 13 then check(text(identity,"sha256") == qualifiedPredecessor,"recovery-unqualified-predecessor")
    else
      check(count == 14 && BackupCli.artifact(artifact) == identity,"recovery-running-artifact-required")

  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    args match
      case (action @ ("preview" | "verify")) :: bundleArg :: artifactArg :: storageArg :: operation :: operator :: recoveryArg :: writerArg :: Nil =>
        for
          bundle <- IO.blocking(Path.of(bundleArg).toRealPath())
          storage <- IO.blocking(target(storageArg))
          _ <- IO.blocking(sourceArtifact(bundle,Path.of(artifactArg).toRealPath()))
          config <- ServiceConfiguration.database(sys.env)
          _ <- PolicyClientConfiguration.load(Path.of(recoveryArg),Vector(bundle,storage)).use { recovery =>
            PolicyClientConfiguration.load(Path.of(writerArg),Vector(bundle,storage)).use { writer =>
              PostgresDatabase.open[IO](config).use { db =>
                val engine = new OrganizationRecovery[IO](db,config,recovery,writer)
                val actionIO = if action == "preview" then engine.preview(bundle,storage,operation,operator)
                  else engine.restore(bundle,storage,operation,operator,sys.env.getOrElse("SANCTITY_PG_RESTORE","pg_restore"),
                    sys.env.getOrElse("SANCTITY_PSQL","psql")) *> engine.verify(operation,storage)
                actionIO.flatMap(j => IO.println(j.spaces2))
              }
            }
          }
        yield ()
      case "reconcile" :: operation :: storageArg :: recoveryArg :: writerArg :: Nil =>
        execute(operation,recoveryArg,writerArg,Some(storageArg))
      case "status" :: operation :: recoveryArg :: writerArg :: Nil =>
        execute(operation,recoveryArg,writerArg,None)
      case _ => IO.raiseError(ConfigurationFailure("recovery preview|verify BUNDLE SOURCE_ARTIFACT NEW_STORAGE SESSION OPERATOR RECOVERY_CONFIG NEW_WRITER_CONFIG | reconcile SESSION STORAGE RECOVERY_CONFIG NEW_WRITER_CONFIG | status SESSION RECOVERY_CONFIG NEW_WRITER_CONFIG"))
  }
  private def execute(operation: String, recoveryArg: String, writerArg: String, storageArg: Option[String]): IO[Unit] =
    for
      config <- ServiceConfiguration.database(sys.env)
      storage <- storageArg.traverse(p => IO.blocking(Path.of(p).toRealPath()))
      _ <- PolicyClientConfiguration.load(Path.of(recoveryArg),storage.toVector).use { recovery =>
        PolicyClientConfiguration.load(Path.of(writerArg),storage.toVector).use { writer =>
          PostgresDatabase.open[IO](config).use { db =>
            val engine = new OrganizationRecovery[IO](db,config,recovery,writer)
            val action: IO[Json] = storage.fold(engine.status(operation))(p => engine.reconcile(operation,p))
            action.flatMap(j => IO.println(j.spaces2))
          }
        }
      }
    yield ()
