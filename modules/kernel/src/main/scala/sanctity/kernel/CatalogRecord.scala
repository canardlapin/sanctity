package sanctity.kernel

/** What an accepted release says about itself, taken from its own descriptor (SEE2).
  *
  * A record is a projection of the release: the same release always yields the same record, so it can be rebuilt
  * at any time from retained bytes and never changes a release's identity. Fields the descriptor does not give are
  * empty; nothing is inferred. Every field is scientific metadata: reading it needs ReadMetadata on that exact
  * release. Discovery alone shows only the steward's label (ADR 002).
  */
final case class CatalogRecord private (kind: String, title: Option[String], description: Option[String], descriptionFrom: Option[RelPath],
    creators: Vector[String], keywords: Vector[String], dataTypes: Vector[String], license: Option[String], identifiers: Vector[String])

object CatalogRecord:
  val Bids = "bids"
  val Archive = "archive"
  val MaxText = 4096
  val MaxDescription = 16384
  val MaxItems = 1000

  private def text(value: String, limit: Int): Option[String] =
    Option(value.trim).filter(_.nonEmpty).map(s => if s.length <= limit then s else s.substring(0, s.offsetByCodePoints(0, s.codePointCount(0, limit))))
  private def list(values: Vector[String]): Vector[String] = values.flatMap(text(_, MaxText)).distinct.take(MaxItems)

  /** Every field is trimmed and bounded, so a record stays small whatever the descriptor held. */
  def from(kind: String, title: Option[String], description: Option[String], descriptionFrom: Option[RelPath], creators: Vector[String],
      keywords: Vector[String], dataTypes: Vector[String], license: Option[String], identifiers: Vector[String]): CatalogRecord =
    val bounded = description.flatMap(text(_, MaxDescription))
    new CatalogRecord(kind, title.flatMap(text(_, MaxText)), bounded, descriptionFrom.filter(_ => bounded.nonEmpty), list(creators),
      list(keywords), list(dataTypes).sorted, license.flatMap(text(_, MaxText)), list(identifiers))

  /** A release whose descriptor could not be read still has a record: its kind, and nothing else. */
  def empty(kind: String): CatalogRecord = from(kind, None, None, None, Vector.empty, Vector.empty, Vector.empty, None, Vector.empty)
