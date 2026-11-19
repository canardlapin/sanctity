package sanctity.app

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import io.circe.Json
import sanctity.application.*
import sanctity.kernel.*
import sanctity.http.ReleaseArchive
import sanctity.objects.{DirectoryExport, FileBlobStore, NativeDirectorySource}
import sanctity.postgres.PostgresDatabase
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

/** Packaged trusted local operator entry point. Database credentials confer operator authority.
  * Secret arguments never appear in commands: issued tokens go to a new mode-0600 file.
  */
object RepositoryCli extends ProtectedIOApp:
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def id[A](s: String): IO[Id[A]] = Checked.lift[IO, Id[A]](Id.parse[A](s))
  private def output(j: Json): IO[Unit] = IO.println(j.spaces2)
  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    ServiceConfiguration.database(sys.env).flatMap { config =>
      PolicyClientConfiguration.database(config,sys.env).use { db =>
        command(db, args)
      }
    }
  }

  private[sanctity] def command(db: PostgresDatabase[IO], args: List[String]): IO[Unit] =
    db.requireOperator *> db.checkCompatibility() *> trustedCommand(db, args)

  private def trustedCommand(db: PostgresDatabase[IO], args: List[String]): IO[Unit] =
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
          repo = new NativeRepository[IO](catalog, store, ServiceInterpreters(store), ids)
          imports = new DurableImports[IO](catalog, db.journal(ids), store, repo, ids)
          native = new NativeDirectorySource[IO](Path.of(source).toAbsolutePath.normalize())
          op <- checked(imports.begin(AccessContext(p), d, k, native))
          _ <- IO.println(s"Import operation: ${op.id.render}")
          done <- checked(imports.resume(AccessContext(p), op.id, native, publish = true))
          receipt <- IO.fromOption(done.receipt)(Failure(Problem.Integrity("import has no acceptance receipt")))
          _ <- output(Json.obj("dataset" -> Json.fromString(d.render), "release" -> Json.fromString(receipt.release.release.render),
            "operation" -> Json.fromString(op.id.render)))
        yield ()
      case "export" :: principal :: dataset :: release :: storage :: target :: Nil =>
        // One accepted release as a new directory, with the same checksum list the HTTP archive carries beside it.
        for
          p <- id[PrincipalTag](principal)
          ref <- (id[DatasetTag](dataset), id[ReleaseTag](release)).mapN(ReleaseRef.apply)
          store <- FileBlobStore.open[IO](Path.of(storage))
          repo = new NativeRepository[IO](catalog, store, ServiceInterpreters(store), ids)
          directory = Path.of(target).toAbsolutePath.normalize()
          sums = directory.resolveSibling(directory.getFileName.toString + ".sha256")
          exists <- IO.blocking(Files.exists(sums))
          _ <- Checked.lift[IO, Unit](Either.cond(!exists, (), Problem.Conflict("checksum file already exists")))
          exported <- checked(repo.release(AccessContext(p), ref))
          receipt <- checked(repo.exportFull(AccessContext(p), ref, new DirectoryExport[IO](directory)))
          _ <- IO.blocking(Files.writeString(sums, ReleaseArchive.checksums(directory.getFileName.toString, exported.manifest), StandardOpenOption.CREATE_NEW))
          _ <- output(Json.obj("dataset" -> Json.fromString(ref.dataset.render), "release" -> Json.fromString(ref.release.render),
            "directory" -> Json.fromString(directory.toString), "checksums" -> Json.fromString(sums.toString),
            "manifest" -> Json.fromString(receipt.manifest.value.value), "files" -> Json.fromInt(receipt.files),
            "directories" -> Json.fromInt(receipt.directories), "bytes" -> Json.fromLong(receipt.bytes)))
        yield ()
      case (action @ ("issue-credential" | "issue-credential-once")) :: rest =>
        val arguments = if action == "issue-credential" then None -> rest else rest match
          case operation :: tail => Some(operation) -> tail
          case _ => None -> Nil
        val (operation, values) = arguments
        val parsed = values match
          case principal :: seconds :: label :: file :: Nil => IO.pure((principal,seconds,label,file))
          case _ => IO.raiseError(new IllegalArgumentException("credential arguments required"))
        for
          values <- parsed
          (principal,seconds,label,file) = values
          p <- id[PrincipalTag](principal)
          ttl <- Checked.lift[IO, Int](seconds.toIntOption.toRight(Problem.Unsupported("invalid lifetime")))
          op <- operation.traverse(id[CredentialTag])
          credential <- checked(op.fold(db.credentials(ids).issue(p,label,ttl))(db.credentials(ids).issueOnce(_,p,label,ttl)))
          document = Json.obj("credential" -> Json.fromString(credential.id.render), "principal" -> Json.fromString(p.render),
            "expiresAt" -> Json.fromString(credential.expiresAt.toString), "durability" -> Json.fromString(credential.durability.toString), "token" -> Json.fromString(credential.secret)).spaces2 + "\n"
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
          _ <- IO.println(s"Credential ${credential.id.render} written to a new private file. Durability: ${credential.durability}. Expires: ${credential.expiresAt}")
        yield ()
      case "bind-identity" :: principal :: issuer :: subject :: name :: Nil =>
        id[PrincipalTag](principal).flatMap(p => checked(db.browserIdentities.bind(p,issuer,subject,name)))
      case "unbind-identity" :: principal :: issuer :: subject :: Nil =>
        id[PrincipalTag](principal).flatMap(p => checked(db.browserIdentities.unbind(p,issuer,subject)))
      case "revoke-credential" :: credential :: Nil =>
        id[CredentialTag](credential).flatMap(c => checked(db.credentials(ids).revokeAcknowledged(c)))
          .flatMap(d => IO.println(s"Credential revoked. Durability: $d."))
      case _ => IO.raiseError(new IllegalArgumentException(
        "RepositoryCli create PRINCIPAL CUSTODIAN | import PRINCIPAL DATASET SOURCE STORAGE KEY | " +
        "export PRINCIPAL DATASET RELEASE STORAGE NEW_DIRECTORY | " +
        "issue-credential PRINCIPAL SECONDS LABEL NEW_FILE | issue-credential-once OPERATION_ID PRINCIPAL SECONDS LABEL NEW_FILE | revoke-credential CREDENTIAL_ID | " +
        "bind-identity PRINCIPAL ISSUER SUBJECT DISPLAY_NAME | unbind-identity PRINCIPAL ISSUER SUBJECT"))
