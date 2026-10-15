package sanctity.app

import cats.effect.{IO, Resource}
import sanctity.policy.*
import sanctity.postgres.{DatabaseConfig, PolicyConnection, PostgresDatabase}
import java.net.URI
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import scala.jdk.CollectionConverters.*

/** The writer has a client identity and signing PUBLIC pin only. Recovery uses a
  * different protected file/identity; witness signing private keys are never inputs.
  */
object PolicyClientConfiguration:
  import PolicyWire.*
  private def read(path: Path): Array[Byte] =
    val noFollow = LinkOption.NOFOLLOW_LINKS
    check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path &&
      Files.isRegularFile(path,noFollow) && Files.size(path) <= maximumBytes &&
      Files.getOwner(path,noFollow).getName == System.getProperty("user.name") &&
      Files.getPosixFilePermissions(path,noFollow) == PosixFilePermissions.fromString("rw-------") &&
      Files.getPosixFilePermissions(path.getParent,noFollow) == PosixFilePermissions.fromString("rwx------"),"protected-client-input")
    val in = Files.newInputStream(path,noFollow)
    try
      val bytes = in.readNBytes(maximumBytes+1)
      check(bytes.length <= maximumBytes,"client-input-size")
      bytes
    finally in.close()

  def load(path: Path, excludedRoots: Vector[Path] = Vector.empty): Resource[IO,PolicyConnection] = Resource.make(IO.blocking {
    check(!excludedRoots.exists(path.startsWith),"client-storage-isolation")
    val config = parse(read(path))
    fields(config,Set("installation","endpoint","identityStore","identityPasswordFile","trustStore","trustPasswordFile","signingPublic"))
    def input(name: String): Array[Byte] =
      val target = Path.of(text(config,name))
      check(!excludedRoots.exists(target.startsWith),"client-storage-isolation")
      read(target)
    def password(name: String): Array[Char] =
      val value = utf8(input(name)).stripSuffix("\n")
      check(value.nonEmpty && value.length <= 4096 && !value.exists(_.isControl),"client-password")
      value.toCharArray
    def keyStore(name: String, password: Array[Char]): KeyStore =
      val result = KeyStore.getInstance("PKCS12")
      val stream = new java.io.ByteArrayInputStream(input(name))
      try result.load(stream,password) finally stream.close()
      result
    val secret = password("identityPasswordFile")
    val anchorsSecret = password("trustPasswordFile")
    try
      val identity = keyStore("identityStore",secret)
      val aliases = identity.aliases().asScala.filter(identity.isKeyEntry).toVector
      check(aliases.size == 1,"one-client-identity-required")
      val fingerprint = digest(identity.getCertificate(aliases.head).getEncoded)
      val installation = id(text(config,"installation"))
      val tls = PolicyTls.context(identity,secret,keyStore("trustStore",anchorsSecret))
      new PolicyConnection(installation,fingerprint,new PolicyWitnessClient(URI.create(text(config,"endpoint")),
        installation,tls,publicKey(input("signingPublic"))))
    finally
      java.util.Arrays.fill(secret,'\u0000')
      java.util.Arrays.fill(anchorsSecret,'\u0000')
  }.handleErrorWith(_ => IO.raiseError(ConfigurationFailure("policy trust bundle"))))(p => IO.blocking(p.client.close()))

  def database(config: DatabaseConfig, values: Map[String,String]): Resource[IO,PostgresDatabase[IO]] =
    val policy = values.get("SANCTITY_POLICY_CONFIG") match
      case None => Resource.pure[IO,Option[PolicyConnection]](None)
      case Some(path) => load(Path.of(path),values.get("SANCTITY_STORAGE").toVector.map(p => Path.of(p).toAbsolutePath.normalize())).map(Some(_))
    policy.flatMap(p => PostgresDatabase.open[IO](config,p))
