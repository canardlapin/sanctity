package sanctity.app

import cats.effect.{ExitCode, IO}
import io.circe.Json
import sanctity.postgres.{BackupBundle, PostgresDatabase}
import sanctity.policy.PolicyWire
import java.io.File
import java.nio.file.{Files, Path}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Captures only from an operator connection; writer trust remains outside backups. */
object BackupCli extends ProtectedIOApp:
  import PolicyWire.{check,field,text,obj,str}
  private def artifact(path: Path): Json =
    check(Files.size(path) <= 512L*1024*1024,"backup-artifact-bound")
    val zip = new ZipFile(path.toFile)
    try
      val manifests = zip.entries().asScala.filter(_.getName.matches("[^/]+/manifest.json")).toVector
      check(manifests.size == 1 && manifests.head.getSize <= 2L*1024*1024,"backup-artifact-manifest")
      val in = zip.getInputStream(manifests.head)
      val bytes = try in.readNBytes(2*1024*1024+1) finally in.close()
      check(bytes.length <= 2*1024*1024,"backup-artifact-manifest")
      val manifest = io.circe.jawn.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8))
        .fold(_ => throw ConfigurationFailure("backup artifact manifest"),identity)
      val dependencies = field(manifest,"dependencies").asArray.get
      check(dependencies.size <= 512,"backup-artifact-dependencies")
      val prefix = manifests.head.getName.stripSuffix("manifest.json")
      var expanded = 0L
      def entryDigest(name: String): String =
        val entry = Option(zip.getEntry(prefix+name)).getOrElse(throw ConfigurationFailure("backup artifact entry"))
        check(entry.getSize >= 0 && entry.getSize <= 512L*1024*1024,"backup-artifact-entry-bound")
        expanded = Math.addExact(expanded,entry.getSize)
        check(expanded <= 512L*1024*1024,"backup-artifact-expanded-bound")
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        val stream = zip.getInputStream(entry)
        try
          val buffer = new Array[Byte](65536)
          var n = stream.read(buffer)
          var total = 0L
          while n >= 0 do
            total += n
            check(total <= entry.getSize,"backup-artifact-entry-bound")
            hash.update(buffer,0,n); n = stream.read(buffer)
          check(total == entry.getSize,"backup-artifact-entry-size")
          hash.digest().map(b => f"${b & 255}%02x").mkString
        finally stream.close()
      check(entryDigest("metadata/source.json") == text(manifest,"sourceInventorySha256"),"backup-artifact-source")
      dependencies.foreach { j =>
        val name = text(j,"path")
        check(name.matches("lib/[A-Za-z0-9._+-]+\\.jar") && entryDigest(name) == text(j,"sha256"),"backup-artifact-entry")
      }
      val expected = dependencies.map(j =>
        Path.of(text(j,"path")).getFileName.toString -> text(j,"sha256")).toMap
      check(expected.size == dependencies.size,"backup-artifact-duplicate")
      val active = System.getProperty("java.class.path").split(File.pathSeparator).toVector.map(Path.of(_))
      check(active.forall(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".jar")) &&
        active.map(_.getFileName.toString).distinct.size == active.size &&
        active.map(p => p.getFileName.toString -> BackupBundle.hashFile(p)).toMap == expected,"backup-running-artifact")
      obj("sha256" -> str(BackupBundle.hashFile(path)),"manifestSha256" -> str(PolicyWire.digest(bytes)),
        "sourceInventorySha256" -> field(manifest,"sourceInventorySha256"),"version" -> field(manifest,"version"))
    finally zip.close()

  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    args match
      case "verify" :: bundle :: Nil => IO.blocking {
        val value = BackupBundle.verify(Path.of(bundle).toRealPath())
        obj("status" -> str("verified"),"bundle" -> str(BackupBundle.manifestDigest(value)),
          "installation" -> field(value,"installation"),"policyHead" -> field(value,"policyHead"))
      }.flatMap(j => IO.println(j.spaces2))
      case "capture" :: storageArg :: outputArg :: artifactArg :: Nil =>
        for
          config <- ServiceConfiguration.database(sys.env)
          storage <- IO.blocking(Path.of(storageArg).toRealPath())
          target <- IO.blocking {
            val p = Path.of(outputArg).toAbsolutePath.normalize()
            p.getParent.toRealPath().resolve(p.getFileName)
          }
          identity <- IO.blocking(artifact(Path.of(artifactArg).toRealPath()))
          policy <- IO.fromOption(sys.env.get("SANCTITY_POLICY_CONFIG"))(ConfigurationFailure("backup requires independent writer trust"))
          seconds <- IO.fromOption(sys.env.get("SANCTITY_BACKUP_DEADLINE_SECONDS").fold(Option(300L))(_.toLongOption))(
            ConfigurationFailure("backup deadline"))
          result <- PolicyClientConfiguration.load(Path.of(policy),Vector(storage,target,target.getParent)).use { trust =>
            PostgresDatabase.open[IO](config).use { db =>
              BackupBundle.capture(db,config,trust,storage,target,identity,
                sys.env.getOrElse("SANCTITY_PG_DUMP","pg_dump"),seconds)
            }
          }
          _ <- IO.println(result.spaces2)
        yield ()
      case _ => IO.raiseError(ConfigurationFailure("usage: backup capture STORAGE NEW_BUNDLE RUNNING_ARTIFACT_ZIP | verify BUNDLE"))
  }
