package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import sanctity.postgres.DatabaseConfig
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

/** Error categories are constants: configuration values and exception causes never reach diagnostics. */
private[app] final case class ConfigurationFailure(category: String)
    extends RuntimeException(s"Invalid service configuration: $category") with NoStackTrace

/** Detached-fiber failures bypass run's handler; they need the same diagnostic boundary. */
trait ProtectedIOApp extends IOApp:
  override protected def reportFailure(error: Throwable): IO[Unit] =
    IO.consoleForIO.errorln("Sanctity background operation failed; internal details withheld.")

final class ServiceConfiguration private[app] (
    val database: DatabaseConfig, val storage: Path, val port: Int,
    val publicOrigin: String, val authority: String, val shutdownTimeout: FiniteDuration
):
  override def toString: String = "ServiceConfiguration(<protected>)"

object ServiceConfiguration:
  private val noFollow = LinkOption.NOFOLLOW_LINKS
  private val directoryMode = PosixFilePermissions.fromString("rwx------")
  private val fileMode = PosixFilePermissions.fromString("rw-------")
  private def requireSetting(valid: Boolean, category: String): Unit =
    if !valid then throw ConfigurationFailure(category)

  private def protectedPath(value: String, category: String): Path =
    val p = Path.of(value)
    requireSetting(p.isAbsolute && p.normalize() == p && p.toRealPath() == p, category)
    requireSetting(Files.getOwner(p, noFollow) == p.getFileSystem.getUserPrincipalLookupService
      .lookupPrincipalByName(System.getProperty("user.name")), category)
    p

  private def password(values: Map[String, String]): IO[String] = IO.blocking {
    (values.get("SANCTITY_DB_PASSWORD"), values.get("SANCTITY_DB_PASSWORD_FILE")) match
      case (Some(secret), None) => secret
      case (None, Some(name)) =>
        val p = protectedPath(name, "database secret file")
        requireSetting(Files.isRegularFile(p, noFollow) && Files.size(p) <= 4096 &&
          Files.getPosixFilePermissions(p, noFollow) == fileMode &&
          Files.getPosixFilePermissions(p.getParent, noFollow) == directoryMode, "database secret file")
        // File remains a protected operator input; no shell sourcing or interpolation.
        val channel = FileChannel.open(p, StandardOpenOption.READ, noFollow)
        val bytes = java.nio.ByteBuffer.allocate(4097)
        try
          while bytes.hasRemaining && channel.read(bytes) >= 0 do ()
          requireSetting(bytes.position() <= 4096, "database secret file")
          new String(bytes.array(), 0, bytes.position(), java.nio.charset.StandardCharsets.UTF_8)
            .stripSuffix("\n").stripSuffix("\r")
        finally channel.close()
      case _ => throw ConfigurationFailure("select exactly one database password source")
  }.handleErrorWith(_ => IO.raiseError(ConfigurationFailure("database password source")))

  /** Also used by trusted operator commands, so passwords need not enter their environments. */
  def database(values: Map[String, String]): IO[DatabaseConfig] =
    (for
      secret <- password(values)
      config <- IO.blocking {
        requireSetting(secret.nonEmpty && secret.length <= 4096 && !secret.exists(c => c == '\n' || c == '\r' || c == 0), "database password")
        val raw = values.getOrElse("SANCTITY_JDBC_URL", "")
        requireSetting(raw.startsWith("jdbc:postgresql://"), "database URL")
        val uri = URI.create(raw.stripPrefix("jdbc:"))
        requireSetting(uri.getHost != null && uri.getUserInfo == null && uri.getFragment == null &&
          (uri.getPort == -1 || uri.getPort > 0 && uri.getPort <= 65535) &&
          Option(uri.getRawPath).exists(_.matches("/[A-Za-z0-9_.-]+")) &&
          Option(uri.getRawQuery).forall(_.matches("sslmode=(disable|require|verify-ca|verify-full)")), "database URL")
        val user = values.getOrElse("SANCTITY_DB_USER", "")
        val schema = values.getOrElse("SANCTITY_DB_SCHEMA", "sanctity")
        val pool = values.getOrElse("SANCTITY_DB_POOL_SIZE", "4").toIntOption.getOrElse(0)
        DatabaseConfig.from(raw, user, secret, schema, pool)
          .fold(_ => throw ConfigurationFailure("database settings"), identity)
      }
    yield config).handleErrorWith(_ => IO.raiseError(ConfigurationFailure("database settings")))

  private def prepareStorage(values: Map[String, String]): IO[Path] = IO.blocking {
    val root = protectedPath(values.getOrElse("SANCTITY_STORAGE", ""), "storage root")
    val working = Path.of("").toRealPath()
    val home = Path.of(System.getProperty("user.home")).toRealPath()
    requireSetting(!working.startsWith(root) && !home.startsWith(root), "storage isolation")
    requireSetting(Files.isDirectory(root, noFollow) && Files.getPosixFilePermissions(root, noFollow) == directoryMode,
      "storage permissions")
    values.get("SANCTITY_DB_PASSWORD_FILE").foreach { name =>
      requireSetting(!Path.of(name).startsWith(root), "storage/secret isolation")
    }
    for name <- Vector("objects", "staging") do
      val p = root.resolve(name)
      if !Files.exists(p, noFollow) then Files.createDirectory(p, PosixFilePermissions.asFileAttribute(directoryMode))
      val checked = protectedPath(p.toString, "storage child")
      requireSetting(Files.isDirectory(checked, noFollow) && Files.getPosixFilePermissions(checked, noFollow) == directoryMode,
        "storage child permissions")
    val probe = Files.createTempFile(root.resolve("staging"), "startup-", ".probe",
      PosixFilePermissions.asFileAttribute(fileMode))
    try
      val channel = FileChannel.open(probe, StandardOpenOption.WRITE, noFollow)
      try
        channel.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
        channel.force(true)
      finally channel.close()
    finally Files.deleteIfExists(probe)
    root
  }.handleErrorWith(_ => IO.raiseError(ConfigurationFailure("storage root, ownership or isolation")))

  def load(values: Map[String, String]): IO[ServiceConfiguration] =
    for
      db <- database(values)
      network <- IO.blocking {
        val port = values.getOrElse("SANCTITY_HTTP_PORT", "8080").toIntOption.getOrElse(0)
        requireSetting(port > 0 && port <= 65535, "HTTP port")
        val seconds = values.getOrElse("SANCTITY_SHUTDOWN_SECONDS", "10").toIntOption.getOrElse(0)
        requireSetting(seconds >= 1 && seconds <= 120, "shutdown timeout")
        val local = s"http://127.0.0.1:$port"
        val mode = values.getOrElse("SANCTITY_DEPLOYMENT_MODE", "loopback")
        val origin = values.getOrElse("SANCTITY_PUBLIC_ORIGIN", local)
        val uri = URI.create(origin)
        requireSetting(uri.getHost != null && uri.getUserInfo == null && uri.getRawQuery == null &&
          uri.getFragment == null && uri.getRawPath == "" &&
          (uri.getPort == -1 || uri.getPort > 0 && uri.getPort <= 65535), "public origin")
        requireSetting(mode match
          case "loopback" => origin == local
          case "proxy" => uri.getScheme == "https" && origin == origin.toLowerCase(java.util.Locale.ROOT)
          case _ => false,
          "deployment mode/public origin")
        (port, origin, uri.getRawAuthority, seconds.seconds)
      }.handleErrorWith(_ => IO.raiseError(ConfigurationFailure("HTTP port, origin or shutdown settings")))
      root <- prepareStorage(values)
    yield new ServiceConfiguration(db, root, network._1, network._2, network._3, network._4)

  /** Deliberately discard nested JDBC, filesystem, proxy and driver exception messages. */
  def exit(action: IO[Unit]): IO[ExitCode] = action.as(ExitCode.Success).handleErrorWith {
    case sanctity.application.Failure(p: sanctity.kernel.Problem.PolicyPending) =>
      IO.consoleForIO.errorln(p.message).as(ExitCode.Error)
    case sanctity.application.Failure(p: sanctity.kernel.Problem.PolicyOutcomeUnknown) =>
      IO.consoleForIO.errorln(p.message).as(ExitCode.Error)
    case e: ConfigurationFailure => IO.consoleForIO.errorln(e.getMessage).as(ExitCode.Error)
    case _ => IO.consoleForIO.errorln("Sanctity command failed; check protected configuration and service dependencies.")
      .as(ExitCode.Error)
  }
