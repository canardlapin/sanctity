package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import org.http4s.{Header, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import sanctity.app.{CatalogBehaviouralSuite, RepositorySystem}
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.RepositoryBidsInterpreter
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.file.{Files, Path}

final class BehaviouralPersistenceSuite extends CatalogBehaviouralSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    PostgresFixture.resource.use(f => run(f.system, f.root)).unsafeRunSync()

  test("authenticated corrections accept behavioural TSV and assess invalid replacements") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val repository = new NativeRepository[IO](f.system.catalog, f.system.store, new RepositoryBidsInterpreter[IO](f.system.store), ids)
      val credentials = f.database.credentials(ids)
      val app = new RepositoryHttp[IO](f.system.catalog, repository, credentials).app
      val path = "sub-01/beh/sub-01_task-choice_beh.tsv"
      def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
      for
        _ <- IO.blocking {
          Files.createDirectories(f.root.resolve("source/sub-01/beh"))
          val _ = Files.writeString(f.root.resolve("source").resolve(path), "trial\tresponse_time\nleft\t0.5\n")
        }
        preview <- checked(repository.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        release <- checked(repository.accept(f.system.context, preview.candidate.id, IdempotencyKey.from("behavioural-http-source").toOption.get))
        token <- checked(credentials.issue(f.system.context.principal, "behavioural-http", 3600))
        endpoint = s"/v1/datasets/${release.release.dataset.render}/releases/${release.release.release.render}/corrections"
        _ <- Vector("trial\tresponse_time\nright\t0.25\n", "trial\tresponse_time\nright\tNaN\n").zipWithIndex.traverse_ { (text, index) =>
          val request = Request[IO](Method.POST, Uri.unsafeFromString(endpoint))
            .withEntity(Json.obj("path" -> Json.fromString(path), "text" -> Json.fromString(text), "reason" -> Json.fromString("correct response")).noSpaces)
            .putHeaders(Header.Raw(CIString("Authorization"), "Bearer " + token.secret), Header.Raw(CIString("Content-Type"), "application/json"))
          for
            response <- app.run(request)
            _ = assertEquals(response.status, Status.Created)
            body <- response.as[String].flatMap(t => IO.fromEither(new JawnParser().parse(t)))
            id <- Checked.lift[IO, CandidateId](Id.parse[CandidateTag](body.hcursor.downField("candidate").get[String]("id").toOption.get))
            candidate <- checked(f.system.catalog.candidate(f.system.context, id))
            _ = assertEquals(candidate.assessment.admissible, index == 0)
          yield ()
        }
      yield ()
    }.unsafeRunSync()
  }
