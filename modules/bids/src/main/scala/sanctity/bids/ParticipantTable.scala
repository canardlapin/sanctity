package sanctity.bids

import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Strict, unquoted BIDS TSV profile. Source bytes remain in the manifest. */
object ParticipantTable:
  final case class Table(columns: Vector[String], rows: Vector[Vector[String]]):
    def render: String = (columns +: rows).map(_.mkString("\t")).mkString("", "\n", "\n")

  /** A leading byte-order mark and trailing blank lines, both common in exported tables, are ignored.
    * The reason for a refusal names the first row or column at fault.
    */
  def parse(bytes: Array[Byte]): Either[String, Table] =
    for
      text <- Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
        .toEither.left.map(_ => "the table is not valid UTF-8")
      lines = text.stripPrefix("\uFEFF").split("\n", -1).toVector.map(_.stripSuffix("\r")).reverse.dropWhile(_.isEmpty).reverse
      cells = lines.map(_.split("\t", -1).toVector)
      columns = cells.headOption.getOrElse(Vector.empty)
      rows = cells.drop(1)
      _ <- Either.cond(columns.headOption.contains("participant_id"), (), "the first column must be participant_id")
      _ <- columns.diff(columns.distinct).headOption.map(c => s"column '$c' appears more than once").toLeft(())
      _ <- columns.find(c => !c.matches("[A-Za-z][A-Za-z0-9_]*")).map(c => s"column name '$c' must start with a letter and use only letters, digits and underscores").toLeft(())
      _ <- rows.zipWithIndex.collectFirst {
        case (row, i) if row.size != columns.size => s"row ${i + 2} has ${row.size} values for ${columns.size} columns"
        case (row, i) if !row.head.matches("sub-[A-Za-z0-9]+") => s"row ${i + 2} does not start with a participant label of the form sub-<label>"
        case (row, i) if row.exists(_.exists(_.isControl)) => s"row ${i + 2} contains a control character"
      }.toLeft(())
      _ <- rows.map(_.head).diff(rows.map(_.head).distinct).headOption.map(id => s"participant $id appears more than once").toLeft(())
    yield Table(columns, rows)

  def facts(table: Table): Vector[ParticipantFacts] =
    table.rows.map(r => ParticipantFacts(r.head, table.columns.zip(r).toMap - "participant_id"))
