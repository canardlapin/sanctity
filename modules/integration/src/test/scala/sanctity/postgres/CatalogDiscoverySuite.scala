package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.jawn.JawnParser
import munit.FunSuite
import org.http4s.*
import org.typelevel.ci.CIString
import sanctity.app.OrganizationAuthorityFixtures as Corpus
import sanctity.application.*
import sanctity.bids.{DatasetInterpreter, ReleaseDescriptions}
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.Request as Policy
import java.time.Instant
import scala.concurrent.duration.*

/** ADR 005: someone who may discover a release sees what it says about itself, and nothing else about it.
  * Discovery fixed to one release does not reach another, it ends with the grant, and it never opens a private bundle.
  */
final class CatalogDiscoverySuite extends FunSuite:
  override val munitTimeout = 5.minutes
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def repository(f: PgFixture) = new NativeRepository[IO](f.system.catalog, f.system.store, new DatasetInterpreter[IO](f.system.store),
    IdSource.random[IO], Some(new ReleaseDescriptions[IO](f.system.store)))
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, repository(f), credentials(f), organization = Some(f.database.organization)).app
  private def call(f: PgFixture, method: Method, target: String, token: String, body: Option[Json] = None): IO[(Status, String)] =
    val base = Request[IO](method, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    api(f).run(body.fold(base)(j => base.withEntity(j.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))))
      .flatMap(r => r.as[String].map(r.status -> _))
  private def json(s: String): Json = new JawnParser().parse(s).toOption.get
  private val open = Corpus.dataset("D-open")
  private val restricted = Corpus.dataset("D-restricted")
  private def ref(d: String, r: String) = ReleaseRef(Corpus.dataset(d), Corpus.release(d, r))
  private def release(r: ReleaseRef) = s"/v1/datasets/${r.dataset.render}/releases/${r.release.render}"
  private def catalog(r: ReleaseRef) = release(r) + "/catalog"
  private val notFound = Status.NotFound -> """{"error":"not_found"}"""
  private def title(body: String): Option[String] = json(body).hcursor.get[Option[String]]("title").toOption.flatten
  private def record(name: String) = CatalogRecord.from(CatalogRecord.Bids, Some(name), Some(s"Description of $name."), None,
    Vector("A. Researcher"), Vector.empty, Vector("func"), Some("CC0"), Vector.empty)

  private def world(f: PgFixture): IO[Map[String, String]] = for
    _ <- AuthoritySeed.seed(f, Corpus.Scenario(Corpus.state, Corpus.now, Corpus.admission("alice"), Policy.ListSharedReleases, Json.Null))
    tokens <- Vector("alice", "bob", "outsider").traverse(who =>
      checked(credentials(f).issue(Corpus.principal(who), who, 3600)).map(t => who -> t.secret))
  yield tokens.toMap
  /** Turn an accepted release into one person's private bundle of another release, as a private view records it. */
  private def makePrivate(f: PgFixture, bundle: ReleaseRef, source: ReleaseRef, owner: PrincipalId): IO[Unit] =
    import JsonCodecs.given
    val id = Records.uuid(bundle.release)
    (for
      text <- sql"SELECT c.activity::text FROM release r JOIN candidate c ON c.id=r.candidate_id WHERE r.id=$id".query[String].unique
      activity = JsonCodecs.decode[Activity](text).toOption.get
      evidence = ProjectionEvidence(source, SelectionId(Digest.bytes("selection".getBytes)), Digest.bytes(Array.emptyByteArray), "compiler", Vector.empty)
      changed = JsonCodecs.encode(activity.copy(kind = ActivityKind.PrivateBundle, actor = owner, projection = Some(evidence)))
      n <- sql"UPDATE candidate SET activity=$changed::jsonb WHERE id=(SELECT candidate_id FROM release WHERE id=$id)".update.run
    yield assertEquals(n, 1)).transact(f.database.transactor)

  private def grant(f: PgFixture, token: String, dataset: DatasetId, recipient: String, release: Option[ReleaseRef], actions: Vector[String], expires: Boolean): IO[String] =
    call(f, Method.POST, s"/v1/datasets/${dataset.render}/grants", token, Some(Json.obj("recipient" -> Json.fromString(Corpus.principal(recipient).render),
      "release" -> release.fold(Json.Null)(r => Json.fromString(r.release.render)), "actions" -> Json.arr(actions.map(Json.fromString)*)).deepMerge(
      if expires then Json.obj("expiresAt" -> Json.fromString(Instant.now.plusSeconds(3600).toString)) else Json.obj())))
      .map { (status, body) => assertEquals(status, Status.Created, body); json(body).hcursor.get[String]("id").toOption.get }

  test("discovery shows a release's record and nothing else; fixed to one release it does not reach another; it ends with the grant; a private bundle stays private") {
    PostgresFixture.resource.use { f =>
      val store = f.database.catalog(IdSource.random[IO])
      for
        t <- world(f)
        // Before any access the outsider learns nothing, whether or not a record is stored.
        none <- call(f, Method.GET, catalog(ref("D-open", "R1")), t("outsider"))
        _ = assertEquals(none, notFound)
        discover <- grant(f, t("alice"), open, "outsider", None, Vector("Discover"), expires = true)
        // These releases were accepted before records were kept. Discovery still gets a record, derived from what was retained.
        derived <- call(f, Method.GET, catalog(ref("D-open", "R1")), t("outsider"))
        _ = assertEquals(derived._1, Status.Ok, derived._2)
        _ = assertEquals(json(derived._2).hcursor.get[String]("kind").toOption, Some("bids"))
        _ <- Vector("D-open" -> "R1", "D-open" -> "R2", "D-restricted" -> "R1", "D-restricted" -> "R2").traverse_((d, r) =>
          checked(store.backfillCatalogRecord(ref(d, r), record(s"$d $r"))).map(stored => assert(stored, s"$d $r")))
        // Discovery of the dataset covers each of its releases, and gives the record only.
        first <- call(f, Method.GET, catalog(ref("D-open", "R1")), t("outsider"))
        second <- call(f, Method.GET, catalog(ref("D-open", "R2")), t("outsider"))
        _ = assertEquals((first._1, title(first._2), second._1, title(second._2)), (Status.Ok, Some("D-open R1"), Status.Ok, Some("D-open R2")))
        metadata <- call(f, Method.GET, release(ref("D-open", "R1")), t("outsider"))
        files <- call(f, Method.GET, release(ref("D-open", "R1")) + "/archive", t("outsider"))
        _ = assertEquals((metadata, files), (notFound, notFound))
        // Discovery fixed to R1 of another dataset shows R1's record and not R2's.
        _ <- grant(f, t("alice"), restricted, "outsider", Some(ref("D-restricted", "R1")), Vector("Discover"), expires = true)
        shared <- call(f, Method.GET, catalog(ref("D-restricted", "R1")), t("outsider"))
        hidden <- call(f, Method.GET, catalog(ref("D-restricted", "R2")), t("outsider"))
        _ = assertEquals((shared._1, title(shared._2)), (Status.Ok, Some("D-restricted R1")))
        _ = assertEquals(hidden, notFound)
        // A link-only reader (metadata without discovery) reads the record of exactly the release they hold.
        _ <- grant(f, t("alice"), restricted, "bob", Some(ref("D-restricted", "R1")), Vector("ReadMetadata"), expires = false)
        linked <- call(f, Method.GET, catalog(ref("D-restricted", "R1")), t("bob"))
        beyond <- call(f, Method.GET, catalog(ref("D-restricted", "R2")), t("bob"))
        _ = assertEquals((linked._1, title(linked._2), beyond), (Status.Ok, Some("D-restricted R1"), notFound))
        // Revoking discovery ends it.
        revoked <- call(f, Method.DELETE, s"/v1/datasets/${open.render}/grants/$discover", t("alice"))
        _ = assertEquals(revoked._1, Status.NoContent)
        after <- call(f, Method.GET, catalog(ref("D-open", "R1")), t("outsider"))
        _ = assertEquals(after, notFound)
        still <- call(f, Method.GET, catalog(ref("D-restricted", "R1")), t("outsider"))
        _ = assertEquals(still._1, Status.Ok)
        // A private bundle stays with the person who made it. Neither discovery nor a metadata grant opens its record.
        _ <- makePrivate(f, ref("D-restricted", "R1"), ref("D-open", "R1"), Corpus.principal("alice"))
        discovered <- call(f, Method.GET, catalog(ref("D-restricted", "R1")), t("outsider"))
        granted <- call(f, Method.GET, catalog(ref("D-restricted", "R1")), t("bob"))
        _ = assertEquals((discovered, granted), (notFound, notFound))
        owner <- call(f, Method.GET, catalog(ref("D-restricted", "R1")), t("alice"))
        _ = assertEquals((owner._1, title(owner._2)), (Status.Ok, Some("D-restricted R1")))
      yield ()
    }.unsafeRunSync()
  }
