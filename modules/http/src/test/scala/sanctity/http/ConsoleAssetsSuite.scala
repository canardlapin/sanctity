package sanctity.http

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

class ConsoleAssetsSuite extends munit.FunSuite:
  private val api: HttpApp[IO] = Kleisli(_ => IO.pure(Response[IO](Status.Unauthorized)))
  private val app = ConsoleAssets.withApi[IO](api)
  private def run(method: Method, uri: Uri): Response[IO] = app(Request[IO](method, uri)).unsafeRunSync()
  private def header(r: Response[IO], name: String) = r.headers.get(CIString(name)).map(_.head.value)

  test("serves the console shell with a restrictive policy") {
    val r = run(Method.GET, uri"/")
    assertEquals(r.status, Status.Ok)
    assertEquals(header(r, "Content-Type"), Some("text/html; charset=utf-8"))
    assert(header(r, "Content-Security-Policy").exists(_.contains("script-src 'self'")))
    assert(r.as[String].unsafeRunSync().contains("""<script src="app.js"></script>"""))
  }

  test("serves only the named assets") {
    assertEquals(run(Method.GET, uri"/app.js").status, Status.Ok)
    assertEquals(run(Method.GET, uri"/app.css").status, Status.Ok)
    assertEquals(run(Method.GET, uri"/index.html").status, Status.NotFound)
    assertEquals(run(Method.GET, uri"/../build.sbt").status, Status.NotFound)
    assertEquals(run(Method.GET, uri"/sanctity/ui/app.js").status, Status.NotFound)
    assertEquals(run(Method.POST, uri"/app.js").status, Status.MethodNotAllowed)
  }

  test("leaves the versioned API to its own authenticated adapter") {
    assertEquals(run(Method.GET, uri"/v1/me").status, Status.Unauthorized)
    assertEquals(run(Method.GET, uri"/v1").status, Status.Unauthorized)
  }
