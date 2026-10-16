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
import sanctity.app.SyntheticFixture
import sanctity.application.*
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.{GrantBasis, Request as Policy}
import java.util.UUID
import scala.concurrent.duration.*

/** F03.3: public read projections under current ADR 002 authority, through HTTP and PostgreSQL.
  * Canary cases change data the caller cannot see and require byte-identical responses.
  */
final class DisclosureSuite extends FunSuite:
  import Records.uuid
  override val munitTimeout = 5.minutes
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, f.system.repository, credentials(f)).app
  private def get(f: PgFixture, target: String, token: String): IO[(Status, String)] =
    api(f).run(Request[IO](Method.GET, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token")))
      .flatMap(r => r.as[String].map(r.status -> _))
  private def json(body: String): Json = new JawnParser().parse(body).toOption.get
  private def sql(f: PgFixture, q: doobie.ConnectionIO[Int]): IO[Unit] = q.transact(f.database.transactor).void

  private val open = Corpus.dataset("D-open")
  private val restricted = Corpus.dataset("D-restricted")
  private def rel(d: String, r: String) = Corpus.release(d, r)
  private def releaseUrl(d: DatasetId, r: ReleaseId) = s"/v1/datasets/${d.render}/releases/${r.render}"
  private def candidateOf(d: String, r: String) = Corpus.id[CandidateTag]("candidate/" + rel(d, r).render)

  /** The baseline corpus in PostgreSQL, R2 descending from R1 in both datasets, and a bearer token per person. */
  private def world(f: PgFixture): IO[Map[String, String]] = for
    _ <- AuthoritySeed.seed(f, Corpus.Scenario(Corpus.state, Corpus.now, Corpus.admission("alice"), Policy.ListSharedReleases, Json.Null))
    _ <- Vector("D-open", "D-restricted").traverse_ { d =>
      val (r1, r2) = (uuid(rel(d, "R1")), uuid(rel(d, "R2")))
      val (draft, candidate) = (uuid(Corpus.id[DraftTag]("draft/" + rel(d, "R2").render)), uuid(candidateOf(d, "R2")))
      sql(f, for
        _ <- sql"UPDATE draft SET base=$r1 WHERE id=$draft".update.run
        _ <- sql"UPDATE candidate SET base=$r1 WHERE id=$candidate".update.run
        n <- sql"UPDATE release SET parent=$r1 WHERE id=$r2".update.run
      yield n)
    }
    tokens <- Vector("alice", "bob", "carol", "dan", "eve", "outsider").traverse(who =>
      checked(credentials(f).issue(Corpus.principal(who), who, 3600)).map(t => who -> t.secret))
  yield tokens.toMap

  private def grantRow(f: PgFixture, name: String, who: String, d: DatasetId, r: Option[ReleaseId], actions: List[String]): IO[Unit] =
    import OrganizationCodecs.given
    val id = uuid(Corpus.id[GrantTag]("grant/" + name))
    val basis = JsonCodecs.encode(GrantBasis.Independent: GrantBasis)
    sql(f, sql"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions,created_at,basis,basis_payload,generation)
      VALUES($id,${uuid(d)},${uuid(Corpus.principal(who))},${r.map(uuid)},$actions,clock_timestamp()-interval '1 second','Independent',$basis::jsonb,
        ${OrganizationGeneration.initial.value})""".update.run)

  test("a fixed-release share sees its release alone: no parent, head or provenance, and hidden changes leave it unchanged") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      _ <- grantRow(f, "outsider-r2", "outsider", restricted, Some(rel("D-restricted", "R2")), List("Discover", "ReadMetadata"))
      shared <- get(f, releaseUrl(restricted, rel("D-restricted", "R2")), t("outsider"))
      _ = assertEquals(shared._1, Status.Ok)
      metadataUrl = releaseUrl(restricted, rel("D-restricted", "R2")) + "/metadata?path=" + SyntheticFixture.firstImage
      metadata <- get(f, metadataUrl, t("outsider"))
      _ = assertEquals(metadata._1, Status.Ok)
      body = json(shared._2)
      _ = assert(body.hcursor.downField("parent").focus.exists(_.isNull), "a share of R2 must not name R1")
      _ = assert(body.hcursor.downField("provenance").focus.exists(_.isNull), "curation history is dataset-wide")
      steward <- get(f, releaseUrl(restricted, rel("D-restricted", "R2")), t("alice")).map(x => json(x._2))
      _ = assertEquals(steward.hcursor.get[String]("parent").toOption, Some(rel("D-restricted", "R1").render))
      // The older release, the head and an unknown release are indistinguishable.
      older <- get(f, releaseUrl(restricted, rel("D-restricted", "R1")), t("outsider"))
      head <- get(f, s"/v1/datasets/${restricted.render}", t("outsider"))
      unknown <- get(f, releaseUrl(restricted, Id[ReleaseTag](UUID.randomUUID())), t("outsider"))
      _ = assertEquals(Vector(older, head, unknown).distinct, Vector(Status.NotFound -> """{"error":"not_found"}"""))
      // Hidden changes: another share, a project rename, a head move and a revocation elsewhere.
      _ <- grantRow(f, "bob-restricted", "bob", restricted, None, List("ReadMetadata"))
      _ <- sql(f, sql"""UPDATE organization_project SET payload=jsonb_set(payload,'{name}','"Renamed in secret"'::jsonb) WHERE payload->>'id'=${Corpus.projectId("P-memory").render}""".update.run)
      _ <- sql(f, sql"UPDATE dataset SET head=${uuid(rel("D-restricted", "R1"))} WHERE id=${uuid(restricted)}".update.run)
      _ <- sql(f, sql"UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=${uuid(Corpus.id[GrantTag]("grant/G-eve-r1"))}".update.run)
      again <- get(f, releaseUrl(restricted, rel("D-restricted", "R2")), t("outsider"))
      metadataAgain <- get(f, metadataUrl, t("outsider"))
      _ = assertEquals(again, shared)
      _ = assertEquals(metadataAgain, metadata)
    yield () }.unsafeRunSync()
  }

  test("candidates need Propose and metadata authority; Accept alone or reading alone is not enough") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      _ <- grantRow(f, "carol-accept", "carol", open, None, List("Accept"))
      url = s"/v1/candidates/${candidateOf("D-open", "R2").render}"
      contributor <- get(f, url, t("dan"))
      acceptOnly <- get(f, url, t("carol"))
      reader <- get(f, url, t("bob"))
      _ = assertEquals(contributor._1, Status.Ok)
      _ = assertEquals(Vector(acceptOnly, reader).distinct, Vector(Status.NotFound -> """{"error":"not_found"}"""))
    yield () }.unsafeRunSync()
  }

  test("release provenance shows readers why and what changed, and curator identities only to curators") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      reader <- get(f, releaseUrl(open, rel("D-open", "R2")), t("bob")).map(x => json(x._2).hcursor.downField("provenance"))
      curator <- get(f, releaseUrl(open, rel("D-open", "R2")), t("dan")).map(x => json(x._2).hcursor.downField("provenance"))
      _ = assert(reader.get[String]("kind").isRight && reader.downField("changesFromParent").focus.exists(!_.isNull), reader.focus.toString)
      _ = assert(reader.downField("proposedBy").focus.exists(_.isNull) && reader.downField("acceptedBy").focus.exists(_.isNull), "readers do not see curators")
      _ = assert(curator.get[String]("acceptedBy").isRight, "a contributor sees who accepted")
    yield () }.unsafeRunSync()
  }

  test("access managers list a dataset's grants with state and expiry; nobody else can, and the shape never reveals why") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      url = s"/v1/datasets/${restricted.render}/grants"
      listed <- get(f, url, t("alice"))
      _ = assertEquals(listed._1, Status.Ok)
      grants = json(listed._2).hcursor.get[Vector[Json]]("grants").toOption.get
      eve = grants.find(_.hcursor.get[String]("id").toOption.contains(Corpus.id[GrantTag]("grant/G-eve-r1").render)).get.hcursor
      _ = assertEquals(eve.get[String]("recipient").toOption, Some(Corpus.principal("eve").render))
      _ = assertEquals(eve.get[String]("release").toOption, Some(rel("D-restricted", "R1").render))
      _ = assertEquals(eve.get[String]("state").toOption, Some("active"))
      _ = assert(eve.get[String]("effectiveExpiresAt").isRight && !listed._2.contains("issuer"), listed._2)
      denied <- Vector("bob", "eve", "outsider").traverse(who => get(f, url, t(who)))
      unknown <- get(f, s"/v1/datasets/${UUID.randomUUID()}/grants", t("alice"))
      _ = assertEquals((denied :+ unknown).distinct, Vector(Status.NotFound -> """{"error":"not_found"}"""))
      _ <- sql(f, sql"UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=${uuid(Corpus.id[GrantTag]("grant/G-eve-r1"))}".update.run)
      after <- get(f, url, t("alice")).map(x => json(x._2).hcursor.get[Vector[Json]]("grants").toOption.get)
      _ = assert(after.exists(g => g.hcursor.get[String]("state").toOption.contains("revoked")), after.toString)
    yield () }.unsafeRunSync()
  }

  test("access explanations name each basis to the principal and to access managers only, without project names") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      explain = (who: String, subject: String, d: DatasetId, r: Option[ReleaseId], p: String) =>
        get(f, s"/v1/datasets/${d.render}/access?principal=${Corpus.principal(subject).render}" + r.fold("")(x => s"&release=${x.render}") + s"&permission=$p", t(who))
      self <- explain("eve", "eve", restricted, Some(rel("D-restricted", "R1")), "ReadMetadata")
      byManager <- explain("alice", "eve", restricted, Some(rel("D-restricted", "R1")), "ReadMetadata")
      role <- explain("alice", "bob", open, None, "ReadMetadata")
      _ = assertEquals(self, byManager)
      selfJson = json(self._2).hcursor
      _ = assertEquals(selfJson.get[Boolean]("allowed").toOption, Some(true))
      _ = assertEquals(selfJson.downField("bases").downArray.get[String]("kind").toOption, Some("grant"))
      roleJson = json(role._2).hcursor.downField("bases").downArray
      _ = assertEquals((roleJson.get[String]("kind").toOption, roleJson.get[String]("role").toOption), (Some("project-role"), Some("reader")))
      _ = assert(!role._2.contains("P-memory") && !role._2.contains("issuer"), role._2)
      // A reader cannot ask about others; a manager cannot ask about someone unknown on the dataset.
      peer <- explain("bob", "eve", restricted, Some(rel("D-restricted", "R1")), "ReadMetadata")
      stranger <- explain("alice", "outsider", restricted, None, "ReadMetadata")
      _ = assertEquals(Vector(peer, stranger).distinct, Vector(Status.NotFound -> """{"error":"not_found"}"""))
    yield () }.unsafeRunSync()
  }
