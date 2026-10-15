package sanctity.app

import cats.effect.{ExitCode, IO, IOApp}
import io.circe.Json
import sanctity.application.*
import sanctity.bids.RepositoryBidsInterpreter
import sanctity.kernel.*
import sanctity.objects.{FileBlobStore, NativeDirectorySource}
import sanctity.postgres.{DatabaseConfig, PostgresDatabase}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

/** Trusted local operator entry point. Database credentials confer operator authority.
  * Secret arguments never appear in commands: issued tokens go to a new mode-0600 file.
  */
object RepositoryCli extends IOApp:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def id[A](s: String): IO[Id[A]] = Checked.lift[IO, Id[A]](Id.parse[A](s))
  private def output(j: Json): IO[Unit] = IO.println(j.spaces2)
  def run(args: List[String]): IO[ExitCode] =
    Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env)).flatMap { config =>
      PostgresDatabase.open[IO](config).use { db =>
        db.migrate *> command(db, args)
      }.as(ExitCode.Success)
    }

  private[sanctity] def command(db: PostgresDatabase[IO], args: List[String]): IO[Unit] =
    val ids = IdSource.random[IO]
    val catalog = db.catalog(ids)
    args match
      case "create" :: principal :: custodian :: Nil =>
        for
          p <- id[PrincipalTag](principal)
          c <- id[CustodianTag](custodian)
          ds <- catalog.create(AccessContext(p), c)
          _ <- output(Json.obj("dataset" -> Json.fromString(ds.id.render), "principal" -> Json.fromString(p.render),
            "custodian" -> Json.fromString(c.render)))
        yield ()
      case "import" :: principal :: dataset :: source :: storage :: key :: Nil =>
        for
          p <- id[PrincipalTag](principal)
          d <- id[DatasetTag](dataset)
          k <- Checked.lift[IO, IdempotencyKey](IdempotencyKey.from(key))
          store <- FileBlobStore.open[IO](Path.of(storage))
          repo = new NativeRepository[IO](catalog, store, new RepositoryBidsInterpreter[IO](store), ids)
          imports = new DurableImports[IO](catalog, db.journal(ids), store, repo, ids)
          native = new NativeDirectorySource[IO](Path.of(source).toAbsolutePath.normalize())
          op <- checked(imports.begin(AccessContext(p), d, k, native))
          _ <- IO.println(s"Import operation: ${op.id.render}")
          done <- checked(imports.resume(AccessContext(p), op.id, native, publish = true))
          receipt <- IO.fromOption(done.receipt)(Failure(Problem.Integrity("import has no acceptance receipt")))
          _ <- output(Json.obj("dataset" -> Json.fromString(d.render), "release" -> Json.fromString(receipt.release.release.render),
            "operation" -> Json.fromString(op.id.render)))
        yield ()
      case "issue-credential" :: principal :: seconds :: label :: file :: Nil =>
        for
          p <- id[PrincipalTag](principal)
          ttl <- Checked.lift[IO, Int](seconds.toIntOption.toRight(Problem.Unsupported("invalid lifetime")))
          credential <- checked(db.credentials(ids).issue(p, label, ttl))
          document = Json.obj("credential" -> Json.fromString(credential.id.render), "principal" -> Json.fromString(p.render),
            "expiresAt" -> Json.fromString(credential.expiresAt.toString), "token" -> Json.fromString(credential.secret)).spaces2 + "\n"
          written <- IO.blocking {
            val channel = FileChannel.open(Path.of(file),
              Set(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).asJava,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try
              val bytes = ByteBuffer.wrap(document.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              while bytes.hasRemaining do channel.write(bytes)
              channel.force(true)
            finally channel.close()
          }.attempt
          _ <- written.fold(e => db.credentials(ids).revoke(credential.id) *> IO.raiseError[Unit](e), _ => IO.unit)
          _ <- IO.println(s"Credential ${credential.id.render} written to a new private file. Expires: ${credential.expiresAt}")
        yield ()
      case "revoke-credential" :: credential :: Nil =>
        id[CredentialTag](credential).flatMap(c => checked(db.credentials(ids).revoke(c))) *> IO.println("Credential revoked.")
      case _ => IO.raiseError(new IllegalArgumentException(
        "RepositoryCli create PRINCIPAL CUSTODIAN | import PRINCIPAL DATASET SOURCE STORAGE KEY | " +
        "issue-credential PRINCIPAL SECONDS LABEL NEW_FILE | revoke-credential CREDENTIAL_ID"))
