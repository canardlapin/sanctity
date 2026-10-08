package sanctity.app

import cats.effect.{IO, IOApp}
import cats.effect.std.Semaphore
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.BidsInterpreter
import sanctity.http.RepositoryHttp
import sanctity.kernel.Problem
import sanctity.objects.FileBlobStore
import sanctity.postgres.{DatabaseConfig, PostgresDatabase}
import java.nio.file.Path

/** Loopback-only origin. A TLS reverse proxy is the external transport boundary. */
object ApiServer extends IOApp.Simple:
  def run: IO[Unit] =
    for
      config <- Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env))
      storage <- Checked.lift[IO, String](sys.env.get("SANCTITY_STORAGE").toRight(Problem.Unsupported("SANCTITY_STORAGE is required")))
      port <- Checked.lift[IO, Port](sys.env.getOrElse("SANCTITY_HTTP_PORT", "8080").toIntOption.flatMap(Port.fromInt)
        .filter(_.value > 0).toRight(Problem.Unsupported("invalid SANCTITY_HTTP_PORT")))
      _ <- PostgresDatabase.open[IO](config).use { db =>
        for
          _ <- db.migrate
          store <- FileBlobStore.open[IO](Path.of(storage))
          ids = IdSource.random[IO]
          catalog = db.catalog(ids)
          repository = new NativeRepository[IO](catalog, store, new BidsInterpreter[IO](store), ids)
          api = new RepositoryHttp[IO](catalog, repository, db.credentials(ids))
          slots <- Semaphore[IO](8)
          _ <- EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(port)
            .withMaxConnections(64).withHttpApp(api.app.mapF(response => slots.permit.use(_ => response)))
            .build.use(server => IO.println(s"Sanctity API listening on ${server.address}; storage: ${Path.of(storage).toAbsolutePath}") *> IO.never)
        yield ()
      }
    yield ()
