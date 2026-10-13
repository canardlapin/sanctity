package sanctity.http

import cats.data.Kleisli
import cats.effect.Sync
import org.http4s.*
import org.typelevel.ci.CIString

/** Fixed browser console assets. Only named classpath files are served; nothing is read from request paths. */
final class ConsoleAssets[F[_]](using F: Sync[F]):
  private val types = Map(
    "index.html" -> "text/html; charset=utf-8",
    "app.css" -> "text/css; charset=utf-8",
    "app.js" -> "text/javascript; charset=utf-8",
    "demo.js" -> "text/javascript; charset=utf-8")
  private val bytes: Map[String, Array[Byte]] = types.keys.flatMap { name =>
    Option(getClass.getResourceAsStream("/sanctity/ui/" + name)).map { stream =>
      try name -> stream.readAllBytes() finally stream.close()
    }
  }.toMap

  // The page makes same-origin API calls only; downloads use blob URLs.
  private val policy = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; " +
    "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

  private def asset(path: String): Option[String] = path match
    case "/" | "" => Some("index.html")
    case other => Some(other.stripPrefix("/")).filter(n => n != "index.html" && types.contains(n))

  val app: HttpApp[F] = Kleisli { req =>
    asset(req.uri.path.renderString).flatMap(n => bytes.get(n).map(n -> _)) match
      case Some((name, body)) if req.method == Method.GET || req.method == Method.HEAD =>
        F.pure(Response[F](Status.Ok).withEntity(body).putHeaders(
          Header.Raw(CIString("Content-Type"), types(name)),
          Header.Raw(CIString("Content-Security-Policy"), policy),
          Header.Raw(CIString("Cache-Control"), "no-cache"),
          Header.Raw(CIString("Referrer-Policy"), "no-referrer"),
          Header.Raw(CIString("X-Frame-Options"), "DENY"),
          Header.Raw(CIString("X-Content-Type-Options"), "nosniff")))
      case Some(_) => F.pure(Response[F](Status.MethodNotAllowed))
      case None => F.pure(Response[F](Status.NotFound))
  }

object ConsoleAssets:
  /** Routes `/v1/...` to the API and everything else to the fixed console assets. */
  def withApi[F[_]: Sync](api: HttpApp[F]): HttpApp[F] =
    val assets = ConsoleAssets[F]().app
    Kleisli(req => if req.uri.path.segments.headOption.exists(_.encoded == "v1") then api(req) else assets(req))
