package sanctity.xnat

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{Checked, Failure}
import sanctity.kernel.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class XnatScope(installation: String, listing: RelPath, projectContext: String)
final case class XnatFile(path: RelPath, uri: String, size: Long, md5: Option[String]):
  def observation: String = Digest.framed("sanctity.xnat-observation.v1",
    Vector(path.value, uri, size.toString, md5.getOrElse(""))).value

final class XnatInventory private(val scope: XnatScope, val files: Vector[XnatFile]):
  val id: Digest = Digest.framed("sanctity.xnat-inventory.v1", Vector(scope.installation, scope.listing.value, scope.projectContext) ++
    files.flatMap(f => Vector(f.path.value, f.uri, f.size.toString, f.md5.getOrElse(""))))
  def json: Json = Json.obj("version" -> Json.fromInt(1), "installation" -> Json.fromString(scope.installation),
    "listing" -> Json.fromString(scope.listing.value), "projectContext" -> Json.fromString(scope.projectContext),
    "inventory" -> Json.fromString(id.value), "scope" -> Json.fromString("explicit resource collection; visible files only"),
    "ResultSet" -> Json.obj("Result" -> Json.arr(files.map(f => Json.obj("Name" -> Json.fromString(f.path.value),
      "URI" -> Json.fromString(f.uri), "Size" -> Json.fromLong(f.size), "digest" -> Json.fromString(f.md5.getOrElse(""))))*)))

object XnatInventory:
  private val parser = new JawnParser(maxValueSize = Some(8 * 1024 * 1024), allowDuplicateKeys = false)

  def validateScope(scope: XnatScope): Either[Problem, Unit] =
    Either.cond(scope.installation.nonEmpty && scope.installation.length <= 200 &&
      scope.listing.value.startsWith("data/") && scope.listing.value.endsWith("/files") &&
      !scope.listing.value.exists(c => c == '%' || c == '?' || c == '#' || c.isWhitespace), (),
      Problem.Unsupported("XNAT scope must name a literal /data/.../files collection"))

  def parse(scope: XnatScope, body: String): Either[Problem, XnatInventory] =
    for
      _ <- validateScope(scope)
      json <- parser.parse(body).leftMap(_ => Problem.Integrity("invalid XNAT listing JSON"))
      records <- json.hcursor.downField("ResultSet").get[Vector[Json]]("Result").leftMap(_ => Problem.Integrity("missing XNAT ResultSet"))
      _ <- Either.cond(records.size <= 10000, (), Problem.LimitExceeded("XNAT collection exceeds 10000 files"))
      total = json.hcursor.downField("ResultSet").downField("totalRecords").focus.flatMap(j => j.asString.orElse(j.asNumber.map(_.toString)))
      _ <- Either.cond(total.forall(_.toLongOption.contains(records.size.toLong)), (), Problem.Integrity("incomplete XNAT file listing"))
      files <- records.traverse { row =>
        def text(name: String): Either[Problem, String] =
          row.hcursor.get[String](name).leftMap(_ => Problem.Integrity(s"missing XNAT $name"))
        for
          name <- text("Name")
          path <- RelPath.from(name)
          uri <- text("URI")
          parsed <- scala.util.Try(new URI(uri)).toEither.leftMap(_ => Problem.Integrity("invalid XNAT file URI"))
          _ <- Either.cond(!parsed.isAbsolute && parsed.getRawAuthority == null && parsed.getQuery == null &&
            parsed.getFragment == null && Option(parsed.getPath).exists(_.startsWith("/data/")) &&
            !parsed.getPath.contains("%") && RelPath.from(parsed.getPath.stripPrefix("/")).isRight,
            (), Problem.Unsupported("XNAT file URI must stay inside the installation data API"))
          sizeText = row.hcursor.downField("Size").focus.flatMap(j => j.asString.orElse(j.asNumber.map(_.toString)))
          size <- sizeText.flatMap(_.toLongOption).filter(_ >= 0).toRight(Problem.Integrity("invalid XNAT file size"))
          digest = row.hcursor.get[String]("digest").toOption.filter(_.nonEmpty).map(_.toLowerCase(java.util.Locale.ROOT))
          _ <- Either.cond(digest.forall(_.matches("[0-9a-f]{32}")), (), Problem.Unsupported("unrecognized XNAT checksum format"))
        yield XnatFile(path, uri, size, digest)
      }
      _ <- Either.cond(files.map(_.path.collisionKey).distinct.size == files.size && files.map(_.uri).distinct.size == files.size,
        (), Problem.Integrity("duplicate XNAT file names or URIs"))
    yield new XnatInventory(scope, files.sortBy(_.path.value))

  def verify[F[_]: Async](file: XnatFile, bytes: Stream[F, Byte]): Stream[F, Byte] =
    Stream.eval(Async[F].delay(MessageDigest.getInstance("MD5"))).flatMap { digest =>
      bytes.chunks.evalMapAccumulate(0L) { (size, chunk) =>
        Async[F].delay {
          val next = size + chunk.size
          if next > file.size then throw Failure(Problem.Integrity("XNAT file exceeds inventoried size"))
          digest.update(chunk.toArray)
          next -> chunk
        }
      }.pull.uncons1.flatMap {
        case None =>
          fs2.Pull.eval(Checked.lift[F, Unit](Either.cond(file.size == 0 && file.md5.forall(_ == hex(digest.digest())),
            (), Problem.Integrity("empty XNAT response differs from inventory"))))
        case Some((first, tail)) =>
          def loop(last: Long, stream: Stream[F, (Long, fs2.Chunk[Byte])]): fs2.Pull[F, Byte, Unit] =
            stream.pull.uncons1.flatMap {
              case Some(((count, chunk), rest)) => fs2.Pull.output(chunk) >> loop(count, rest)
              case None => fs2.Pull.eval(Checked.lift[F, Unit](Either.cond(last == file.size && file.md5.forall(_ == hex(digest.digest())),
                (), Problem.Integrity("XNAT file differs from its inventory checksum or size"))))
            }
          fs2.Pull.output(first._2) >> loop(first._1, tail)
      }.stream
    }

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
  def bytes(inventory: XnatInventory): Array[Byte] = (inventory.json.spaces2 + "\n").getBytes(StandardCharsets.UTF_8)
