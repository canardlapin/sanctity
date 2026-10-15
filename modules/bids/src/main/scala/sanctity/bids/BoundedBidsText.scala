package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Small scientific documents only. Storage failure and corrupt bytes remain operational errors. */
private[bids] object BoundedBidsText:
  val Limit: Long = 4L * 1024 * 1024
  val TotalLimit: Long = 32L * 1024 * 1024

  def read[F[_]: Async](store: BlobStore[F], file: Entry.File): F[Either[String, String]] =
    if file.blob.size > Limit then Async[F].pure(Left("table exceeds 4 MiB"))
    else store.read(file.blob).take(Limit + 1).compile.to(Array).flatMap { bytes =>
      Checked.lift[F, Unit](Either.cond(bytes.length.toLong == file.blob.size && Digest.bytes(bytes) == file.blob.digest,
        (), Problem.Integrity("scientific document bytes differ from manifest: " + file.path.value))).as {
        Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
          .toEither.leftMap(_ => "table must be UTF-8")
      }
    }
