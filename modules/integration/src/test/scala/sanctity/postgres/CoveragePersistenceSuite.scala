package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.jawn.JawnParser
import org.http4s.{Header, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import sanctity.app.{CatalogCoverageSuite, CoverageFixture, RepositorySystem, TestFiles}
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.DatasetInterpreter
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.file.{Files, Path}

final class CoveragePersistenceSuite extends CatalogCoverageSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    PostgresFixture.resource.use(f => run(f.system, f.root)).unsafeRunSync()

  test("the release read over HTTP reports a level for each file and a summary per data type") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val repository = new NativeRepository[IO](f.system.catalog, f.system.store, new DatasetInterpreter[IO](f.system.store), ids)
      val credentials = f.database.credentials(ids)
      val app = new RepositoryHttp[IO](f.system.catalog, repository, credentials).app
      val source = f.root.resolve("source")
      def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
      for
        _ <- IO.blocking(Files.exists(source)).flatMap(exists => if exists then TestFiles.remove(source) else IO.unit)
        _ <- CoverageFixture.mixed(source)
        preview <- checked(repository.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](source)))
        receipt <- checked(repository.accept(f.system.context, preview.candidate.id, IdempotencyKey.from("coverage-http").toOption.get))
        token <- checked(credentials.issue(f.system.context.principal, "coverage-http", 3600))
        request = Request[IO](Method.GET, Uri.unsafeFromString(s"/v1/datasets/${receipt.release.dataset.render}/releases/${receipt.release.release.render}"))
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer " + token.secret))
        response <- app.run(request)
        _ = assertEquals(response.status, Status.Ok)
        body <- response.as[String].flatMap(t => IO.fromEither(new JawnParser().parse(t)))
        summary = body.hcursor.downField("levels").get[Vector[Json]]("summary").toOption.getOrElse(fail("no levels summary"))
        counts = summary.map(j => (j.hcursor.get[String]("group").toOption.get, j.hcursor.get[String]("level").toOption.get) -> j.hcursor.get[Int]("files").toOption.get).toMap
        _ = assertEquals(counts.get("func" -> "assessed"), Some(3))
        _ = assertEquals(counts.get("eeg" -> "described"), Some(6))
        _ = assertEquals(counts.get("eeg" -> "preserved"), Some(1))
        _ = assertEquals(counts.get("meg" -> "described"), Some(1))
        _ = assertEquals(counts.get("meg" -> "preserved"), Some(2))
        _ = assertEquals(counts.get("micr" -> "preserved"), Some(1))
        _ = assertEquals(counts.values.sum, body.hcursor.get[Vector[Json]]("entries").toOption.get.count(_.hcursor.get[String]("kind").contains("file")))
        entry = body.hcursor.get[Vector[Json]]("entries").toOption.get.find(_.hcursor.get[String]("path").contains(CoverageFixture.eegHeader)).getOrElse(fail("no EEG entry"))
        _ = assertEquals(entry.hcursor.get[String]("group").toOption -> entry.hcursor.get[String]("level").toOption, Some("eeg") -> Some("described"))
      yield ()
    }.unsafeRunSync()
  }
