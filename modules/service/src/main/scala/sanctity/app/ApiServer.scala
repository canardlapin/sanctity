package sanctity.app

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.std.Semaphore
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Response, Status}
import org.http4s.server.Server
import sanctity.application.{BlobStore, BrowserUploads, IdSource, NativeRepository, ScientificViews}
import sanctity.bids.{ReleaseDescriptions, RepositoryBidsContext}
import sanctity.http.{ConsoleAssets, RepositoryHttp}
import sanctity.objects.FileBlobStore
import sanctity.postgres.PostgresDatabase
import scala.concurrent.duration.*

/** Loopback service origin. A TLS reverse proxy is the external transport boundary. The listener binds loopback
  * unless the explicit `container` deployment mode selects the container's interfaces; the origin never widens.
  */
object ApiServer extends ProtectedIOApp:
  private[app] def server(port: Int, app: HttpApp[IO], shutdown: FiniteDuration,
      bindAddress: String = ServiceConfiguration.loopbackAddress): Resource[IO, Server] =
    EmberServerBuilder.default[IO].withHost(Host.fromString(bindAddress).get).withPort(Port.fromInt(port).get)
      .withMaxConnections(64).withMaxHeaderSize(16384).withRequestHeaderReceiveTimeout(10.seconds)
      .withIdleTimeout(60.seconds).withShutdownTimeout(shutdown).withHttpApp(app)
      .withConnectionErrorHandler { case _ => IO.unit }
      .withErrorHandler { case _ => IO.pure(Response[IO](Status.InternalServerError)) }
      .withOnWriteFailure((_, _, _) => IO.unit)
      .withRequestLineParseErrorHandler(_ => IO.pure(Response[IO](Status.BadRequest)))
      .build

  private[sanctity] def routes(db: PostgresDatabase[IO], store: BlobStore[IO], ids: IdSource[IO],
      independentPolicy: Boolean = false, login: Option[InstitutionalLogin] = None): HttpApp[IO] =
    val catalog = db.catalog(ids)
    val repository = new NativeRepository(catalog, store, ServiceInterpreters(store), ids, Some(new ReleaseDescriptions(store)))
    val views = RepositoryBidsContext.Modalities.flatMap(m => m.rules.map(rule => m.id ->
      new ScientificViews[IO](catalog, store, repository, rule.view(RepositoryBidsContext.Repository), ids))).toMap
    new RepositoryHttp(catalog, repository, db.credentials(ids), independentPolicy, Some(db.organization),
      views, Some(new BrowserUploads(catalog, db.journal(ids), store, repository, ids)),
      login.map(_.authenticate), login.map(provider => (ctx,d,q) => catalog.recipients(ctx,d,provider.issuer,q))).app

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
          login <- InstitutionalLogin.configured(sys.env,config.publicOrigin,db.browserIdentities)
          api = routes(db, store, ids, sys.env.contains("SANCTITY_POLICY_CONFIG"), login)
          slots <- Semaphore[IO](8)
          protectedApp = ProtectedOrigin(login.fold(ConsoleAssets.withApi(api))(_.wrap(ConsoleAssets.withApi(api))), config.authority, config.publicOrigin)
          started = if config.bindAddress == ServiceConfiguration.loopbackAddress
            then "Sanctity API and console started on the configured loopback origin."
            else "Sanctity API and console started for the configured loopback origin, listening on every interface of this container."
          _ <- server(config.port, protectedApp.mapF(response => slots.permit.use(_ => response)), config.shutdownTimeout, config.bindAddress)
            .use(_ => IO.println(started) *> IO.never)
        yield ()
      }
    yield ()
  }
