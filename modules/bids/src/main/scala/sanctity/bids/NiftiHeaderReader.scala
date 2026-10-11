package sanctity.bids

import bids4s.{DwiIssue, DwiIssueCode, NiftiHeaderFacts}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.BlobStore
import sanctity.kernel.Entry
import java.io.{ByteArrayInputStream, IOException}
import java.util.zip.GZIPInputStream

/** Bounded prefix inspection; ordinary acceptance verifies complete managed blob identity. */
private[bids] object NiftiHeaderReader:
  val Version: String = "sanctity-prefix-v1"
  val CompressedPrefixLimit: Int = 64 * 1024

private[bids] final class NiftiHeaderReader[F[_]](store: BlobStore[F])(using F: Async[F]):
  def read(file: Entry.File): F[Either[DwiIssue, NiftiHeaderFacts]] =
    val gzip = file.path.fileName.endsWith(".nii.gz")
    val limit = if gzip then NiftiHeaderReader.CompressedPrefixLimit else NiftiHeaderFacts.MaxHeaderBytes
    store.read(file.blob).take(limit.toLong).compile.to(Array).flatMap { prefix =>
      F.blocking {
        val decoded = if !gzip then Right(prefix) else gzipHeader(prefix, file.blob.size > prefix.length)
        decoded.flatMap(NiftiHeaderFacts.parse).flatMap { header =>
          if !gzip && file.blob.size < header.minimumFileBytes then
            Left(DwiIssue(DwiIssueCode.Header, "image", "uncompressed file is shorter than the header's declared array"))
          else Right(header)
        }
      }
    }

  private final class PrefixLimit extends IOException
  private def gzipHeader(prefix: Array[Byte], limited: Boolean): Either[DwiIssue, Array[Byte]] =
    val input = new ByteArrayInputStream(prefix):
      override def read(): Int =
        if available() == 0 && limited then throw new PrefixLimit else super.read()
      override def read(b: Array[Byte], offset: Int, length: Int): Int =
        if length > 0 && available() == 0 && limited then throw new PrefixLimit else super.read(b, offset, length)
    try
      val stream = new GZIPInputStream(input, 512)
      try
        val initial = stream.readNBytes(4)
        NiftiHeaderFacts.headerSize(initial).map(size => initial ++ stream.readNBytes(size - initial.length))
      finally stream.close()
    catch
      case _: PrefixLimit => Left(DwiIssue(DwiIssueCode.LimitExceeded, "image", "gzip header requires more than 64 KiB compressed input"))
      case _: IOException => Left(DwiIssue(DwiIssueCode.Header, "image", "invalid or truncated gzip header prefix"))
