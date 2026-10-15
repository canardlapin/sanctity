package sanctity.app

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.{Header, HttpApp, Response, Status}
import org.typelevel.ci.CIString

/** Host and Origin are checked against explicit configuration, never forwarded claims. */
object ProtectedOrigin:
  def apply(app: HttpApp[IO], authority: String, origin: String): HttpApp[IO] = Kleisli { request =>
    def values(name: String): List[String] = request.headers.headers.filter(_.name == CIString(name)).map(_.value).toList
    val hostAllowed = values("Host").map(_.toLowerCase(java.util.Locale.ROOT)) == List(authority)
    val originAllowed = values("Origin") match
      case Nil => true // Bearer-token clients need not be browsers.
      case List(value) => value == origin
      case _ => false
    val response =
      if !hostAllowed || request.uri.authority.nonEmpty then IO.pure(Response[IO](Status.BadRequest))
      else if !originAllowed || values("Sec-Fetch-Site").exists(_ == "cross-site") then IO.pure(Response[IO](Status.Forbidden))
      else app(request.withHeaders(request.headers.headers.filterNot { h =>
        val name = h.name.toString.toLowerCase(java.util.Locale.ROOT)
        name == "forwarded" || name.startsWith("x-forwarded-") || name == "x-real-ip"
      }))
    response.map(_.putHeaders(
      Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
      Header.Raw(CIString("X-Frame-Options"), "DENY"),
      Header.Raw(CIString("Referrer-Policy"), "no-referrer")
    ))
  }
