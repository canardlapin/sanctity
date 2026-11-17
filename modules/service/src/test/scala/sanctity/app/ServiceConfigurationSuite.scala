package sanctity.app

import cats.data.Kleisli
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.http4s.{Header, HttpApp, Request, Response, Status, Uri}
import org.typelevel.ci.CIString
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class ServiceConfigurationSuite extends FunSuite:
  private val canary = "DO_NOT_PRINT_password_origin_path_8c21"
  private def mode(p: Path, value: String): Unit =
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(value)): Unit
  private def fixture(f: (Path, Map[String, String]) => IO[Unit]): Unit =
    Resource.make(IO.blocking {
      val root = Files.createTempDirectory("sanctity-config-").toRealPath()
      mode(root, "rwx------")
      val storage = Files.createDirectory(root.resolve("storage"))
      mode(storage, "rwx------")
      root
    })(root => IO.blocking {
      val stream = Files.walk(root)
      try stream.iterator().asScala.toVector.reverse.foreach { p =>
        if !Files.isSymbolicLink(p) then mode(p, if Files.isDirectory(p) then "rwx------" else "rw-------")
        Files.deleteIfExists(p)
      }
      finally stream.close()
    }).use { root =>
      f(root, Map("SANCTITY_JDBC_URL" -> "jdbc:postgresql://127.0.0.1:5432/test",
        "SANCTITY_DB_USER" -> "test", "SANCTITY_DB_PASSWORD" -> canary,
        "SANCTITY_STORAGE" -> root.resolve("storage").toString))
    }.unsafeRunSync()

  private def denied(values: Map[String, String]): IO[Unit] = ServiceConfiguration.load(values).attempt.map { result =>
    assert(result.isLeft)
    val error = result.swap.toOption.get
    assert(error.isInstanceOf[ConfigurationFailure])
    assert(!error.toString.contains(canary))
    assertEquals(error.getCause, null)
  }

  test("valid private storage and environment credentials have redacted diagnostics") {
    fixture { (root, values) => ServiceConfiguration.load(values).map { c =>
      assertEquals(c.port, 8080)
      assertEquals(c.publicOrigin, "http://127.0.0.1:8080")
      assertEquals(c.shutdownTimeout, 10.seconds)
      assert(!c.toString.contains(root.toString))
      assert(!c.database.toString.contains(canary))
      val staging = Files.list(root.resolve("storage/staging"))
      try assertEquals(staging.count(), 0L)
      finally staging.close()
    }}
  }

  test("password file supports one terminal newline, private owner/mode and exclusive source") {
    fixture { (root, values) =>
      val file = root.resolve("password")
      Files.writeString(file, canary + "\n"); mode(file, "rw-------")
      val configured = (values - "SANCTITY_DB_PASSWORD") + ("SANCTITY_DB_PASSWORD_FILE" -> file.toString)
      for
        _ <- ServiceConfiguration.load(configured)
        _ <- denied(configured + ("SANCTITY_DB_PASSWORD" -> canary))
        _ <- IO(mode(file, "rw-r--r--"))
        _ <- denied(configured)
        _ <- IO { mode(file, "rw-------"); Files.writeString(file, "x" * 4097) }
        _ <- denied(configured)
      yield ()
    }
  }

  test("missing, relative, aliased, read-only and exposed storage roots fail closed") {
    fixture { (root, values) =>
      val storage = root.resolve("storage")
      Files.createSymbolicLink(root.resolve("alias"), storage)
      val bad = Vector(root.resolve(canary).toString, ".", root.resolve("alias").toString,
        storage.resolve("..").resolve("storage").toString, Path.of("").toRealPath().toString)
      bad.foldLeft(IO.unit)((io, p) => io *> denied(values.updated("SANCTITY_STORAGE", p))) *>
        IO(mode(storage, "r-x------")) *> denied(values) *>
        IO(mode(storage, "rwxr-xr-x")) *> denied(values)
    }
  }

  test("storage children cannot redirect writes and credentials cannot live in blob storage") {
    fixture { (root, values) =>
      val storage = root.resolve("storage")
      val other = Files.createDirectory(root.resolve("other")); mode(other, "rwx------")
      Files.createSymbolicLink(storage.resolve("staging"), other)
      denied(values) *> IO {
        Files.delete(storage.resolve("staging"))
        val file = storage.resolve("password"); Files.writeString(file, canary); mode(file, "rw-------")
      } *> denied((values - "SANCTITY_DB_PASSWORD").updated("SANCTITY_DB_PASSWORD_FILE", storage.resolve("password").toString))
    }
  }

  test("invalid ports, shutdown bounds, database URLs, pool and unsafe origins do not echo inputs") {
    fixture { (_, values) =>
      val bad = Vector(
        "SANCTITY_HTTP_PORT" -> "0", "SANCTITY_HTTP_PORT" -> "65536",
        "SANCTITY_SHUTDOWN_SECONDS" -> "0", "SANCTITY_SHUTDOWN_SECONDS" -> "121",
        "SANCTITY_DB_POOL_SIZE" -> "100", "SANCTITY_DB_SCHEMA" -> "public",
        "SANCTITY_JDBC_URL" -> s"jdbc:postgresql://test:$canary@localhost/test",
        "SANCTITY_JDBC_URL" -> s"jdbc:postgresql://localhost/test?password=$canary",
        "SANCTITY_PUBLIC_ORIGIN" -> s"http://$canary.invalid",
        "SANCTITY_PUBLIC_ORIGIN" -> "https://example.org/",
        "SANCTITY_DEPLOYMENT_MODE" -> "public"
      )
      bad.foldLeft(IO.unit)((io, setting) => io *> denied(values + setting)) *>
        denied(values ++ Map("SANCTITY_DEPLOYMENT_MODE" -> "proxy", "SANCTITY_PUBLIC_ORIGIN" -> s"https://user:$canary@example.org"))
    }
  }

  test("proxy mode uses a fixed HTTPS origin; forwarded claims never select the origin") {
    fixture { (_, values) =>
      ServiceConfiguration.load(values ++ Map("SANCTITY_DEPLOYMENT_MODE" -> "proxy",
        "SANCTITY_PUBLIC_ORIGIN" -> "https://repository.example.org")).map { c =>
        assertEquals(c.authority, "repository.example.org")
      }
    }
  }

  test("only the explicit container mode binds beyond loopback, and it keeps the loopback origin") {
    fixture { (_, values) =>
      val container = values + ("SANCTITY_DEPLOYMENT_MODE" -> "container")
      for
        default <- ServiceConfiguration.load(values)
        _ = assertEquals(default.bindAddress, "127.0.0.1")
        proxy <- ServiceConfiguration.load(values ++ Map("SANCTITY_DEPLOYMENT_MODE" -> "proxy",
          "SANCTITY_PUBLIC_ORIGIN" -> "https://repository.example.org"))
        _ = assertEquals(proxy.bindAddress, "127.0.0.1")
        c <- ServiceConfiguration.load(container + ("SANCTITY_HTTP_PORT" -> "9090"))
        _ = assertEquals(c.bindAddress, "0.0.0.0")
        _ = assertEquals(c.publicOrigin, "http://127.0.0.1:9090")
        _ = assertEquals(c.authority, "127.0.0.1:9090")
        explicit <- ServiceConfiguration.load(container + ("SANCTITY_PUBLIC_ORIGIN" -> "http://127.0.0.1:8080"))
        _ = assertEquals(explicit.authority, "127.0.0.1:8080")
        // The wider listener never widens the origin: no wildcard, name, other port, HTTPS or foreign host.
        _ <- Vector("http://0.0.0.0:8080", "http://localhost:8080", "http://127.0.0.1:8081",
          "https://repository.example.org", s"http://$canary.invalid:8080").foldLeft(IO.unit)((io, origin) =>
          io *> denied(container + ("SANCTITY_PUBLIC_ORIGIN" -> origin)))
        // No setting names an address: the mode alone selects it.
        ignored <- ServiceConfiguration.load(values ++ Map("SANCTITY_BIND_ADDRESS" -> "0.0.0.0", "SANCTITY_HTTP_BIND" -> "0.0.0.0"))
        _ = assertEquals(ignored.bindAddress, "127.0.0.1")
      yield ()
    }
  }

  test("a container listener binds every interface and still refuses every Host but the loopback origin") {
    val app: HttpApp[IO] = Kleisli(_ => IO.pure(Response[IO]().withEntity("served")))
    // One request over a real socket: the JDK client cannot send a Host that differs from its URI.
    def status(port: Int, host: String, origin: Option[String] = None): IO[String] = IO.blocking {
      val socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress, port)
      try
        socket.setSoTimeout(5000)
        val request = s"GET / HTTP/1.1\r\nHost: $host\r\n" + origin.fold("")(o => s"Origin: $o\r\n") + "Connection: close\r\n\r\n"
        socket.getOutputStream.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        socket.getOutputStream.flush()
        new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream, java.nio.charset.StandardCharsets.US_ASCII))
          .readLine().split(" ")(1)
      finally socket.close()
    }
    (for
      loopback <- ApiServer.server(0, app, 1.second).use(s => IO(s.address.getAddress))
      _ = assert(loopback.isLoopbackAddress, "the default listener must stay on loopback")
      // The guard names the port it serves, which is known only once the listener is bound.
      bound <- Deferred[IO, Int]
      guarded: HttpApp[IO] = Kleisli(req => bound.get.flatMap(p => ProtectedOrigin(app, s"127.0.0.1:$p", s"http://127.0.0.1:$p")(req)))
      _ <- ApiServer.server(0, guarded, 1.second, ServiceConfiguration.containerAddress).use { server =>
        val port = server.address.getPort
        for
          _ <- bound.complete(port)
          _ = assert(server.address.getAddress.isAnyLocalAddress, "container mode must listen on the container's interfaces")
          ok <- status(port, s"127.0.0.1:$port", Some(s"http://127.0.0.1:$port"))
          _ = assertEquals(ok, "200")
          // What a client on the container network or a LAN would send: the address or name it actually reached.
          address <- status(port, s"172.18.0.3:$port")
          _ = assertEquals(address, "400")
          name <- status(port, s"sanctity:$port")
          _ = assertEquals(name, "400")
          wildcard <- status(port, s"0.0.0.0:$port")
          _ = assertEquals(wildcard, "400")
          foreign <- status(port, s"127.0.0.1:$port", Some("http://attacker.invalid"))
          _ = assertEquals(foreign, "403")
        yield ()
      }
    yield ()).unsafeRunSync()
  }

  test("Host, Origin, duplicate fields and cross-site requests enforce the configured boundary") {
    val app: HttpApp[IO] = Kleisli(req => IO.pure(Response[IO]().withEntity(req.headers.toString)))
    val guard = ProtectedOrigin(app, "repository.example.org", "https://repository.example.org")
    def req(headers: (String, String)*): Request[IO] = Request[IO](uri = Uri.unsafeFromString("/v1/me"))
      .putHeaders(headers.map((k, v) => Header.Raw(CIString(k), v))*)
    val check = for
      host <- guard(req("Host" -> s"$canary.invalid", "X-Forwarded-Host" -> "repository.example.org"))
      _ = assertEquals(host.status, Status.BadRequest)
      origin <- guard(req("Host" -> "repository.example.org", "Origin" -> s"https://$canary.invalid"))
      _ = assertEquals(origin.status, Status.Forbidden)
      cross <- guard(req("Host" -> "repository.example.org", "Sec-Fetch-Site" -> "cross-site"))
      _ = assertEquals(cross.status, Status.Forbidden)
      duplicate <- guard(Request[IO]().withHeaders(org.http4s.Headers(
        Header.Raw(CIString("Host"), "repository.example.org"), Header.Raw(CIString("Host"), "repository.example.org"))))
      _ = assertEquals(duplicate.status, Status.BadRequest)
      ok <- guard(req("Host" -> "repository.example.org", "Origin" -> "https://repository.example.org",
        "Forwarded" -> canary, "X-Forwarded-For" -> canary, "X-Real-IP" -> canary))
      body <- ok.as[String]
      _ = assertEquals(ok.status, Status.Ok)
      _ = assert(!body.contains(canary))
      _ = assert(ok.headers.headers.exists(h => h.name == CIString("X-Frame-Options") && h.value == "DENY"))
    yield ()
    check.unsafeRunSync()
  }

  private def shutdownCase(stalled: Boolean): Unit =
    (for
      started <- Deferred[IO, Unit]
      finalized <- Deferred[IO, Unit]
      requests <- Ref.of[IO, Int](0)
      lateResult <- Deferred[IO, Unit]
      body = (Stream.emit[IO, Byte](65) ++ Stream.eval(started.complete(())).drain ++
        (if stalled then Stream.exec(IO.never) else Stream.sleep_[IO](200.millis)) ++ Stream.emit[IO, Byte](66))
        .onFinalize(finalized.complete(()).void)
      app: HttpApp[IO] = Kleisli(_ => requests.update(_ + 1).as(Response[IO]().withBodyStream(body)))
      _ <- Resource.fromAutoCloseable(IO(HttpClient.newHttpClient())).use { client =>
        for
          before <- IO.monotonic
          future <- ApiServer.server(0, app, 1.second).use { server =>
            val request = HttpRequest.newBuilder(URI.create(s"http://${server.addressIp4s}/")).build()
            for
              first <- IO(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
              _ <- started.get.timeout(5.seconds)
              _ <- (IO.sleep(100.millis) *> IO.fromCompletableFuture(IO(client.sendAsync(request,
                HttpResponse.BodyHandlers.ofString()))).attempt.timeoutTo(2.seconds, IO.pure(Left(new RuntimeException("timeout")))))
                .flatMap(_ => lateResult.complete(())).start
            yield first
          }
          elapsed <- IO.monotonic.map(_ - before)
          outcome <- IO.fromCompletableFuture(IO.pure(future)).attempt.timeout(5.seconds)
          _ <- finalized.get.timeout(2.seconds)
          _ <- lateResult.get.timeout(3.seconds)
          admitted <- requests.get
          _ = assertEquals(admitted, 1, "shutdown must stop admitting new application requests")
          _ = if stalled then
            assert(outcome.isLeft)
            assert(elapsed < 4.seconds)
          else assertEquals(outcome.toOption.get.body(), "AB")
        yield ()
      }
    yield ()).unsafeRunSync()

  test("graceful release drains a real active HTTP response") { shutdownCase(false) }
  test("shutdown bounds a stalled response and finalizes its stream") { shutdownCase(true) }
