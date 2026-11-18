package sanctity.app

import cats.data.Kleisli
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.id.{ClientID, Issuer}
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import io.circe.Json
import org.http4s.{Header, HttpApp, Method, Request, Response, Status}
import org.typelevel.ci.CIString
import sanctity.kernel.{AccessContext, Problem}
import sanctity.postgres.BrowserIdentities
import java.net.{HttpURLConnection, URI, URLEncoder}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64

/** OIDC authenticates the provider subject. Only its operator-established binding selects a principal. */
final class InstitutionalLogin private (
    val issuer: String, client: String, secret: Option[String], origin: String, identities: BrowserIdentities[IO],
    pending: Ref[IO,Map[String,InstitutionalLogin.Flow]]
):
  import InstitutionalLogin.*
  private val callback = origin + "/auth/callback"
  private val local = origin.startsWith("http://127.0.0.1:")
  private val cookiePrefix = if local then s"sanctity-local-${URI.create(origin).getPort}-" else "__Host-sanctity-"
  private val sessionCookie = cookiePrefix + "session"
  private val flowCookie = cookiePrefix + "flow"
  private def uri(value: String): URI =
    val u = URI.create(value)
    require(u.getHost != null && u.getUserInfo == null && u.getFragment == null && u.getQuery == null &&
      (u.getScheme == "https" || (local && issuer.startsWith("http://") && u.getScheme == "http" && Set("localhost","127.0.0.1")(u.getHost))))
    u
  private[app] def validateConfig(): Unit =
    uri(issuer)
    require(!issuer.endsWith("/") && client.trim.nonEmpty && client.length <= 1024 && !client.exists(_.isControl))

  private def request(url: URI, form: Option[String] = None, basic: Option[String] = None): Json =
    val con = url.toURL.openConnection().asInstanceOf[HttpURLConnection]
    try
      con.setInstanceFollowRedirects(false)
      con.setConnectTimeout(5000)
      con.setReadTimeout(5000)
      con.setRequestProperty("Accept","application/json")
      basic.foreach(v => con.setRequestProperty("Authorization", "Basic " + v))
      form.foreach { body =>
        con.setRequestMethod("POST")
        con.setDoOutput(true)
        con.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
        val out = con.getOutputStream
        try out.write(body.getBytes(US_ASCII)) finally out.close()
      }
      require(con.getResponseCode == 200)
      val in = con.getInputStream
      val bytes = try in.readNBytes(262145) finally in.close()
      require(bytes.length <= 262144)
      io.circe.jawn.parse(new String(bytes, UTF_8)).toOption.get
    finally con.disconnect()

  private def endpoints(): Endpoints =
    val doc = request(uri(issuer + "/.well-known/openid-configuration"))
    require(field(doc,"issuer") == issuer)
    Endpoints(uri(field(doc,"authorization_endpoint")),uri(field(doc,"token_endpoint")),uri(field(doc,"jwks_uri")))

  private def begin: IO[Response[IO]] = for
    endpoints <- IO.blocking(this.endpoints())
    clock <- IO.monotonic.map(_.toSeconds)
    flow <- IO.delay(Flow(random(),random(),random(),clock + 300, endpoints))
    state <- IO.delay(random())
    added <- pending.modify { entries =>
      val live = entries.filter(_._2.expires > clock)
      if live.size >= 1024 then live -> false else live.updated(state,flow) -> true
    }
  yield if !added then Response[IO](Status.ServiceUnavailable)
    else
      val challenge = b64(MessageDigest.getInstance("SHA-256").digest(flow.verifier.getBytes(US_ASCII)))
      val params = form("scope" -> "openid","response_type" -> "code","client_id" -> client,
        "redirect_uri" -> callback,"state" -> state,"nonce" -> flow.nonce,"code_challenge" -> challenge,
        "code_challenge_method" -> "S256")
      Response[IO](Status.SeeOther).putHeaders(header("Location",endpoints.authorize.toString + "?" + params),
        cookie(flowCookie,flow.browser,300))

  private def verify(code: String, flow: Flow): String =
    val basic = secret.map(s => Base64.getEncoder.encodeToString((encoded(client) + ":" + encoded(s)).getBytes(US_ASCII)))
    val body = form("grant_type" -> "authorization_code","code" -> code,"redirect_uri" -> callback,
      "code_verifier" -> flow.verifier) + (if basic.isEmpty then "&" + form("client_id" -> client) else "")
    val token = SignedJWT.parse(field(request(flow.endpoints.token,Some(body),basic),"id_token"))
    val validator = new IDTokenValidator(new Issuer(issuer),new ClientID(client),JWSAlgorithm.RS256,
      JWKSet.parse(request(flow.endpoints.keys).noSpaces))
    validator.setMaxClockSkew(30)
    val claims = validator.validate(token,new Nonce(flow.nonce))
    val azp = Option(claims.getAuthorizedParty).map(_.getValue)
    require(azp.forall(_ == client) && (claims.getAudience.size == 1 || azp.contains(client)))
    require(!claims.getIssueTime.toInstant.isAfter(Instant.now.plusSeconds(30)))
    claims.getSubject.getValue

  private def complete(req: Request[IO]): IO[Response[IO]] =
    def param(key: String) = req.uri.multiParams.get(key).collect { case Seq(v) if v.nonEmpty && v.length <= 8192 => v }
    val denied = page(Status.Unauthorized,"Sign-in could not be completed. Return to Sanctity to try again.")
    for
      clock <- IO.monotonic.map(_.toSeconds)
      flow <- pending.modify { all =>
        val matched = for
          state <- param("state")
          f <- all.get(state) if f.expires > clock && readCookie(req,flowCookie).contains(f.browser)
        yield state -> f
        matched.fold(all -> Option.empty[Flow])((key,f) => (all - key) -> Some(f))
      }
      session <- (flow, param("code")) match
        case (Some(f), Some(code)) if !req.uri.multiParams.contains("error") &&
            req.uri.multiParams.get("iss").forall(_ == Seq(issuer)) =>
          IO.blocking(verify(code,f)).flatMap(identities.issue(issuer,_)).map(_.toOption)
        case _ => IO.pure(None)
    yield session.fold(denied.putHeaders(cookie(flowCookie,"",0)))(token => page(Status.Ok,"Signed in.", refresh = true)
      .putHeaders(cookie(sessionCookie,token,28800),cookie(flowCookie,"",0)))

  private def sameOrigin(req: Request[IO]): Boolean =
    def one(n: String, v: String) = req.headers.headers.filter(_.name == CIString(n)).map(_.value) == List(v)
    one("Origin",origin) && one("X-Sanctity-CSRF","1")

  def authenticate(req: Request[IO]): IO[Either[Problem,AccessContext]] =
    readCookie(req,sessionCookie) match
      case Some(token) if req.method == Method.GET || req.method == Method.HEAD || sameOrigin(req) => identities.authenticate(issuer,token)
      case _ => IO.pure(Left(Problem.Denied))

  def wrap(app: HttpApp[IO]): HttpApp[IO] = Kleisli { req =>
    val response = (req.method,req.uri.path.renderString) match
      case (Method.GET,"/auth/config") => IO.pure(Response[IO]().withEntity("""{"enabled":true}""")
        .putHeaders(header("Content-Type","application/json")))
      case (Method.GET,"/auth/login") => begin
      case (Method.GET,"/auth/callback") => complete(req).handleError(_ =>
        page(Status.Unauthorized,"Sign-in could not be completed. Return to Sanctity to try again.").putHeaders(cookie(flowCookie,"",0)))
      case (Method.POST,"/auth/logout") if sameOrigin(req) =>
        readCookie(req,sessionCookie).traverse(identities.logout).map { outcome =>
          if outcome.exists(_.isLeft) then Response[IO](Status.ServiceUnavailable)
          else Response[IO](Status.NoContent).putHeaders(cookie(sessionCookie,"",0))
        }
      case (_, "/auth/logout") => IO.pure(Response[IO](Status.Forbidden))
      case _ => app(req)
    if req.uri.path.renderString.startsWith("/auth/") then response.handleError(_ =>
      page(Status.ServiceUnavailable,"Institutional sign-in is temporarily unavailable."))
      .map(_.putHeaders(header("Cache-Control","no-store")))
    else response
  }

