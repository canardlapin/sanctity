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

/** F05.1: authorized, searchable, policy-bound directory pages over HTTP and PostgreSQL (ADR 002 corpus). */
final class DirectorySuite extends FunSuite:
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
  private def ids(body: String, field: String = "id"): Vector[String] =
    json(body).hcursor.downField("items").focus.flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.hcursor.get[String](field).toOption)
  private def gen(s: String): Long = json(s).hcursor.get[Long]("generation").toOption.get
  private val memory = Corpus.projectId("P-memory")
  private val methods = Corpus.projectId("P-methods")
  private val open = Corpus.dataset("D-open")
  private val restricted = Corpus.dataset("D-restricted")
  private val notFound = """{"error":"not_found"}"""

  private def world(f: PgFixture): IO[Map[String, String]] = for
    _ <- AuthoritySeed.seed(f, Corpus.Scenario(Corpus.state, Corpus.now, Corpus.admission("alice"), Policy.ListSharedReleases, Json.Null))
    tokens <- Vector("alice", "bob", "carol", "dan", "eve", "outsider").traverse(who =>
      checked(credentials(f).issue(Corpus.principal(who), who, 3600)).map(t => who -> t.secret))
  yield tokens.toMap

  /** dan creates a labelled ExplicitOnly dataset in P-memory; optionally opens it to project members. */
  private def dataset(f: PgFixture, t: Map[String, String], label: String, key: String, inherit: Boolean): IO[String] = for
    project <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("dan")).map(x => gen(x._2))
    made <- call(f, Method.POST, s"/v1/projects/${memory.render}/datasets", t("dan"), Some(Json.obj("custodian" -> Json.fromString(f.system.dataset.custodian.render),
      "label" -> Json.fromString(label), "expected" -> Json.obj(s"project:${memory.render}" -> Json.fromLong(project)))), Some(key))
    _ = assertEquals(made._1, Status.Created, made._2)
    d = json(made._2).hcursor.downField("created").get[String]("dataset").toOption.get
    _ <- if !inherit then IO.unit else openToMembers(f, t, d, key + "-inherit")
  yield d

  private def openToMembers(f: PgFixture, t: Map[String, String], d: String, key: String): IO[Unit] = for
    preview <- call(f, Method.GET, s"/v1/datasets/$d/inheritance", t("dan")).map(x => json(x._2))
    place <- call(f, Method.GET, s"/v1/datasets/$d/placement", t("dan")).map(x => gen(x._2))
    project <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("dan")).map(x => gen(x._2))
    set <- call(f, Method.POST, s"/v1/datasets/$d/inheritance", t("dan"), Some(Json.obj("policy" -> Json.fromString("ProjectMembers"),
      "previewDigest" -> Json.fromString(preview.hcursor.get[String]("digest").toOption.get),
      "expected" -> Json.obj(s"placement:$d" -> Json.fromLong(place), s"project:${memory.render}" -> Json.fromLong(project)))), Some(key))
    _ = assertEquals(set._1, Status.Ok, set._2)
  yield ()

  test("directories list only what the caller may discover: labs, projects, datasets and links") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      aliceProjects <- call(f, Method.GET, "/v1/projects", t("alice")).map(x => ids(x._2))
      bobProjects <- call(f, Method.GET, "/v1/projects", t("bob")).map(x => ids(x._2))
      outsiderProjects <- call(f, Method.GET, "/v1/projects", t("outsider")).map(x => ids(x._2))
      _ = assertEquals(aliceProjects, Vector(memory.render))
      _ = assertEquals(bobProjects, Vector(memory.render))
      _ = assertEquals(outsiderProjects, Vector(methods.render))
      bobLabs <- call(f, Method.GET, "/v1/labs", t("bob")).map(x => ids(x._2))
      eveLabs <- call(f, Method.GET, "/v1/labs", t("eve")).map(_._2)
      _ = assertEquals(bobLabs, Vector(Corpus.lab("L-B").render))
      _ = assertEquals(json(eveLabs).hcursor.get[Int]("total").toOption, Some(0))
      // bob reads D-open through P-memory; D-restricted is ExplicitOnly and absent, not merely unnamed.
      bobData <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets", t("bob")).map(_._2)
      aliceData <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets", t("alice")).map(x => ids(x._2))
      _ = assertEquals(ids(bobData), Vector(open.render))
      _ = assertEquals(json(bobData).hcursor.get[Int]("total").toOption, Some(1))
      _ = assertEquals(aliceData.toSet, Set(open.render, restricted.render))
      // D-open is linked into P-methods, but a link grants outsider nothing; bob cannot list a project he cannot see.
      outsiderData <- call(f, Method.GET, s"/v1/projects/${methods.render}/datasets", t("outsider")).map(_._2)
      bobMethods <- call(f, Method.GET, s"/v1/projects/${methods.render}/datasets", t("bob"))
      _ = assertEquals(ids(outsiderData), Vector.empty)
      _ = assertEquals(bobMethods, Status.NotFound -> notFound)
    yield () }.unsafeRunSync()
  }

  test("stewards label datasets; search sees only visible labels; pages are bounded and cursors are bound to their caller") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      place <- call(f, Method.GET, s"/v1/datasets/${open.render}/placement", t("alice")).map(x => gen(x._2))
      readerRename <- call(f, Method.POST, s"/v1/datasets/${open.render}/rename", t("bob"), Some(Json.obj("label" -> Json.fromString("Mine"),
        "expected" -> Json.obj(s"placement:${open.render}" -> Json.fromLong(place)))), Some("l-0"))
      _ = assertEquals(readerRename, Status.NotFound -> notFound)
      renamed <- call(f, Method.POST, s"/v1/datasets/${open.render}/rename", t("alice"), Some(Json.obj("label" -> Json.fromString("Memory pilot, open"),
        "expected" -> Json.obj(s"placement:${open.render}" -> Json.fromLong(place)))), Some("l-1"))
      _ = assertEquals(renamed._1, Status.Ok, renamed._2)
      labels <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?q=pilot", t("bob")).map(x => ids(x._2, "label"))
      _ = assertEquals(labels, Vector("Memory pilot, open"))
      // D-restricted gets a label bob cannot see; searching for it finds nothing and counts nothing.
      rPlace <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/placement", t("alice")).map(x => gen(x._2))
      _ <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/rename", t("alice"), Some(Json.obj("label" -> Json.fromString("Secret cohort"),
        "expected" -> Json.obj(s"placement:${restricted.render}" -> Json.fromLong(rPlace)))), Some("l-2"))
      secret <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?q=secret", t("bob")).map(_._2)
      _ = assertEquals((ids(secret), json(secret).hcursor.get[Int]("total").toOption), (Vector.empty, Some(0)))
      first <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1", t("alice")).map(_._2)
      cursor = json(first).hcursor.get[String]("next").toOption.get
      second <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1&cursor=$cursor", t("alice")).map(_._2)
      _ = assertEquals((ids(first) ++ ids(second)).toSet, Set(open.render, restricted.render))
      _ = assert(json(second).hcursor.downField("next").focus.exists(_.isNull))
      borrowed <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1&cursor=$cursor", t("bob"))
      otherQuery <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=2&cursor=$cursor", t("alice"))
      tooMany <- call(f, Method.GET, "/v1/projects?limit=101", t("alice"))
      _ = assertEquals(Vector(borrowed._1, otherQuery._1, tooMany._1), Vector(Status.BadRequest, Status.BadRequest, Status.BadRequest))
    yield () }.unsafeRunSync()
  }

  test("a hidden change leaves the next page valid and identical; a visible change makes it an explicit restart") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      _ <- dataset(f, t, "Zeta sessions", "z", inherit = true)
      first <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1", t("bob")).map(_._2)
      _ = assertEquals(json(first).hcursor.get[Int]("total").toOption, Some(2))
      cursor = json(first).hcursor.get[String]("next").toOption.get
      before <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1&cursor=$cursor", t("bob"))
      // dan adds an ExplicitOnly dataset bob cannot discover: the continuation is unchanged, byte for byte.
      hidden <- dataset(f, t, "Hidden pilot", "h", inherit = false)
      after <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1&cursor=$cursor", t("bob"))
      _ = assertEquals(after, before)
      _ = assertEquals(before._1, Status.Ok)
      // Opening it to project members changes what bob can see, so the old cursor must restart.
      _ <- openToMembers(f, t, hidden, "h-open")
      restart <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets?limit=1&cursor=$cursor", t("bob"))
      _ = assertEquals(restart._1, Status.Conflict)
      fresh <- call(f, Method.GET, s"/v1/projects/${memory.render}/datasets", t("bob")).map(_._2)
      _ = assertEquals(json(fresh).hcursor.get[Int]("total").toOption, Some(3))
    yield () }.unsafeRunSync()
  }

  test("shares list the caller's exact scopes with the dataset label, never a project or head; Discover shares can be issued over HTTP") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      eve <- call(f, Method.GET, "/v1/me/shares", t("eve")).map(_._2)
      entry = json(eve).hcursor.downField("items").downArray
      _ = assertEquals(entry.get[String]("release").toOption, Some(Corpus.release("D-restricted", "R1").render))
      _ = assert(!eve.contains(memory.render) && !eve.contains(Corpus.release("D-restricted", "R2").render), eve)
      none <- call(f, Method.GET, "/v1/me/shares", t("outsider")).map(x => ids(x._2, "dataset"))
      _ = assertEquals(none, Vector.empty)
      issued <- call(f, Method.POST, s"/v1/datasets/${open.render}/grants", t("alice"), Some(Json.obj("recipient" -> Json.fromString(Corpus.principal("outsider").render),
        "release" -> Json.Null, "actions" -> Json.arr(Json.fromString("Discover"), Json.fromString("ReadMetadata")),
        "expiresAt" -> Json.fromString(java.time.Instant.now.plusSeconds(3600).toString))))
      _ = assertEquals(issued._1, Status.Created, issued._2)
      shared <- call(f, Method.GET, "/v1/me/shares", t("outsider")).map(_._2)
      _ = assertEquals(ids(shared, "dataset"), Vector(open.render))
      _ = assert(json(shared).hcursor.downField("items").downArray.downField("release").focus.exists(_.isNull))
    yield () }.unsafeRunSync()
  }

  test("organization events written before dataset labels still decode") {
    val legacy = """{"version":1,"value":{"key":"legacy","expected":[],"command":{"PlaceNewDataset":{"dataset":"%s","project":"%s","stewardship":"%s"}}}}"""
      .format(open.render, memory.render, java.util.UUID.randomUUID())
    import OrganizationCodecs.given
    val decoded = JsonCodecs.decode[OrganizationMutation](legacy)
    assert(decoded.exists(_.command == OrganizationCommand.PlaceNewDataset(open, memory, decoded.toOption.get.command.asInstanceOf[OrganizationCommand.PlaceNewDataset].stewardship, None)), decoded.toString)
  }
