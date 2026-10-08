package sanctity.xnat

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.jawn.JawnParser
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import sanctity.application.*
import sanctity.kernel.*
import java.nio.charset.StandardCharsets
import java.util.Base64

final class XnatConnection private(val base: Uri, private[xnat] val authorization: String):
  override def toString: String = s"XnatConnection(${base.renderString},credentials=<redacted>)"
object XnatConnection:
  def from(base: String, user: String, password: String, allowLoopbackHttp: Boolean = false): Either[Problem, XnatConnection] =
    for
      uri <- Uri.fromString(base).leftMap(_ => Problem.Unsupported("invalid XNAT installation URL"))
      _ <- Either.cond(uri.userInfo.isEmpty && uri.query.isEmpty && uri.fragment.isEmpty &&
        uri.host.nonEmpty && !user.contains(':') && user.nonEmpty &&
        (uri.scheme.exists(_.value == "https") || (allowLoopbackHttp && uri.scheme.exists(_.value == "http") &&
          uri.host.exists(h => Set("127.0.0.1", "localhost", "::1").contains(h.value)))), (),
        Problem.Unsupported("XNAT requires HTTPS and credentials outside the URL; loopback HTTP is test-only"))
    yield new XnatConnection(uri, "Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8)))

final class XnatApi[F[_]: Async](connection: XnatConnection, client: Client[F]):
  private val F = Async[F]
  private val parser = new JawnParser(maxValueSize = Some(8 * 1024 * 1024), allowDuplicateKeys = false)
  private def url(path: String): Uri =
    Uri.unsafeFromString(connection.base.renderString.stripSuffix("/") + "/" + path.stripPrefix("/"))
  private def request(uri: Uri): Request[F] = Request[F](Method.GET, uri).putHeaders(
    Header.Raw(CIString("Authorization"), connection.authorization), Header.Raw(CIString("Accept-Encoding"), "identity"))
  private def body(uri: Uri): F[String] =
    client.run(request(uri)).use { response =>
      if response.status == Status.Unauthorized || response.status == Status.Forbidden then F.raiseError(Failure(Problem.Denied))
      else if response.status != Status.Ok then F.raiseError(Failure(Problem.Operational(s"XNAT HTTP ${response.status.code}")))
      else response.body.take(8L * 1024 * 1024 + 1).compile.to(Array).flatMap { bytes =>
        Checked.lift[F, String](Either.cond(bytes.length <= 8 * 1024 * 1024, new String(bytes, StandardCharsets.UTF_8),
          Problem.LimitExceeded("XNAT listing exceeds 8 MiB")))
      }
    }

  def inventory(scope: XnatScope): F[Either[Problem, XnatInventory]] = Checked.attempt {
    Checked.lift[F, Unit](XnatInventory.validateScope(scope)) *> body(url(scope.listing.value).withQueryParam("format", "json")).flatMap(text => Checked.lift[F, XnatInventory](XnatInventory.parse(scope, text)))
  }

  /** Pagination is used on documented experiment tables; resource file listings are bounded whole collections. */
  def experiments(project: String, pageSize: Int = 100): Stream[F, Json] =
    if !project.matches("[A-Za-z0-9_-]+") || pageSize < 1 || pageSize > 1000 then
      Stream.raiseError[F](Failure(Problem.Unsupported("invalid project or page size")))
    else
      def page(offset: Int, seen: Set[String]): Stream[F, Json] =
        if offset > 100000 then Stream.raiseError[F](Failure(Problem.LimitExceeded("experiment discovery exceeds 100000 rows")))
        else Stream.eval(body(url(s"data/projects/$project/experiments").withQueryParam("format", "json")
          .withQueryParam("limit", pageSize).withQueryParam("offset", offset))).flatMap { text =>
          val parsed = for
            json <- parser.parse(text).leftMap(_ => Problem.Integrity("invalid experiment page"))
            rows <- json.hcursor.downField("ResultSet").get[Vector[Json]]("Result").leftMap(_ => Problem.Integrity("missing experiment page"))
            ids <- rows.traverse(_.hcursor.get[String]("ID").leftMap(_ => Problem.Integrity("experiment has no native ID")))
            _ <- Either.cond(ids.distinct.size == ids.size && !ids.exists(seen) && rows.size <= pageSize, (),
              Problem.Integrity("repeated or oversized experiment page; source may have changed"))
          yield rows -> ids
          Stream.eval(Checked.lift[F, (Vector[Json], Vector[String])](parsed)).flatMap { (rows, ids) =>
            Stream.emits(rows).covary[F] ++ (if rows.size < pageSize then Stream.empty else page(offset + pageSize, seen ++ ids))
          }
        }
      page(0, Set.empty)

  def source(observed: XnatInventory, store: BlobStore[F]): F[NativeSource[F]] =
    store.put(Stream.emits(XnatInventory.bytes(observed)).covary[F]).map { receipt =>
      new NativeSource[F]:
        val label: String = s"xnat-api:${observed.scope.installation}:${observed.id.value}"
        override val evidence: Option[BlobRef] = Some(receipt)
        def entries: Stream[F, SourceEntry] =
          Stream.eval(inventory(observed.scope).flatMap(Checked.lift[F, XnatInventory])).flatMap { current =>
            if current.id != observed.id then Stream.raiseError[F](Failure(Problem.Integrity("XNAT inventory changed")))
            else
              val directories = observed.files.flatMap(f => Iterator.iterate(f.path.parent)(_.flatMap(_.parent)).takeWhile(_.nonEmpty).flatten.toVector).distinct
              Stream.emits(directories.map(SourceEntry.Directory.apply) ++
                observed.files.map(f => SourceEntry.File(f.path, f.size, f.observation))).covary[F]
          }
        def read(file: SourceEntry.File): Stream[F, Byte] =
          observed.files.find(f => f.path == file.path && f.size == file.size && f.observation == file.observation) match
            case None => Stream.raiseError[F](Failure(Problem.Denied))
            case Some(expected) =>
              Stream.resource(client.run(request(url(expected.uri)))).flatMap { response =>
                if response.status == Status.Unauthorized || response.status == Status.Forbidden then Stream.raiseError[F](Failure(Problem.Denied))
                else if response.status != Status.Ok then Stream.raiseError[F](Failure(Problem.Operational(s"XNAT file HTTP ${response.status.code}")))
                else XnatInventory.verify(expected, response.body)
              }
    }
