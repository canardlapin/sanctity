package sanctity.postgres

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import org.http4s.*
import org.typelevel.ci.CIString
import sanctity.app.SyntheticFixture
import sanctity.application.*
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.{ZipException, ZipFile}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** SHARE2: one accepted release downloads as a single archive that verifies, under the same current authority as a
  * single file.
  */
final class ArchiveSuite extends FunSuite:
  override val munitTimeout = 5.minutes
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def key(s: String) = IdempotencyKey.from(s).toOption.get
  private def path(s: String) = RelPath.from(s).toOption.get
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, f.system.repository, credentials(f)).app
  private def request(method: Method, target: String, token: String, json: Option[Json] = None): Request[IO] =
    val req = Request[IO](method, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    json.fold(req)(j => req.withEntity(j.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
  private def publish(f: PgFixture): IO[AcceptanceReceipt] =
    checked(f.system.repository.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
      .flatMap(p => checked(f.system.repository.accept(f.system.context, p.candidate.id, key("initial"))))
  private def archiveUrl(r: ReleaseRef): String = s"/v1/datasets/${r.dataset.render}/releases/${r.release.render}/archive"
  private def header(r: Response[IO], name: String): Option[String] = r.headers.get(CIString(name)).map(_.head.value)

  /** Unpacked view of an archive: its entry names, and the bytes of every file. */
  private final case class Unpacked(names: Vector[String], files: Map[String, Array[Byte]])
  private def unpack(bytes: Array[Byte]): Unpacked =
    val file = Files.createTempFile("sanctity-archive-", ".zip")
    try
      Files.write(file, bytes)
      val zip = new ZipFile(file.toFile)
      try
        val entries = zip.entries().asScala.toVector
        Unpacked(entries.map(_.getName), entries.filterNot(_.isDirectory).map(e => e.getName -> zip.getInputStream(e).readAllBytes()).toMap)
      finally zip.close()
    finally Files.deleteIfExists(file)
  private def download(f: PgFixture, ref: ReleaseRef, token: String): IO[(Response[IO], Array[Byte])] =
    api(f).run(request(Method.GET, archiveUrl(ref), token)).flatMap(r => r.body.compile.to(Array).map(r -> _))
  private def status(f: PgFixture, ref: ReleaseRef, token: String): IO[Status] =
    api(f).run(request(Method.GET, archiveUrl(ref), token)).flatMap(r => r.body.compile.drain.attempt.as(r.status))
  private def sourceFiles(root: Path): Map[String, Array[Byte]] =
    val walk = Files.walk(root)
    try walk.iterator().asScala.filter(Files.isRegularFile(_)).map(p => root.relativize(p).iterator().asScala.mkString("/") -> Files.readAllBytes(p)).toMap
    finally walk.close()

  test("a whole release downloads as one archive whose files and checksum list match what was accepted") {
    PostgresFixture.resource.use { f =>
      for
        initial <- publish(f)
        owner <- checked(credentials(f).issue(f.system.context.principal, "owner", 3600))
        release <- checked(f.system.catalog.release(f.system.context, initial.release))
        got <- download(f, initial.release, owner.secret)
        (response, bytes) = got
        _ = assertEquals(response.status, Status.Ok)
        root = s"sanctity-${initial.release.dataset.render.take(8)}-${initial.release.release.render.take(8)}"
        _ = assertEquals(header(response, "Content-Type"), Some("application/zip"))
        _ = assertEquals(header(response, "Content-Disposition"), Some(s"""attachment; filename="$root.zip""""))
        _ = assertEquals(header(response, "X-Sanctity-Manifest"), Some(release.manifest.id.value.value))
        _ = assertEquals(header(response, "X-Sanctity-Files"), Some(release.manifest.files.size.toString))
        _ = assertEquals(header(response, "X-Sanctity-Bytes"), Some(release.manifest.files.map(_.blob.size).sum.toString))
        unpacked = unpack(bytes)
        // Exactly the source tree, under one folder, plus the checksum list beside it.
        source <- IO.blocking(sourceFiles(f.root.resolve("source")))
        _ = assertEquals(unpacked.files.keySet, source.keySet.map(n => s"$root/$n") + "SHA256SUMS.txt")
        _ = source.foreach((name, original) => assert(java.util.Arrays.equals(unpacked.files(s"$root/$name"), original), name))
        directories = release.manifest.directories.map(d => s"$root/${d.value}/").toSet + s"$root/"
        _ = assertEquals(unpacked.names.filter(_.endsWith("/")).toSet, directories)
        listed = new String(unpacked.files("SHA256SUMS.txt"), UTF_8).linesIterator.toVector
        _ = assertEquals(listed, source.keys.toVector.sorted.map(n => s"${Digest.bytes(source(n)).value}  $root/$n"))
        // The same release gives the same bytes again.
        again <- download(f, initial.release, owner.secret)
        _ = assert(java.util.Arrays.equals(again._2, bytes))
      yield ()
    }.unsafeRunSync()
  }

  test("the archive follows current access: content permission on that exact release, and nothing after revocation") {
    PostgresFixture.resource.use { f =>
      val dataset = f.system.dataset.id.render
      def grant(owner: String, recipient: PrincipalId, release: ReleaseRef, actions: String*): IO[String] =
        api(f).run(request(Method.POST, s"/v1/datasets/$dataset/grants", owner, Some(Json.obj("recipient" -> Json.fromString(recipient.render),
          "release" -> Json.fromString(release.release.render), "actions" -> Json.arr(actions.map(Json.fromString)*)))))
          .flatMap(r => r.as[String].map { body => assertEquals(r.status, Status.Created, body); new io.circe.jawn.JawnParser().parse(body).toOption.get.hcursor.get[String]("id").toOption.get })
      for
        initial <- publish(f)
        owner <- checked(credentials(f).issue(f.system.context.principal, "owner", 3600))
        guest <- IdSource.random[IO].next[PrincipalTag]
        _ <- grant(owner.secret, guest, initial.release, "ReadMetadata")
        token <- checked(credentials(f).issue(guest, "guest", 3600))
        metadataOnly <- status(f, initial.release, token.secret)
        _ = assertEquals(metadataOnly, Status.NotFound)
        content <- grant(owner.secret, guest, initial.release, "ReadMetadata", "ReadContent")
        mine <- download(f, initial.release, owner.secret)
        theirs <- download(f, initial.release, token.secret)
        _ = assertEquals(theirs._1.status, Status.Ok)
        _ = assert(java.util.Arrays.equals(theirs._2, mine._2))
        // A later release is not covered by a fixed-release grant.
        preview <- checked(f.system.repository.correctMetadata(f.system.context, initial.release, path(SyntheticFixture.sharedSidecar),
          SyntheticFixture.correctedJson, "second release"))
        next <- checked(f.system.repository.accept(f.system.context, preview.candidate.id, key("second")))
        later <- status(f, next.release, token.secret)
        _ = assertEquals(later, Status.NotFound)
        newer <- download(f, next.release, owner.secret)
        _ = assertEquals(newer._1.status, Status.Ok)
        _ = assert(!java.util.Arrays.equals(newer._2, mine._2))
        revoked <- api(f).run(request(Method.DELETE, s"/v1/datasets/$dataset/grants/$content", owner.secret))
        _ = assertEquals(revoked.status, Status.NoContent)
        after <- status(f, initial.release, token.secret)
        _ = assertEquals(after, Status.NotFound)
      yield ()
    }.unsafeRunSync()
  }

  test("a download that loses access part-way fails, and what was delivered is not a valid archive") {
    PostgresFixture.resource.use { f =>
      for
        _ <- IO.blocking {
          val image = f.root.resolve("source").resolve(SyntheticFixture.firstImage)
          // Keep a valid image header while making the file span many authority checks.
          val _ = Files.write(image, java.util.Arrays.copyOf(Files.readAllBytes(image), 2 * 1024 * 1024))
        }
        initial <- publish(f)
        token <- checked(credentials(f).issue(f.system.context.principal, "stream", 3600))
        response <- api(f).run(request(Method.GET, archiveUrl(initial.release), token.secret))
        _ = assertEquals(response.status, Status.Ok)
        delivered <- Ref.of[IO, Vector[Byte]](Vector.empty)
        revoked <- Ref.of[IO, Boolean](false)
        // The credential is revoked as soon as the first bytes arrive.
        attempt <- response.body.chunks.evalTap(c => delivered.update(_ ++ c.toVector) *>
          revoked.getAndSet(true).flatMap(done => if done then IO.unit else checked(credentials(f).revoke(token.id)))).compile.drain.attempt
        _ = assert(attempt.left.toOption.exists { case Failure(Problem.Denied) => true; case _ => false }, attempt.toString)
        sent <- delivered.get
        _ = assert(sent.nonEmpty && sent.size < 2 * 1024 * 1024, sent.size.toString)
        _ = intercept[ZipException](unpack(sent.toArray))
        closed <- status(f, initial.release, token.secret)
        _ = assertEquals(closed, Status.Unauthorized)
      yield ()
    }.unsafeRunSync()
  }
