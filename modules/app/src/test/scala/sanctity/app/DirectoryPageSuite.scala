package sanctity.app

import munit.FunSuite
import sanctity.application.Directory
import sanctity.kernel.*
import java.time.Instant
import java.util.{Locale, UUID}

/** CORE7 (was ARC03): the directory cursor contract, on the pure paging function behind every directory.
  *
  * A listing is traversed one row per page so that every boundary is exercised. Expected identifiers and totals are
  * written out, never derived from the function under test.
  */
final class DirectoryPageSuite extends FunSuite:
  private final case class Row(id: String, name: String)
  private val alice: PrincipalId = Id(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
  private val bob: PrincipalId = Id(UUID.fromString("00000000-0000-0000-0000-0000000000b0"))
  private def query(text: Option[String] = None, limit: Int = 1): Directory.Query =
    Directory.Query.from(text, Some(limit)).fold(p => fail(p.toString), identity)
  private def page(rows: Vector[Row], cursor: Option[String] = None, who: PrincipalId = alice, directory: String = "datasets:p",
      q: Directory.Query = query()): Either[Problem, Directory.Page[Row]] =
    Directory.page(who, directory, q, cursor, rows)(_.name, _.id)
  private def first(rows: Vector[Row], q: Directory.Query = query()): Directory.Page[Row] =
    page(rows, q = q).fold(p => fail(p.toString), identity)

  /** Follow cursors to the end; the listing must not change underneath. */
  private def traverse(rows: Vector[Row], q: Directory.Query = query()): Vector[String] =
    Iterator.unfold(Option(Option.empty[String])) {
      case None => None
      case Some(cursor) =>
        val p = page(rows, cursor, q = q).fold(e => fail(e.toString), identity)
        Some(p.items.map(_.id) -> p.next.map(Some(_)))
    }.flatten.toVector

  private def isRestart(result: Either[Problem, Directory.Page[Row]]): Boolean = result match
    case Left(Problem.Conflict(message)) => message.contains("restart")
    case _ => false

  private val a = Row("id-1", "A")
  private val b = Row("id-2", "B")
  private val c = Row("id-3", "C")

  test("a rename that keeps the order between pages restarts; the old cursor never repeats or skips a row") {
    val before = Vector(a, b, c)
    val one = first(before)
    assertEquals((one.items.map(_.id), one.total), (Vector("id-1"), 3))
    // A becomes AA: still first, so the identifier order is unchanged. This is the reproduced defect.
    val after = Vector(a.copy(name = "AA"), b, c)
    assert(isRestart(page(after, one.next)), page(after, one.next).toString)
    assertEquals(traverse(after), Vector("id-1", "id-2", "id-3"))
    assertEquals(first(after).total, 3)
  }

  test("a rename that moves a row, on a page already read or still to come, restarts") {
    val one = first(Vector(a, b, c))
    assert(isRestart(page(Vector(a.copy(name = "Z"), b, c), one.next)))
    assert(isRestart(page(Vector(a, b, c.copy(name = "Aa")), one.next)))
    assertEquals(traverse(Vector(a.copy(name = "Z"), b, c)), Vector("id-2", "id-3", "id-1"))
  }

  test("a rename that changes only letter case is a visible change and restarts") {
    val one = first(Vector(a, b.copy(name = "beta"), c))
    assert(isRestart(page(Vector(a, b.copy(name = "Beta"), c), one.next)))
  }

  test("an unchanged listing continues, and a change the caller cannot see is not a change") {
    val rows = Vector(a, b, c)
    val one = first(rows)
    val two = page(rows, one.next).fold(p => fail(p.toString), identity)
    assertEquals((two.items.map(_.id), two.total), (Vector("id-2"), 3))
    // The caller's visible rows are the input. A hidden row added, renamed or removed never reaches this function,
    // so the continuation is identical.
    assertEquals(page(rows, one.next), Right(two))
    assertEquals(traverse(rows), Vector("id-1", "id-2", "id-3"))
  }

  test("a row that leaves or joins the caller's view between pages restarts, and totals follow at once") {
    val one = first(Vector(a, b, c))
    val revoked = Vector(a, c)
    assert(isRestart(page(revoked, one.next)))
    assertEquals((traverse(revoked), first(revoked).total), (Vector("id-1", "id-3"), 2))
    val granted = Vector(a, b, c, Row("id-4", "Ab"))
    assert(isRestart(page(granted, one.next)))
    assertEquals((traverse(granted), first(granted).total), (Vector("id-1", "id-4", "id-2", "id-3"), 4))
  }

  test("empty names and equal names have one total order, broken by identifier") {
    val rows = Vector(Row("id-9", ""), Row("id-2", "Same"), Row("id-1", "same"), Row("id-5", ""), Row("id-3", "SAME"))
    assertEquals(traverse(rows), Vector("id-5", "id-9", "id-1", "id-2", "id-3"))
    assertEquals(traverse(rows.reverse), Vector("id-5", "id-9", "id-1", "id-2", "id-3"))
    assertEquals(first(rows).total, 5)
  }

  test("search and order fold case the same way under any server locale and any composition of a name") {
    val rows = Vector(Row("id-1", "INDEX study"), Row("id-2", "index pilot"), Row("id-3", "Café cohort"), Row("id-4", "Café archive"))
    def observed = (traverse(rows, query(Some("index"))), traverse(rows, query(Some("CAFÉ"))), traverse(rows))
    val expected = (Vector("id-2", "id-1"), Vector("id-4", "id-3"), Vector("id-4", "id-3", "id-2", "id-1"))
    val saved = Locale.getDefault
    try
      // In a Turkish locale the default lower-casing maps I to a dotless i, which would hide "INDEX study".
      Vector(Locale.ROOT, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("lt-LT")).foreach { locale =>
        Locale.setDefault(locale)
        assertEquals(observed, expected, locale.toString)
      }
    finally Locale.setDefault(saved)
  }

  test("a share that expires or is revoked between pages restarts; what is visible comes from the real policy") {
    import OrganizationAuthority.*
    import OrganizationAuthorityFixtures as Corpus
    val outsider = Corpus.admission("outsider")
    val (open, restricted) = (Corpus.dataset("D-open").render, Corpus.dataset("D-restricted").render)
    def share(name: String, dataset: String, expires: Instant): PolicyGrant =
      PolicyGrant(Corpus.id("grant/" + name), Some(Corpus.principal("alice")), outsider.principal, DataScope(Corpus.dataset(dataset)),
        Set(Permission.Discover, Permission.ReadMetadata), Instant.EPOCH, Some(expires), GrantBasis.Independent, Corpus.generation)
    val lasting = share("lasting", "D-open", Corpus.now.plusSeconds(3600))
    val brief = share("brief", "D-restricted", Corpus.now.plusSeconds(10))
    val state = Corpus.state.copy(grants = Corpus.state.grants ++ Map(lasting.id -> lasting, brief.id -> brief))
    def shares(s: State, at: Instant): Vector[Row] =
      sharedScopes(s, outsider, at).fold(p => fail(p.toString), identity).map(x => Row(x.dataset.render, ""))

    val both = shares(state, Corpus.now)
    assertEquals(both.map(_.id).toSet, Set(open, restricted))
    val one = first(both)
    assertEquals(one.total, 2)
    // The same state eleven seconds later: the brief share has expired.
    val expired = shares(state, Corpus.now.plusSeconds(11))
    assert(isRestart(page(expired, one.next)))
    assertEquals((traverse(expired), first(expired).total), (Vector(open), 1))
    // The lasting share is revoked while the brief one is still live.
    val revoked = shares(state.copy(grants = state.grants.updated(lasting.id, lasting.copy(revoked = true))), Corpus.now)
    assert(isRestart(page(revoked, one.next)))
    assertEquals((traverse(revoked), first(revoked).total), (Vector(restricted), 1))
  }

  test("a cursor cannot be reused by another principal, in another directory, or with another query or page size") {
    val rows = Vector(a, b, c)
    val cursor = first(rows).next
    def foreign(result: Either[Problem, Directory.Page[Row]]) = result match
      case Left(Problem.Unsupported(message)) => message.contains("another principal, directory or query")
      case _ => false
    assert(foreign(page(rows, cursor, who = bob)))
    assert(foreign(page(rows, cursor, directory = "datasets:q")))
    assert(foreign(page(rows, cursor, q = query(Some("a")))))
    assert(foreign(page(rows, cursor, q = query(limit = 2))))
    assert(page(rows, Some("not-a-cursor")).isLeft)
    assert(page(rows, Some("")).isLeft)
  }
