package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.implicits.*
import io.circe.Json
import io.circe.jawn.JawnParser
import munit.FunSuite
import org.http4s.*
import org.typelevel.ci.CIString
import sanctity.app.OrganizationAuthorityFixtures as Corpus
import sanctity.application.*
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.Request as Policy
import java.time.Instant
import scala.concurrent.duration.*

/** CORE8 (was ARC05): grant creation over HTTP is safe to retry after a lost response.
  *
  * An `Idempotency-Key` names the grant for its caller and dataset. Repeating the same intent returns the same
  * grant. The key cannot be moved to another intent, and a replay never renews access.
  */
final class GrantRetrySuite extends FunSuite:
  override val munitTimeout = 5.minutes
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, f.system.repository, credentials(f),
    organization = Some(f.database.organization)).app
  private def call(f: PgFixture, method: Method, target: String, token: String, body: Option[Json] = None, key: Option[String] = None): IO[(Status, String)] =
    val base = Request[IO](method, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    val keyed = key.fold(base)(k => base.putHeaders(Header.Raw(CIString("Idempotency-Key"), k)))
    api(f).run(body.fold(keyed)(j => keyed.withEntity(j.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))))
      .flatMap(r => r.as[String].map(r.status -> _))
  private def json(s: String): Json = new JawnParser().parse(s).toOption.get
  private def gen(j: Json): Long = j.hcursor.get[Long]("generation").toOption.get
  private val open = Corpus.dataset("D-open")
  private val outsider = Corpus.principal("outsider").render
  private val grantsUrl = s"/v1/datasets/${open.render}/grants"
  private val notFound = """{"error":"not_found"}"""

  private def world(f: PgFixture): IO[Map[String, String]] = for
    _ <- AuthoritySeed.seed(f, Corpus.Scenario(Corpus.state, Corpus.now, Corpus.admission("alice"), Policy.ListSharedReleases, Json.Null))
    tokens <- Vector("alice", "bob", "dan", "eve", "outsider").traverse(who =>
      checked(credentials(f).issue(Corpus.principal(who), who, 3600)).map(t => who -> t.secret))
  yield tokens.toMap

  private def share(recipient: String, actions: Vector[String], expires: Option[Instant], release: Option[String] = None): Json =
    Json.obj("recipient" -> Json.fromString(recipient), "release" -> release.fold(Json.Null)(Json.fromString),
      "actions" -> Json.arr(actions.map(Json.fromString)*), "expiresAt" -> expires.fold(Json.Null)(e => Json.fromString(e.toString)))
  private def legacy(recipient: String, actions: Vector[String]): Json =
    Json.obj("recipient" -> Json.fromString(recipient), "release" -> Json.Null, "actions" -> Json.arr(actions.map(Json.fromString)*))
  private def id(body: String): String = json(body).hcursor.get[String]("id").toOption.get

  /** Issuances recorded for D-open: (policy events, audit rows, stored grants). */
  private def issuances(f: PgFixture): IO[(Int, Int, Int)] =
    (for
      events <- sql"SELECT count(*) FROM organization_event WHERE payload->>'kind'='GrantIssued'".query[Int].unique
      audit <- sql"SELECT count(*) FROM policy_audit WHERE action='Granted'".query[Int].unique
      grants <- sql"SELECT count(*) FROM access_grant".query[Int].unique
    yield (events, audit, grants)).transact(f.database.transactor)
  private def plus(counts: (Int, Int, Int), n: Int): (Int, Int, Int) = (counts._1 + n, counts._2 + n, counts._3 + n)
  private def sharedWith(f: PgFixture, token: String): IO[Vector[String]] =
    call(f, Method.GET, "/v1/me/shares", token).map(x => json(x._2).hcursor.downField("items").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      .flatMap(_.hcursor.get[String]("dataset").toOption))

  test("a retry with the same key and the same intent returns the same grant: one grant, one issuance") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      before <- issuances(f)
      expiry = Instant.now.plusSeconds(3600)
      first <- call(f, Method.POST, grantsUrl, t("alice"), Some(share(outsider, Vector("Discover", "ReadMetadata"), Some(expiry))), Some("share-1"))
      _ = assertEquals(first._1, Status.Created, first._2)
      // The response was lost; the caller repeats it. The action set is a set, so its order does not matter.
      retry <- call(f, Method.POST, grantsUrl, t("alice"), Some(share(outsider, Vector("ReadMetadata", "Discover"), Some(expiry))), Some("share-1"))
      _ = assertEquals(retry, first)
      after <- issuances(f)
      _ = assertEquals(after, plus(before, 1))
      seen <- sharedWith(f, t("outsider"))
      _ = assertEquals(seen, Vector(open.render))
      // The legacy shape (no expiry, legacy actions) is retried the same way.
      one <- call(f, Method.POST, grantsUrl, t("alice"), Some(legacy(outsider, Vector("ReadMetadata", "ReadContent"))), Some("legacy-1"))
      two <- call(f, Method.POST, grantsUrl, t("alice"), Some(legacy(outsider, Vector("ReadContent", "ReadMetadata"))), Some("legacy-1"))
      _ = assertEquals(one._1, Status.Created, one._2)
      _ = assertEquals(two, one)
      _ = assertNotEquals(id(one._2), id(first._2))
      end <- issuances(f)
      _ = assertEquals(end, plus(before, 2))
    yield () }.unsafeRunSync()
  }

  test("concurrent duplicate submissions issue one grant and all receive it") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      before <- issuances(f)
      body = share(outsider, Vector("Discover", "ReadMetadata"), Some(Instant.now.plusSeconds(3600)))
      results <- (1 to 8).toVector.parTraverse(_ => call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("race-1")))
      _ = assertEquals(results.map(_._1).distinct, Vector(Status.Created), results.toString)
      _ = assertEquals(results.map(_._2).distinct.size, 1, results.toString)
      after <- issuances(f)
      _ = assertEquals(after, plus(before, 1))
    yield () }.unsafeRunSync()
  }

  test("a key cannot be reused for another recipient, scope, action set, expiry or grant shape") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      expiry = Instant.now.plusSeconds(3600)
      actions = Vector("Discover", "ReadMetadata")
      first <- call(f, Method.POST, grantsUrl, t("alice"), Some(share(outsider, actions, Some(expiry))), Some("k"))
      _ = assertEquals(first._1, Status.Created, first._2)
      before <- issuances(f)
      others = Vector(
        share(Corpus.principal("bob").render, actions, Some(expiry)),
        share(outsider, actions, Some(expiry), Some(Corpus.release("D-open", "R1").render)),
        share(outsider, Vector("Discover"), Some(expiry)),
        share(outsider, actions, Some(expiry.plusSeconds(60))),
        legacy(outsider, Vector("ReadMetadata")))
      reused <- others.traverse(b => call(f, Method.POST, grantsUrl, t("alice"), Some(b), Some("k")))
      _ = assertEquals(reused.map(_._1), Vector.fill(others.size)(Status.Conflict), reused.toString)
      after <- issuances(f)
      _ = assertEquals(after, before)
      // The original is untouched and still answers its own retry.
      again <- call(f, Method.POST, grantsUrl, t("alice"), Some(share(outsider, actions, Some(expiry))), Some("k"))
      _ = assertEquals(again, first)
    yield () }.unsafeRunSync()
  }

  test("different keys issue independent grants that are revoked independently; no key issues a new grant each time") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      before <- issuances(f)
      body = share(outsider, Vector("Discover", "ReadMetadata"), Some(Instant.now.plusSeconds(3600)))
      a <- call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("a"))
      b <- call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("b"))
      // Clients that send no key keep the old behaviour.
      c <- call(f, Method.POST, grantsUrl, t("alice"), Some(body))
      d <- call(f, Method.POST, grantsUrl, t("alice"), Some(body))
      _ = assertEquals(Vector(a, b, c, d).map(_._1), Vector.fill(4)(Status.Created))
      _ = assertEquals(Vector(a, b, c, d).map(x => id(x._2)).distinct.size, 4)
      after <- issuances(f)
      _ = assertEquals(after, plus(before, 4))
      revoked <- Vector(a, c, d).traverse(x => call(f, Method.DELETE, s"$grantsUrl/${id(x._2)}", t("alice")))
      _ = assertEquals(revoked.map(_._1), Vector.fill(3)(Status.NoContent))
      still <- sharedWith(f, t("outsider"))
      _ = assertEquals(still, Vector(open.render))
      last <- call(f, Method.DELETE, s"$grantsUrl/${id(b._2)}", t("alice"))
      _ = assertEquals(last._1, Status.NoContent)
      gone <- sharedWith(f, t("outsider"))
      _ = assertEquals(gone, Vector.empty)
      malformed <- call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("x" * 201))
      _ = assertEquals(malformed._1, Status.BadRequest, malformed._2)
    yield () }.unsafeRunSync()
  }

  test("a replay after revocation conflicts and does not renew access") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      body = share(outsider, Vector("Discover", "ReadMetadata"), Some(Instant.now.plusSeconds(3600)))
      first <- call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("once"))
      _ = assertEquals(first._1, Status.Created, first._2)
      revoked <- call(f, Method.DELETE, s"$grantsUrl/${id(first._2)}", t("alice"))
      _ = assertEquals(revoked._1, Status.NoContent)
      before <- issuances(f)
      replay <- call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("once"))
      _ = assertEquals(replay._1, Status.Conflict, replay._2)
      after <- issuances(f)
      _ = assertEquals(after, before)
      seen <- sharedWith(f, t("outsider"))
      _ = assertEquals(seen, Vector.empty)
      // A new grant is a new operation with a new key.
      fresh <- call(f, Method.POST, grantsUrl, t("alice"), Some(body), Some("twice"))
      _ = assertEquals(fresh._1, Status.Created, fresh._2)
      _ = assertNotEquals(id(fresh._2), id(first._2))
    yield () }.unsafeRunSync()
  }

  test("a replay after the issuer lost authority is denied like any other request and reveals nothing") {
    // D-restricted is ExplicitOnly: its steward is the only access manager. (On D-open, Alice keeps authority through
    // her project role after handing over stewardship, and her retry is rightly answered.)
    val restricted = Corpus.dataset("D-restricted")
    val url = s"/v1/datasets/${restricted.render}/grants"
    PostgresFixture.resource.use { f => for
      t <- world(f)
      body = share(outsider, Vector("Discover", "ReadMetadata"), Some(Instant.now.plusSeconds(3600)))
      first <- call(f, Method.POST, url, t("alice"), Some(body), Some("mine"))
      _ = assertEquals(first._1, Status.Created, first._2)
      // Alice hands stewardship to dan and no longer manages access to the dataset.
      place <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/placement", t("alice")).map(x => json(x._2))
      from = place.hcursor.downField("stewards").downArray
      fromId = from.get[String]("id").toOption.get
      moved <- call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("alice"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("dan").render),
        "expected" -> Json.obj(s"stewardship:$fromId" -> Json.fromLong(gen(from.focus.get)), s"placement:${restricted.render}" -> Json.fromLong(gen(place))))), Some("handover"))
      _ = assertEquals(moved._1, Status.Created, moved._2)
      before <- issuances(f)
      replay <- call(f, Method.POST, url, t("alice"), Some(body), Some("mine"))
      stranger <- call(f, Method.POST, url, t("bob"), Some(body), Some("mine"))
      _ = assertEquals(replay, Status.NotFound -> notFound)
      _ = assertEquals(stranger, Status.NotFound -> notFound)
      after <- issuances(f)
      _ = assertEquals(after, before)
      // The grant Alice issued while she had authority is unchanged, and the new steward's same key is his own.
      seen <- sharedWith(f, t("outsider"))
      _ = assertEquals(seen, Vector(restricted.render))
      dans <- call(f, Method.POST, url, t("dan"), Some(body), Some("mine"))
      _ = assertEquals(dans._1, Status.Created, dans._2)
      _ = assertNotEquals(id(dans._2), id(first._2))
    yield () }.unsafeRunSync()
  }
