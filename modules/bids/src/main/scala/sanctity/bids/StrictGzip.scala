package sanctity.bids

import cats.effect.Async
import fs2.{Chunk, Pull, Stream}
import java.util.zip.{CRC32, DataFormatException, Inflater}

/** Bounded RFC 1952 single-member decoding. No buffered filename/comment or trailer search.
  * Concatenated members and trailing bytes are explicitly outside this table profile.
  */
private[bids] object StrictGzip:
  private val BufferSize = 64 * 1024
  private val HeaderLimit = 16 * 1024

  def decode[F[_]: Async](source: Stream[F, Byte]): Stream[F, Byte] =
    Stream.bracket(Async[F].delay(new Decoder))(d => Async[F].delay(d.close())).flatMap { d =>
      def inflate: Pull[F, Byte, Unit] = Pull.eval(Async[F].delay(d.inflate())).flatMap {
        case Some(bytes) => Pull.output(Chunk.array(bytes)) >> inflate
        case None => Pull.done
      }
      def loop(in: Stream[F, Byte]): Pull[F, Byte, Unit] = in.pull.uncons.flatMap {
        case Some((chunk, rest)) => Pull.eval(Async[F].delay(d.input(chunk.toArray))) >> inflate >> loop(rest)
        case None => Pull.eval(Async[F].delay(d.finish()))
      }
      loop(source.chunkN(BufferSize).unchunks).stream
    }

  private final class Decoder:
    private val inflater = new Inflater(true)
    private val crc = new CRC32
    private val headerCrc = new CRC32
    private val fixed = new Array[Byte](10)
    private val trailer = new Array[Byte](8)
    private var headerSize = 0
    private var state = 0 // fixed, extra length, extra, name, comment, header CRC, data, trailer, end
    private var flags = 0
    private var extraLength = 0
    private var position = 0
    private var headerCheck = 0
    private var written = 0L
    private var supplied = Array.emptyByteArray

    private def invalid(message: String): Nothing = throw StreamingTsv.Rejected(StreamingTsv.Issue("Gzip", message))
    private def nextOptional(after: Int): Unit =
      position = 0
      if after < 1 && (flags & 4) != 0 then state = 1
      else if after < 3 && (flags & 8) != 0 then state = 3
      else if after < 4 && (flags & 16) != 0 then state = 4
      else if after < 5 && (flags & 2) != 0 then state = 5
      else state = 6

    private def header(b: Byte): Unit =
      headerSize += 1
      if headerSize > HeaderLimit then invalid("GZIP header exceeds 16 KiB")
      if state != 5 then headerCrc.update(b & 255)
      state match
        case 0 =>
          fixed(position) = b
          position += 1
          if position == 10 then
            if (fixed(0) & 255) != 31 || (fixed(1) & 255) != 139 || fixed(2) != 8 then invalid("invalid GZIP magic or compression method")
            flags = fixed(3) & 255
            if (flags & 224) != 0 then invalid("reserved GZIP flags are set")
            nextOptional(0)
        case 1 =>
          extraLength |= (b & 255) << (8 * position)
          position += 1
          if position == 2 then
            if extraLength + headerSize > HeaderLimit then invalid("GZIP extra field exceeds header budget")
            state = 2
            position = 0
            if extraLength == 0 then nextOptional(1)
        case 2 =>
          position += 1
          if position == extraLength then nextOptional(1)
        case 3 | 4 => if b == 0 then nextOptional(state)
        case 5 =>
          headerCheck |= (b & 255) << (8 * position)
          position += 1
          if position == 2 then
            if headerCheck != (headerCrc.getValue & 65535).toInt then invalid("GZIP header CRC mismatch")
            state = 6
        case _ => invalid("invalid GZIP header state")

    private def tail(bytes: Array[Byte], from: Int): Unit =
      var i = from
      while i < bytes.length do
        if state == 8 then invalid("trailing bytes or concatenated GZIP members are unsupported")
        trailer(position) = bytes(i)
        position += 1
        i += 1
        if position == 8 then
          def uint(offset: Int): Long = (0 until 4).foldLeft(0L)((n, j) => n | ((trailer(offset + j).toLong & 255) << (8 * j)))
          if uint(0) != crc.getValue then invalid("GZIP content CRC mismatch")
          if uint(4) != (written & 0xffffffffL) then invalid("GZIP decoded size mismatch")
          state = 8

    def input(bytes: Array[Byte]): Unit =
      var i = 0
      while i < bytes.length && state < 6 do
        header(bytes(i))
        i += 1
      if i < bytes.length then
        if state >= 7 then tail(bytes, i)
        else
          supplied = bytes
          inflater.setInput(bytes, i, bytes.length - i)

    def inflate(): Option[Array[Byte]] =
      if state != 6 || inflater.needsInput() then None
      else
        val output = new Array[Byte](BufferSize)
        val size = try inflater.inflate(output) catch case _: DataFormatException => invalid("invalid DEFLATE data")
        if size > 0 then
          crc.update(output, 0, size)
          written += size
        if inflater.finished() then
          state = 7
          position = 0
          tail(supplied, supplied.length - inflater.getRemaining)
          supplied = Array.emptyByteArray
        else if inflater.needsDictionary() then invalid("GZIP requires an unsupported dictionary")
        else if size == 0 && !inflater.needsInput() then invalid("GZIP inflater made no progress")
        if size == 0 then None else Some(if size == output.length then output else java.util.Arrays.copyOf(output, size))

    def finish(): Unit = if state != 8 then invalid("truncated GZIP header, data or trailer")
    def close(): Unit = inflater.end()
