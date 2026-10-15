package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.compression.Compression
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.*
import scala.util.Try

/** Incremental TSV content and column projection. Memory is bounded by one row and
  * one input chunk, independently of recording length. This is a table mechanism,
  * not a physiological clock or eye-tracking interpretation.
  */
object StreamingTsv:
  val Version = "sanctity-streaming-tsv-v1"
  private val ChunkSize = 64 * 1024

  final case class Limits(encodedBytes: Long = 2L * 1024 * 1024 * 1024,
      decodedBytes: Long = 2L * 1024 * 1024 * 1024, rows: Long = 100000000,
      columns: Int = 256, fieldBytes: Int = 64 * 1024, rowBytes: Int = 1024 * 1024,
      diagnostics: Int = 64, duration: FiniteDuration = 5.minutes)

  /** Column order comes from the sidecar for headerless tables. Numeric checks
    * preserve lexical values and accept n/a unless requiredValues names a column.
    */
  final case class Schema(columns: Vector[String], header: Boolean = false,
      numeric: Set[String] = Set.empty, nonnegative: Set[String] = Set.empty,
      requiredValues: Set[String] = Set.empty)
  final case class Issue(code: String, message: String, row: Long = 0, column: Int = 0)
  final case class Report(rows: Long, decodedBytes: Long, invalidValues: Long, issues: Vector[Issue])
  final case class Rejected(issue: Issue) extends RuntimeException(issue.message)

  private def valid(schema: Schema, limits: Limits, selected: Set[String]): Either[Issue, Unit] =
    Either.cond(limits.encodedBytes > 0 && limits.decodedBytes > 0 && limits.rows > 0 &&
      limits.columns > 0 && limits.columns <= 256 && limits.fieldBytes > 0 && limits.fieldBytes <= 64 * 1024 &&
      limits.rowBytes > 0 && limits.rowBytes <= 1024 * 1024 && limits.diagnostics > 0 && limits.diagnostics <= 64 &&
      limits.duration > Duration.Zero && schema.columns.nonEmpty && schema.columns.size <= limits.columns &&
      schema.columns.distinct.size == schema.columns.size && schema.columns.forall(c => c.nonEmpty &&
        c.getBytes(StandardCharsets.UTF_8).length <= limits.fieldBytes && !c.exists(_.isControl) && c != "n/a") &&
      (schema.numeric ++ schema.nonnegative ++ schema.requiredValues ++ selected).subsetOf(schema.columns.toSet) &&
      schema.nonnegative.subsetOf(schema.numeric) && selected.nonEmpty,
      (), Issue("Schema", "invalid table schema, column mask or resource limits"))

  def assess[F[_]: Async](input: Stream[F, Byte], gzip: Boolean, schema: Schema,
      limits: Limits = Limits()): F[Either[Issue, Report]] =
    valid(schema, limits, schema.columns.toSet) match
      case Left(issue) => Async[F].pure(Left(issue))
      case Right(_) =>
        Async[F].delay(new Decoder(schema, limits, Set.empty)).flatMap { decoder =>
          consume(input, gzip, decoder, limits).compile.drain.flatMap(_ => Async[F].delay(decoder.report))
        }.attempt.map {
          case Right(report) => Right(report)
          case Left(Rejected(issue)) => Left(issue)
          case Left(error) => throw error // Storage failures are not scientific findings.
        }

  /** A failure after emitted rows still fails BlobStore.put: callers must consume
    * through EOF and may publish only its successfully finalized blob.
    */
  def project[F[_]: Async](input: Stream[F, Byte], gzip: Boolean, schema: Schema,
      selected: Set[String], gzipOutput: Boolean, limits: Limits = Limits()): Stream[F, Byte] =
    Stream.eval(Async[F].fromEither(valid(schema, limits, selected).leftMap(Rejected.apply))).flatMap { _ =>
      Stream.eval(Async[F].delay(new Decoder(schema, limits, selected))).flatMap { decoder =>
        val projected = consume(input, gzip, decoder, limits) ++ Stream.exec(Async[F].delay {
          if decoder.report.invalidValues > 0 then throw Rejected(decoder.report.issues.head)
        })
        if gzipOutput then projected.through(Compression.forSync[F].gzip()) else projected
      }
    }

  private def consume[F[_]: Async](input: Stream[F, Byte], gzip: Boolean, decoder: Decoder,
      limits: Limits): Stream[F, Byte] =
    val encoded = Stream.eval(cats.effect.Ref.of[F, Long](0L)).flatMap { size =>
      input.chunkN(ChunkSize).evalTap { c => size.updateAndGet(n => Math.addExact(n, c.size.toLong)).flatMap { n =>
        Async[F].raiseWhen(n > limits.encodedBytes)(Rejected(Issue("EncodedBytes", "encoded table exceeds byte budget")))
      }}.unchunks
    }
    val decoded = if gzip then StrictGzip.decode(encoded) else encoded
    val content = decoded.chunkN(ChunkSize).evalMap(c => Async[F].delay(decoder.accept(c.toArray)))
      .flatMap(cs => Stream.emits(cs).flatMap(Stream.chunk)) ++
      Stream.eval(Async[F].delay(decoder.finish())).flatMap(cs => Stream.emits(cs).flatMap(Stream.chunk))
    content.interruptWhen(Async[F].sleep(limits.duration).as[Either[Throwable, Unit]](
      Left(Rejected(Issue("Duration", "table check exceeded its time budget")))))

  private final class Decoder(schema: Schema, limits: Limits, selected: Set[String]):
    private val row = new ByteArrayOutputStream
    private val field = new ByteArrayOutputStream
    private val spans = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    private val problems = scala.collection.mutable.ArrayBuffer.empty[Issue]
    private val indices = schema.columns.indices.filter(i => selected(schema.columns(i))).toVector
    private val numeric = schema.columns.map(schema.numeric)
    private val nonnegative = schema.columns.map(schema.nonnegative)
    private val required = schema.columns.map(schema.requiredValues)
    private var mode = 0 // field start, unquoted, quoted, after quote
    private var fieldStart = 0
    private var pendingCR = false
    private var records = 0L
    private var dataRows = 0L
    private var bytes = 0L
    private var invalid = 0L

    def report: Report = Report(dataRows, bytes, invalid, problems.toVector)
    private def sourceRow: Long = records + 1
    private def reject(code: String, message: String): Nothing =
      throw Rejected(Issue(code, message, sourceRow, spans.size + 1))
    private def issue(code: String, message: String): Unit =
      invalid += 1
      if problems.size < limits.diagnostics then problems += Issue(code, message, sourceRow, spans.size + 1)
    private def raw(b: Byte): Unit =
      if row.size >= limits.rowBytes then reject("RowBytes", "table row exceeds byte budget")
      row.write(b & 255)
    private def value(b: Byte): Unit =
      if field.size >= limits.fieldBytes then reject("FieldBytes", "table field exceeds byte budget")
      field.write(b & 255)
    private def completeField(end: Int): Unit =
      if spans.size >= schema.columns.size then reject("Width", "table row has more fields than declared columns")
      val text = Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(field.toByteArray)).toString)
        .getOrElse(reject("Utf8", "table field is not UTF-8"))
      if text.isEmpty then reject("Empty", "empty table fields must be represented by n/a")
      if text.exists(c => c.isControl && c != '\t' && c != '\n' && c != '\r') then reject("Control", "control character in table field")
      val index = spans.size
      if schema.header && records == 0 then
        if text != schema.columns(index) then reject("Header", "table header does not match declared column order")
      else if text == "n/a" then
        if required(index) then issue("Missing", "required numeric value is n/a")
      else if numeric(index) then
        val decimal = boundedNumber(text)
        if decimal.isEmpty then issue("Number", "value must be a bounded decimal or n/a")
        else if nonnegative(index) && decimal.exists(_.signum < 0) then issue("Nonnegative", "value must be zero or positive")
      spans += ((fieldStart, end))
      field.reset()
      mode = 0

    private def completeRow(newlineBytes: Int): Option[Chunk[Byte]] =
      completeField(row.size - newlineBytes)
      if spans.size != schema.columns.size then reject("Width", "table row width differs from declared columns")
      if !schema.header || records > 0 then
        dataRows += 1
        if dataRows > limits.rows then reject("Rows", "table exceeds row budget")
      records += 1
      val result = if indices.isEmpty then None else
        val original = row.toByteArray
        val out = new ByteArrayOutputStream
        indices.zipWithIndex.foreach { (i, n) =>
          if n > 0 then out.write('\t')
          val (from, until) = spans(i)
          out.write(original, from, until - from)
        }
        out.write('\n')
        Some(Chunk.array(out.toByteArray))
      spans.clear()
      row.reset()
      fieldStart = 0
      pendingCR = false
      result

    def accept(chunk: Array[Byte]): Vector[Chunk[Byte]] =
      bytes = Math.addExact(bytes, chunk.length.toLong)
      if bytes > limits.decodedBytes then reject("DecodedBytes", "decoded table exceeds byte budget")
      val output = Vector.newBuilder[Chunk[Byte]]
      chunk.foreach { b =>
        if pendingCR then
          if b != '\n' then reject("Newline", "bare carriage return outside quoted field")
          raw(b)
          completeRow(2).foreach(output += _)
        else if mode == 2 then
          raw(b)
          if b == '"' then mode = 3 else value(b)
        else if b == '\n' then
          raw(b)
          completeRow(1).foreach(output += _)
        else if b == '\r' then
          raw(b)
          pendingCR = true
        else if b == '\t' then
          completeField(row.size)
          raw(b)
          fieldStart = row.size
        else
          raw(b)
          mode match
            case 0 => if b == '"' then mode = 2 else { mode = 1; value(b) }
            case 1 => if b == '"' then reject("Quote", "quote in unquoted field") else value(b)
            case 3 => if b == '"' then { mode = 2; value(b) } else reject("Quote", "unexpected byte after closing quote")
            case _ => reject("Syntax", "invalid table parser state")
      }
      output.result()

    def finish(): Vector[Chunk[Byte]] =
      if mode == 2 then reject("Quote", "unterminated quoted field")
      if pendingCR then reject("Newline", "bare carriage return at end of table")
      val output = if row.size > 0 then completeRow(0).toVector else Vector.empty
      if dataRows == 0 then reject("Empty", "table requires at least one data row")
      output

  private val Decimal = java.util.regex.Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
  private def boundedNumber(value: String): Option[java.math.BigDecimal] =
    if value.length > 128 || !Decimal.matcher(value).matches() then None
    else Try(new java.math.BigDecimal(value)).toOption.filter(n => n.precision <= 128 && math.abs(n.scale.toLong) <= 308)
