package sanctity.app

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.std.Semaphore
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Response, Status}
import org.http4s.server.Server
import sanctity.application.{IdSource, NativeRepository}
import sanctity.bids.BidsInterpreter
import sanctity.http.{ConsoleAssets, RepositoryHttp}
import sanctity.objects.FileBlobStore
import scala.concurrent.duration.*

/** Loopback-only service origin. A TLS reverse proxy is the external transport boundary. */
object ApiServer extends ProtectedIOApp:
  private[app] def server(port: Int, app: HttpApp[IO], shutdown: FiniteDuration): Resource[IO, Server] =
    EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(port).get)
      .withMaxConnections(64).withMaxHeaderSize(16384).withRequestHeaderReceiveTimeout(10.seconds)
      .withIdleTimeout(60.seconds).withShutdownTimeout(shutdown).withHttpApp(app)
      .withConnectionErrorHandler { case _ => IO.unit }
      .withErrorHandler { case _ => IO.pure(Response[IO](Status.InternalServerError)) }
      .withOnWriteFailure((_, _, _) => IO.unit)
      .withRequestLineParseErrorHandler(_ => IO.pure(Response[IO](Status.BadRequest)))
      .build

  def run(args: List[String]): IO[ExitCode] = ServiceConfiguration.exit {
    for
      _ <- IO.raiseWhen(args.nonEmpty)(ConfigurationFailure("serve takes no arguments"))
      config <- ServiceConfiguration.load(sys.env)
      _ <- PolicyClientConfiguration.database(config.database,sys.env).use { db =>
        for
          _ <- db.checkCompatibility()
          _ <- db.checkRuntimeRole
          _ <- db.checkOrganizationReady
          store <- FileBlobStore.open[IO](config.storage)
          ids = IdSource.random[IO]
          catalog = db.catalog(ids)
          repository = new NativeRepository[IO](catalog, store, new BidsInterpreter[IO](store), ids)
          api = new RepositoryHttp[IO](catalog, repository, db.credentials(ids), independentPolicy=sys.env.contains("SANCTITY_POLICY_CONFIG"))
          slots <- Semaphore[IO](8)
          protectedApp = ProtectedOrigin(ConsoleAssets.withApi(api.app), config.authority, config.publicOrigin)
          _ <- server(config.port, protectedApp.mapF(response => slots.permit.use(_ => response)), config.shutdownTimeout)
            .use(_ => IO.println("Sanctity API and console started on the configured loopback origin.") *> IO.never)
        yield ()
      }
    yield ()
  }
