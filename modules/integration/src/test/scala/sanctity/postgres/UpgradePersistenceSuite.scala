package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.jawn.JawnParser
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString
import sanctity.app.{CatalogUpgradeSuite, CoverageFixture, EarlierBuild, RepositorySystem, TestFiles}
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.DatasetInterpreter
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.file.{Files, Path}

final class UpgradePersistenceSuite extends CatalogUpgradeSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    PostgresFixture.resource.use(f => run(f.system, f.root)).unsafeRunSync()

  test("over HTTP a correction is refused with its own code until the release is re-assessed and accepted") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val store = f.system.store
      val earlier = new NativeRepository[IO](f.system.catalog, store, new EarlierBuild(new DatasetInterpreter[IO](store), EarlierBuild.withoutLevels), ids)
      val repository = new NativeRepository[IO](f.system.catalog, store, new DatasetInterpreter[IO](store), ids)
      val credentials = f.database.credentials(ids)
      val app = new RepositoryHttp[IO](f.system.catalog, repository, credentials).app
      val source = f.root.resolve("source")
      val dataset = f.system.dataset.id.render
      def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
      def parsed(response: Response[IO]): IO[Json] = response.as[String].flatMap(t => IO.fromEither(new JawnParser().parse(t)))
      def post(token: String, path: String, body: Json, key: Option[String] = None): IO[Response[IO]] =
        val request = Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body.noSpaces)
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer " + token), Header.Raw(CIString("Content-Type"), "application/json"))
        app.run(key.fold(request)(k => request.putHeaders(Header.Raw(CIString("Idempotency-Key"), k))))
      def correction(token: String, release: String): IO[Response[IO]] =
        post(token, s"/v1/datasets/$dataset/releases/$release/corrections", Json.obj("path" -> Json.fromString(CoverageFixture.eegSidecar),
          "text" -> Json.fromString("""{"TaskName":"rest","SamplingFrequency":1000}"""), "reason" -> Json.fromString("Recorded at 1 kHz")))
      for
        _ <- IO.blocking(Files.exists(source)).flatMap(exists => if exists then TestFiles.remove(source) else IO.unit)
        _ <- CoverageFixture.described(source)
        preview <- checked(earlier.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](source)))
        old <- checked(earlier.accept(f.system.context, preview.candidate.id, IdempotencyKey.from("earlier-build-http").toOption.get))
        token <- checked(credentials.issue(f.system.context.principal, "upgrade-http", 3600)).map(_.secret)
        refused <- correction(token, old.release.release.render)
        _ = assertEquals(refused.status, Status.Conflict)
        code <- parsed(refused).map(_.hcursor.get[String]("error").toOption)
        _ = assertEquals(code, Some("reassessment_required"))
        review <- post(token, s"/v1/datasets/$dataset/releases/${old.release.release.render}/reassessments",
          Json.obj("reason" -> Json.fromString("Record coverage levels under the current rules")))
        _ = assertEquals(review.status, Status.Created)
        candidate <- parsed(review).map(_.hcursor.downField("candidate").get[String]("id").toOption.getOrElse(fail("no candidate")))
        accepted <- post(token, s"/v1/candidates/$candidate/accept", Json.obj(), Some("re-assess-http"))
        _ = assertEquals(accepted.status, Status.Ok)
        head <- checked(f.system.catalog.dataset(f.system.context, f.system.dataset.id)).map(_.head.getOrElse(fail("no head")))
        _ = assertNotEquals(head, old.release.release)
        fresh <- checked(f.system.catalog.release(f.system.context, ReleaseRef(f.system.dataset.id, head)))
        _ = assertEquals(fresh.activity.kind, ActivityKind.Reinterpretation)
        _ = assert(fresh.interpretation.levels.nonEmpty)
        allowed <- correction(token, head.render)
        _ = assertEquals(allowed.status, Status.Created)
      yield ()
    }.unsafeRunSync()
  }