object InstitutionalLogin:
  private val rng = new SecureRandom
  private def header(name: String, value: String) = Header.Raw(CIString(name),value)
  private def cookie(name: String, value: String, seconds: Int) =
    header("Set-Cookie",s"$name=$value; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=$seconds")
  private def readCookie(req: Request[IO], name: String): Option[String] =
    req.cookies.filter(_.name == name).map(_.content) match
      case List(v) => Some(v)
      case _ => None
  private def b64(bytes: Array[Byte]) = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  private def random() =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    b64(bytes)
  private def encoded(s: String) = URLEncoder.encode(s, UTF_8)
  private def form(pairs: (String,String)*) = pairs.map((k,v) => encoded(k) + "=" + encoded(v)).mkString("&")
  private def field(j: Json,key: String) = j.hcursor.get[String](key).toOption.get
  private final case class Endpoints(authorize: URI,token: URI,keys: URI)
  private final case class Flow(browser: String,nonce: String,verifier: String,expires: Long,endpoints: Endpoints)
  private def page(status: Status,message: String,refresh: Boolean = false): Response[IO] =
    val forward = if refresh then """<meta http-equiv="refresh" content="0; url=/">""" else ""
    Response[IO](status).withEntity(s"""<!doctype html><html lang="en"><meta charset="utf-8">$forward<title>Sanctity sign-in</title><p>$message</p><a href="/">Return to Sanctity</a></html>""")
      .putHeaders(header("Content-Type","text/html; charset=utf-8"),header("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
  def configured(env: Map[String,String],origin: String,identities: BrowserIdentities[IO]): IO[Option[InstitutionalLogin]] =
    val secretValue = env.get("SANCTITY_OIDC_CLIENT_SECRET")
    val secretFile = env.get("SANCTITY_OIDC_CLIENT_SECRET_FILE")
    (env.get("SANCTITY_OIDC_ISSUER"),env.get("SANCTITY_OIDC_CLIENT_ID")) match
      case (None,None) if secretValue.isEmpty && secretFile.isEmpty => IO.pure(None)
      case (Some(issuer),Some(client)) =>
        for
          secret <- IO.blocking {
            require(secretValue.isEmpty || secretFile.isEmpty)
            val value = secretValue.orElse(secretFile.map(ServiceConfiguration.protectedSecretFile(_,"OIDC secret file")))
            require(value.forall(s => s.nonEmpty && s.length <= 4096 && !s.exists(_.isControl)))
            value
          }.adaptError { case _ => ConfigurationFailure("OIDC secret source") }
          pending <- Ref.of[IO,Map[String,Flow]](Map.empty)
          login = new InstitutionalLogin(issuer,client,secret,origin,identities,pending)
          _ <- IO(login.validateConfig()).adaptError { case _ => ConfigurationFailure("invalid OIDC configuration") }
        yield Some(login)
      case _ => IO.raiseError(ConfigurationFailure("configure both the OIDC issuer and client ID"))
