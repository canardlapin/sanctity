package sanctity.application

import sanctity.kernel.*
import java.nio.charset.StandardCharsets.UTF_8
import java.text.Normalizer
import java.util.{Base64, Locale}

/** Bounded, authorized directory pages (F05.1, ADR 001 "Pagination").
  *
  * Callers pass only rows the current principal may discover. Filtering, ordering, totals and cursors are computed on
  * that projection, so nothing hidden can shape a count or a position. A cursor names its principal, directory and
  * query, the last key returned and a digest of the caller's whole matching listing: every visible name and
  * identifier, in order. Each later page recomputes the listing under current policy; if an entry appeared,
  * disappeared or was renamed, the page is an explicit restart conflict rather than a page drawn from the old
  * listing. The cursor holds no secret: altering it only moves within the caller's own authorized rows.
  */
object Directory:
  val maxLimit = 100
  val defaultLimit = 25

  /** One case folding for search and order, independent of the server's locale and of how a name was composed. */
  private def fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)

  final case class Query(text: Option[String], limit: Int):
    def matches(name: String): Boolean = text.forall(t => fold(name).contains(t))
  object Query:
    def from(text: Option[String], limit: Option[Int]): Either[Problem, Query] =
      val q = text.map(t => fold(t.trim)).filter(_.nonEmpty)
      for
        _ <- Either.cond(q.forall(_.length <= 200), (), Problem.Unsupported("search text exceeds 200 characters"))
        n = limit.getOrElse(defaultLimit)
        _ <- Either.cond(n >= 1 && n <= maxLimit, (), Problem.Unsupported(s"limit must be 1 to $maxLimit"))
      yield Query(q, n)
  final case class Page[A](items: Vector[A], total: Int, next: Option[String])

  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder
  private def field(s: String) = enc.encodeToString(s.getBytes(UTF_8))
  private def unfield(s: String) = new String(dec.decode(s), UTF_8)
  private val invalid = Problem.Unsupported("invalid directory cursor")

  /** `name` orders and is searched (only visible names); `id` breaks ties so the order is total.
    *
    * The digest covers each matching row's name as displayed and its identifier, in listing order. A digest of the
    * identifiers alone let a rename that kept the order pass unnoticed: the renamed row then sorted after its own
    * old key and was returned twice (ARC03).
    */
  def page[A](principal: PrincipalId, directory: String, query: Query, cursor: Option[String], visible: Vector[A])(
      name: A => String, id: A => String): Either[Problem, Page[A]] =
    val key = (a: A) => (fold(name(a)), id(a))
    val matching = visible.filter(a => query.matches(name(a))).sortBy(key)
    val digest = Digest.framed("sanctity.directory-listing.v2", matching.flatMap(a => Vector(name(a), id(a)))).value
    val scope = Vector(principal.render, directory, query.text.getOrElse(""), query.limit.toString)
    for
      after <- cursor match
        case None => Right(None)
        case Some(c) =>
          for
            parts <- scala.util.Try(unfield(c).split("\n", -1).toVector.map(unfield)).toOption.toRight(invalid)
            _ <- Either.cond(parts.size == 8 && parts.head == "v1", (), invalid)
            _ <- Either.cond(parts.slice(1, 5) == scope, (), Problem.Unsupported("cursor belongs to another principal, directory or query"))
            _ <- Either.cond(parts(7) == digest, (), Problem.Conflict("directory changed since the first page; restart"))
          yield Some(parts(5) -> parts(6))
      rest = after.fold(matching)(k => matching.dropWhile(a => Ordering[(String, String)].lteq(key(a), k)))
      items = rest.take(query.limit)
      next = Option.when(rest.size > items.size)(items.last).map { last =>
        val (n, i) = key(last)
        field((Vector("v1") ++ scope ++ Vector(n, i, digest)).map(field).mkString("\n"))
      }
    yield Page(items, matching.size, next)
