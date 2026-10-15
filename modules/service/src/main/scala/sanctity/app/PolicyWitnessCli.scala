package sanctity.app

import cats.effect.{ExitCode, IO, Resource}
import sanctity.policy.*
import java.net.InetSocketAddress
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore

/** Explicit standalone witness process. It has no catalog or object-store connection.
  * Local-only listener; independent host/storage deployment remains separately qualified.
  */
object PolicyWitnessCli extends ProtectedIOApp:
  import PolicyWire.*
  private def protectedFile(path: Path): Array[Byte] =
    val noFollow = LinkOption.NOFOLLOW_LINKS
    val owner = path.getFileSystem.getUserPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path &&
      Files.isRegularFile(path,noFollow) && Files.getOwner(path,noFollow) == owner &&
      Files.getPosixFilePermissions(path,noFollow) == PosixFilePermissions.fromString("rw-------") &&
      Files.getPosixFilePermissions(path.getParent,noFollow) == PosixFilePermissions.fromString("rwx------") &&
      Files.size(path) <= maximumBytes, "protected-input")
    val stream = Files.newInputStream(path,noFollow)
    try stream.readNBytes(maximumBytes+1) finally stream.close()

  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    args match
      case (action @ ("init" | "serve" | "inspect")) :: configPath :: Nil =>
        IO.blocking {
          val config = parse(protectedFile(Path.of(configPath)))
          fields(config,Set("installation","store","identityStore","identityPasswordFile","trustStore","trustPasswordFile",
            "signingPrivate","signingPublic","writerFingerprint","recoveryFingerprint","port"))
          val root = Path.of(text(config,"store"))
          val inputKeys = Vector("identityStore","identityPasswordFile","trustStore","trustPasswordFile","signingPrivate","signingPublic")
          check(!Path.of(configPath).startsWith(root) && inputKeys.forall(k => !Path.of(text(config,k)).startsWith(root)), "trust-store-isolation")
          val privateKey = PolicyWire.privateKey(protectedFile(Path.of(text(config,"signingPrivate"))))
          val publicKey = PolicyWire.publicKey(protectedFile(Path.of(text(config,"signingPublic"))))
          val installation = id(text(config,"installation"))
          val writer = hash(text(config,"writerFingerprint"))
          val recovery = hash(text(config,"recoveryFingerprint"))
          check(writer != recovery, "separate-recovery-identity")
          val port = natural(text(config,"port"))
          check(port >= 1 && port <= 65535, "port")
          def password(name: String): Array[Char] =
            val value = utf8(protectedFile(Path.of(text(config,name)))).stripSuffix("\n")
            check(value.nonEmpty && value.length <= 4096 && !value.exists(_.isControl), "password")
            value.toCharArray
          def keyStore(name: String, secret: Array[Char]): KeyStore =
            val store = KeyStore.getInstance("PKCS12")
            val bytes = protectedFile(Path.of(text(config,name)))
            store.load(new java.io.ByteArrayInputStream(bytes),secret)
            store
          val secret = password("identityPasswordFile")
          val anchorsSecret = password("trustPasswordFile")
          val tls = try PolicyTls.context(keyStore("identityStore",secret),secret,keyStore("trustStore",anchorsSecret))
            finally
              java.util.Arrays.fill(secret,'\u0000')
              java.util.Arrays.fill(anchorsSecret,'\u0000')
          (root,installation,privateKey,publicKey,tls,Map(writer -> "writer",recovery -> "recovery"),port.toInt)
        }.flatMap { (root,installation,key,public,tls,identities,port) =>
          if action == "init" then IO.blocking(PolicyWitnessStore.initialize(root,installation,key,public)) *>
            IO.println("Policy witness store initialized; catalog enrollment is still required.")
          else Resource.fromAutoCloseable(IO.blocking(PolicyWitnessStore.open(root,installation,key,public))).use { store =>
            if action == "inspect" then IO.println(new String(canonical(store.diagnostics),java.nio.charset.StandardCharsets.UTF_8))
            else Resource.fromAutoCloseable(IO.blocking(PolicyWitnessServer.start(
              new InetSocketAddress("127.0.0.1",port),tls,store,installation,key,public,identities))).use { _ =>
              IO.println("Policy witness listening on the configured loopback TLS endpoint.") *> IO.never
            }
          }
        }
      case _ => IO.raiseError(ConfigurationFailure("policy-witness init|serve|inspect PROTECTED_CONFIG_FILE"))
  }
