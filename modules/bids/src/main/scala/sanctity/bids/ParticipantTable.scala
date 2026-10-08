package sanctity.bids

import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** Strict, unquoted BIDS TSV profile. Source bytes remain in the manifest. */
object ParticipantTable:
  final case class Table(columns: Vector[String], rows: Vector[Vector[String]]):
    def render: String = (columns +: rows).map(_.mkString("\t")).mkString("", "\n", "\n")
  def parse(bytes: Array[Byte]): Either[Problem, Table] =
    for
      text <- Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
        .toEither.left.map(_ => Problem.Integrity("invalid UTF-8 participant table"))
      lines = text.stripSuffix("\n").split("\n", -1).toVector.map(_.stripSuffix("\r"))
      cells = lines.map(_.split("\t", -1).toVector)
      columns = cells.headOption.getOrElse(Vector.empty)
      _ <- Either.cond(columns.headOption.contains("participant_id") && columns.distinct.size == columns.size &&
        columns.forall(_.matches("[A-Za-z][A-Za-z0-9_]*")) && cells.tail.forall(r => r.size == columns.size &&
          r.head.matches("sub-[A-Za-z0-9]+") && r.forall(v => !v.exists(_.isControl))) &&
        cells.tail.map(_.head).distinct.size == cells.tail.size,
        (), Problem.Unsupported("participant table requires unique columns/participants, participant_id first, and unquoted rows"))
    yield Table(columns, cells.tail)

  def facts(table: Table): Vector[ParticipantFacts] =
    table.rows.map(r => ParticipantFacts(r.head, table.columns.zip(r).toMap - "participant_id"))
