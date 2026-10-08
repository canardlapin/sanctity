package sanctity.app

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.FunSuite
import org.http4s.{Request, Response, Status}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import sanctity.application.*
import sanctity.kernel.*
import sanctity.objects.DirectoryExport
import sanctity.xnat.*
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.*

final class XnatSuite extends FunSuite:
  private def path(s: String): RelPath = RelPath.from(s).toOption.get
  private val scope = XnatScope("fixture-installation", path("data/experiments/E1/resources/BIDS/files"), "P1")
  private def md5(bytes: Array[Byte]): String = MessageDigest.getInstance("MD5").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  private def listing(files: Map[String, Array[Byte]]): String = Json.obj("ResultSet" -> Json.obj("Result" -> Json.arr(
    files.toVector.sortBy(_._1).map { (name, bytes) => Json.obj(
      "Name" -> Json.fromString(name), "URI" -> Json.fromString("/" + scope.listing.value + "/" + name),
      "Size" -> Json.fromString(bytes.length.toString), "digest" -> Json.fromString(md5(bytes))) }*
  ))).noSpaces
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def fixture(use: (RepositorySystem, Path, Map[String, Array[Byte]]) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-xnat-test-")))(TestFiles.remove).use { root =>
      for
        _ <- SyntheticFixture.create(root.resolve("source"))
        system <- ReferenceSystem.create(root.resolve("storage"))
        files <- IO.blocking {
          val walk = Files.walk(root.resolve("source"))
          try walk.iterator().asScala.filter(Files.isRegularFile(_)).map(p =>
            root.resolve("source").relativize(p).toString -> Files.readAllBytes(p)).toMap
          finally walk.close()
        }
        _ <- use(system, root, files)
      yield ()
    }.unsafeRunSync()

  private def client(response: Request[IO] => IO[Response[IO]]): Client[IO] = Client.fromHttpApp(Kleisli(response))
  private def ok(bytes: Array[Byte]): Response[IO] = Response[IO](Status.Ok).withBodyStream(Stream.emits(bytes).covary[IO])
  private val connection = XnatConnection.from("https://fixture.invalid", "user", "not-a-real-secret").toOption.get

  test("API and declared export resource produce the same accepted bytes through the native lifecycle") {
    fixture { (s, root, files) =>
      val api = new XnatApi[IO](connection, client { request =>
        val key = request.uri.path.renderString.stripPrefix("/" + scope.listing.value + "/")
        IO.pure(if request.uri.path.renderString == "/" + scope.listing.value then ok(listing(files).getBytes("UTF-8"))
          else files.get(key).fold(Response[IO](Status.NotFound))(ok))
      })
      for
        inventory <- checked(api.inventory(scope))
        source <- api.source(inventory, s.store)
        preview <- checked(s.repository.importNative(s.context, s.dataset.id, source))
        first <- checked(s.repository.accept(s.context, preview.candidate.id, IdempotencyKey.from("api").toOption.get))
        _ = assert(preview.candidate.activity.evidence.nonEmpty)
        zip = root.resolve("resource.zip")
        _ <- IO.blocking {
          val output = new ZipOutputStream(Files.newOutputStream(zip))
          try files.toVector.sortBy(_._1).foreach { (name, bytes) =>
            output.putNextEntry(new ZipEntry(name)); output.write(bytes); output.closeEntry()
          }
          finally output.close()
        }
        second <- s.repository.create(s.context, s.dataset.custodian)
        zipPreview <- XnatExport.open[IO](zip, inventory, s.store).use(exported =>
          checked(s.repository.importNative(s.context, second.id, exported)))
        secondReceipt <- checked(s.repository.accept(s.context, zipPreview.candidate.id, IdempotencyKey.from("export").toOption.get))
        a <- checked(s.repository.release(s.context, first.release))
        b <- checked(s.repository.release(s.context, secondReceipt.release))
        _ = assertEquals(a.manifest.files.map(f => f.path -> f.blob).toMap, b.manifest.files.map(f => f.path -> f.blob).toMap)
        _ <- checked(s.repository.exportFull(s.context, first.release, new DirectoryExport[IO](root.resolve("materialized"))))
      yield ()
    }
  }

  test("same-size corruption is detected, forbidden access is distinct from an empty collection, and redirect URIs are rejected") {
    fixture { (s, _, _) =>
      val bytes = "abc".getBytes("UTF-8")
      val original = listing(Map("file.dcm" -> bytes))
      val inventory = XnatInventory.parse(scope, original).toOption.get
      val corrupt = new XnatApi[IO](connection, client { request =>
        IO.pure(ok(if request.uri.path.renderString.endsWith("/files") then original.getBytes("UTF-8") else "abd".getBytes("UTF-8")))
      })
      for
        source <- corrupt.source(inventory, s.store)
        failed <- source.read(SourceEntry.File(path("file.dcm"), 3, inventory.files.head.observation)).compile.drain.attempt
        _ = assert(failed.left.toOption.exists { case Failure(_: Problem.Integrity) => true; case _ => false })
        denied <- new XnatApi[IO](connection, client(_ => IO.pure(Response[IO](Status.Forbidden)))).inventory(scope)
        _ = assertEquals(denied, Left(Problem.Denied))
        empty <- checked(new XnatApi[IO](connection, client(_ => IO.pure(ok(listing(Map.empty).getBytes("UTF-8"))))).inventory(scope))
        _ = assertEquals(empty.files, Vector.empty)
        malicious = original.replace("/data/experiments", "https://outside.invalid/data/experiments")
        _ = assert(XnatInventory.parse(scope, malicious).isLeft)
        traversal = original.replace("file.dcm", "../file.dcm")
        _ = assert(XnatInventory.parse(scope, traversal).isLeft)
      yield ()
    }
  }

  test("documented experiment pagination detects servers that repeat a page") {
    def page(offset: Int): String =
      Json.obj("ResultSet" -> Json.obj("Result" -> Json.arr((offset until math.min(offset + 2, 5)).map(i =>
        Json.obj("ID" -> Json.fromString(s"E$i")))*))).noSpaces
    val api = new XnatApi[IO](connection, client(r => IO.pure(ok(page(r.uri.query.params("offset").toInt).getBytes("UTF-8")))))
    val repeats = new XnatApi[IO](connection, client(_ => IO.pure(ok(page(0).getBytes("UTF-8")))))
    (for
      rows <- api.experiments("P1", 2).compile.toVector
      _ = assertEquals(rows.size, 5)
      failed <- repeats.experiments("P1", 2).compile.drain.attempt
      _ = assert(failed.isLeft)
    yield ()).unsafeRunSync()
  }

  test("real HTTP client streams authenticated resource content from a loopback fixture") {
    fixture { (s, _, files) =>
      val server = Resource.make(IO.blocking {
        val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", exchange => {
          val requested = exchange.getRequestURI.getPath
          val authorized = Option(exchange.getRequestHeaders.getFirst("Authorization")).contains("Basic dXNlcjpwYXNz")
          val bytes = if requested == "/" + scope.listing.value then Some(listing(files).getBytes("UTF-8"))
            else files.get(requested.stripPrefix("/" + scope.listing.value + "/"))
          val response = if !authorized then 401 else if bytes.isEmpty then 404 else 200
          exchange.sendResponseHeaders(response, 0)
          val output = exchange.getResponseBody
          try if response == 200 then output.write(bytes.get)
          finally { output.close(); exchange.close() }
        })
        server.start()
        server
      })(server => IO.blocking(server.stop(0)))
      server.use { server =>
        EmberClientBuilder.default[IO].build.use { http =>
          for
            conn <- Checked.lift[IO, XnatConnection](XnatConnection.from(
              s"http://127.0.0.1:${server.getAddress.getPort}", "user", "pass", allowLoopbackHttp = true))
            api = new XnatApi[IO](conn, http)
            inventory <- checked(api.inventory(scope))
            source <- api.source(inventory, s.store)
            prepared <- checked(s.repository.importNative(s.context, s.dataset.id, source))
            _ = assert(prepared.candidate.assessment.admissible)
          yield ()
        }
      }
    }
  }
