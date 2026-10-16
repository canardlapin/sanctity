package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
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
import scala.concurrent.duration.*

/** F02.3.2: governed organization commands and authorized organization reads over HTTP and PostgreSQL,
  * on the ADR 002 corpus (alice: L-A admin, P-memory and dataset steward; carol: L-A admin; bob: L-B member and
  * P-memory reader; dan: P-memory contributor; outsider: P-methods reader).
  */
final class OrganizationHttpSuite extends FunSuite:
  override val munitTimeout = 5.minutes
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, f.system.repository, credentials(f),
    organization = Some(f.database.organization)).app
  private def call(f: PgFixture, method: Method, target: String, token: String, body: Option[Json] = None, key: Option[String] = None): IO[(Status, Json)] =
    val base = Request[IO](method, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    val keyed = key.fold(base)(k => base.putHeaders(Header.Raw(CIString("Idempotency-Key"), k)))
    val req = body.fold(keyed)(j => keyed.withEntity(j.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
    api(f).run(req).flatMap(r => r.as[String].map(t => r.status -> (if t.isEmpty then Json.Null else new JawnParser().parse(t).toOption.get)))
  private def gen(j: Json): Long = j.hcursor.get[Long]("generation").toOption.get
  private val notFound = Json.obj("error" -> Json.fromString("not_found"))

  private val labA = Corpus.lab("L-A")
  private val memory = Corpus.projectId("P-memory")
  private val open = Corpus.dataset("D-open")
  private val restricted = Corpus.dataset("D-restricted")

  private def world(f: PgFixture): IO[Map[String, String]] = for
    _ <- AuthoritySeed.seed(f, Corpus.Scenario(Corpus.state, Corpus.now, Corpus.admission("alice"), Policy.ListSharedReleases, Json.Null))
    tokens <- Vector("alice", "bob", "carol", "dan", "outsider").traverse(who =>
      checked(credentials(f).issue(Corpus.principal(who), who, 3600)).map(t => who -> t.secret))
  yield tokens.toMap

  test("organization reads disclose rosters only to administrators and stewards, and nothing to strangers") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      adminLab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("carol"))
      _ = assertEquals(adminLab._1, Status.Ok)
      _ = assert(adminLab._2.hcursor.downField("roster").focus.exists(_.isArray), adminLab._2.noSpaces)
      strangerLab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("bob"))
      contributorLab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("dan"))
      stewardProject <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice"))
      readerProject <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("bob"))
      outsiderProject <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("outsider"))
      _ = assert(stewardProject._2.hcursor.downField("roster").focus.exists(_.isArray))
      _ = assert(readerProject._1 == Status.Ok && readerProject._2.hcursor.downField("roster").focus.exists(_.isNull), readerProject._2.noSpaces)
      placement <- call(f, Method.GET, s"/v1/datasets/${open.render}/placement", t("alice"))
      readerPlacement <- call(f, Method.GET, s"/v1/datasets/${open.render}/placement", t("bob"))
      _ = assertEquals(placement._2.hcursor.get[String]("policy").toOption, Some("ProjectMembers"))
      _ = assertEquals(Vector(strangerLab, contributorLab, outsiderProject, readerPlacement).distinct, Vector(Status.NotFound -> notFound))
    yield () }.unsafeRunSync()
  }

  test("a lab administrator creates a project; preconditions, stale generations, retries and strangers are handled") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      lab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("carol")).map(x => gen(x._2))
      url = s"/v1/labs/${labA.render}/projects"
      body = Json.obj("name" -> Json.fromString("Imaging methods"), "expected" -> Json.obj(s"lab:${labA.render}" -> Json.fromLong(lab)))
      noKey <- call(f, Method.POST, url, t("carol"), Some(body))
      noExpected <- call(f, Method.POST, url, t("carol"), Some(Json.obj("name" -> Json.fromString("x"))), Some("p-1"))
      _ = assertEquals(Vector(noKey._1, noExpected._1), Vector(Status.PreconditionRequired, Status.PreconditionRequired))
      made <- call(f, Method.POST, url, t("carol"), Some(body), Some("p-1"))
      _ = assertEquals(made._1, Status.Created)
      project = made._2.hcursor.downField("created").get[String]("project").toOption.get
      _ = assertEquals(made._2.hcursor.get[String]("durability").toOption, Some("LocalOnly"))
      retried <- call(f, Method.POST, url, t("carol"), Some(body), Some("p-1"))
      _ = assertEquals(retried._2.hcursor.get[Long]("sequence").toOption, made._2.hcursor.get[Long]("sequence").toOption)
      _ = assertEquals(retried._2.hcursor.downField("created").get[String]("project").toOption, Some(project))
      stale <- call(f, Method.POST, url, t("carol"), Some(body), Some("p-2"))
      _ = assertEquals(stale._1, Status.Conflict)
      stranger <- call(f, Method.POST, url, t("bob"), Some(body), Some("p-3"))
      _ = assertEquals(stranger, Status.NotFound -> notFound)
      // The creator becomes the project's steward and can see its roster; lab administration alone grants no data rights.
      view <- call(f, Method.GET, s"/v1/projects/$project", t("carol"))
      _ = assertEquals(view._2.hcursor.downField("roster").downArray.get[String]("role").toOption, Some("steward"))
    yield () }.unsafeRunSync()
  }

  test("memberships, participations and stewardship are appointed and ended by the right people only") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      lab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("carol")).map(x => gen(x._2))
      member <- call(f, Method.POST, s"/v1/labs/${labA.render}/members", t("carol"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("bob").render),
        "role" -> Json.fromString("member"), "expected" -> Json.obj(s"lab:${labA.render}" -> Json.fromLong(lab)))), Some("m-1"))
      _ = assertEquals(member._1, Status.Created)
      membership = member._2.hcursor.downField("created").get[String]("membership").toOption.get
      projectGen <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice")).map(x => gen(x._2))
      readerTries <- call(f, Method.POST, s"/v1/projects/${memory.render}/participants", t("bob"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("outsider").render),
        "role" -> Json.fromString("reader"), "expected" -> Json.obj(s"project:${memory.render}" -> Json.fromLong(projectGen)))), Some("r-0"))
      _ = assertEquals(readerTries, Status.NotFound -> notFound)
      added <- call(f, Method.POST, s"/v1/projects/${memory.render}/participants", t("alice"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("outsider").render),
        "role" -> Json.fromString("reader"), "expected" -> Json.obj(s"project:${memory.render}" -> Json.fromLong(projectGen)))), Some("r-1"))
      _ = assertEquals(added._1, Status.Created)
      participation = added._2.hcursor.downField("created").get[String]("participation").toOption.get
      project2 <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice")).map(_._2)
      roleGen = project2.hcursor.downField("roster").focus.get.asArray.get.find(_.hcursor.get[String]("id").toOption.contains(participation)).map(gen).get
      ended <- call(f, Method.POST, s"/v1/participations/$participation/end", t("alice"), Some(Json.obj("expected" -> Json.obj(
        s"participation:$participation" -> Json.fromLong(roleGen), s"project:${memory.render}" -> Json.fromLong(gen(project2))))), Some("r-2"))
      _ = assertEquals(ended._1, Status.Ok)
      lab2 <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("carol")).map(_._2)
      memberGen = lab2.hcursor.downField("roster").focus.get.asArray.get.find(_.hcursor.get[String]("id").toOption.contains(membership)).map(gen).get
      endMember <- call(f, Method.POST, s"/v1/memberships/$membership/end", t("carol"), Some(Json.obj("expected" -> Json.obj(
        s"membership:$membership" -> Json.fromLong(memberGen), s"lab:${labA.render}" -> Json.fromLong(gen(lab2))))), Some("m-2"))
      _ = assertEquals(endMember._1, Status.Ok)
      // Stewardship: the dataset steward appoints dan; a reader cannot; the last steward cannot leave silently.
      place <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/placement", t("alice")).map(_._2)
      byReader <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/stewards", t("bob"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("bob").render),
        "expected" -> Json.obj(s"placement:${restricted.render}" -> Json.fromLong(gen(place))))), Some("s-0"))
      _ = assertEquals(byReader, Status.NotFound -> notFound)
      only = place.hcursor.downField("stewards").downArray
      lastSteward <- call(f, Method.POST, s"/v1/stewardships/${only.get[String]("id").toOption.get}/end", t("alice"), Some(Json.obj("expected" -> Json.obj(
        s"stewardship:${only.get[String]("id").toOption.get}" -> Json.fromLong(gen(only.focus.get)), s"placement:${restricted.render}" -> Json.fromLong(gen(place))))), Some("s-1"))
      _ = assertEquals(lastSteward._1, Status.Conflict)
      appointed <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/stewards", t("alice"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("dan").render),
        "expected" -> Json.obj(s"placement:${restricted.render}" -> Json.fromLong(gen(place))))), Some("s-2"))
      _ = assertEquals(appointed._1, Status.Created)
      after <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/placement", t("dan"))
      _ = assertEquals(after._2.hcursor.downField("stewards").focus.flatMap(_.asArray).map(_.size), Some(2))
    yield () }.unsafeRunSync()
  }

  test("enabling project inheritance requires the exact access-impact preview the steward reviewed") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      preview <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/inheritance", t("alice")).map(_._2)
      digest = preview.hcursor.get[String]("digest").toOption.get
      _ = assert(preview.hcursor.downField("recipients").focus.exists(_.asArray.exists(_.nonEmpty)), preview.noSpaces)
      readerPreview <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/inheritance", t("bob"))
      _ = assertEquals(readerPreview, Status.NotFound -> notFound)
      place <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/placement", t("alice")).map(_._2)
      project <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice")).map(_._2)
      expected = Json.obj(s"placement:${restricted.render}" -> Json.fromLong(gen(place)), s"project:${memory.render}" -> Json.fromLong(gen(project)))
      wrong <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/inheritance", t("alice"), Some(Json.obj("policy" -> Json.fromString("ProjectMembers"),
        "previewDigest" -> Json.fromString("0" * 64), "expected" -> expected)), Some("i-1"))
      _ = assertEquals(wrong._1, Status.Conflict)
      confirmed <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/inheritance", t("alice"), Some(Json.obj("policy" -> Json.fromString("ProjectMembers"),
        "previewDigest" -> Json.fromString(digest), "expected" -> expected)), Some("i-2"))
      _ = assertEquals(confirmed._1, Status.Ok)
      // bob, a P-memory reader, now inherits metadata on the formerly explicit-only dataset.
      bobCanRead <- f.system.catalog.check(AccessContext(Corpus.principal("bob")), restricted, Action.ReadMetadata)
      _ = assert(bobCanRead.isRight, bobCanRead.toString)
    yield () }.unsafeRunSync()
  }
