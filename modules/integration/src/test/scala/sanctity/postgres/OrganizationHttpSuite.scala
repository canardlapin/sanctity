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
import doobie.implicits.*
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
    tokens <- Vector("alice", "bob", "carol", "dan", "outsider", "sam").traverse(who =>
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

  test("project contributors create datasets they then steward; readers, lab admins, unknown custodians and archived projects cannot") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      project <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice")).map(_._2)
      url = s"/v1/projects/${memory.render}/datasets"
      body = (custodian: String, generation: Long) => Json.obj("custodian" -> Json.fromString(custodian),
        "expected" -> Json.obj(s"project:${memory.render}" -> Json.fromLong(generation)))
      custodian = f.system.dataset.custodian.render
      made <- call(f, Method.POST, url, t("dan"), Some(body(custodian, gen(project))), Some("d-1"))
      _ = assertEquals(made._1, Status.Created)
      dataset = made._2.hcursor.downField("created").get[String]("dataset").toOption.get
      retried <- call(f, Method.POST, url, t("dan"), Some(body(custodian, gen(project))), Some("d-1"))
      _ = assertEquals(retried._2.hcursor.downField("created").get[String]("dataset").toOption, Some(dataset))
      placement <- call(f, Method.GET, s"/v1/datasets/$dataset/placement", t("dan")).map(_._2)
      _ = assertEquals(placement.hcursor.get[String]("policy").toOption, Some("ExplicitOnly"))
      _ = assertEquals(placement.hcursor.downField("stewards").downArray.get[String]("principal").toOption, Some(Corpus.principal("dan").render))
      current <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice")).map(x => gen(x._2))
      reader <- call(f, Method.POST, url, t("bob"), Some(body(custodian, current)), Some("d-2"))
      labAdmin <- call(f, Method.POST, url, t("carol"), Some(body(custodian, current)), Some("d-3"))
      _ = assertEquals(Vector(reader, labAdmin).distinct, Vector(Status.NotFound -> notFound))
      unknown <- call(f, Method.POST, url, t("dan"), Some(body(java.util.UUID.randomUUID().toString, current)), Some("d-4"))
      _ = assertEquals(unknown._1, Status.BadRequest)
      lab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("alice")).map(x => gen(x._2))
      archived <- call(f, Method.POST, s"/v1/projects/${memory.render}/archive", t("alice"), Some(Json.obj("archived" -> Json.True,
        "expected" -> Json.obj(s"project:${memory.render}" -> Json.fromLong(current), s"lab:${labA.render}" -> Json.fromLong(lab)))), Some("a-1"))
      _ = assertEquals(archived._1, Status.Ok)
      after <- call(f, Method.GET, s"/v1/projects/${memory.render}", t("alice")).map(x => gen(x._2))
      refused <- call(f, Method.POST, url, t("dan"), Some(body(custodian, after)), Some("d-5"))
      _ = assertEquals(refused._1, Status.Conflict)
    yield () }.unsafeRunSync()
  }

  private def placementOf(f: PgFixture, t: Map[String, String], who: String, d: DatasetId): IO[Json] =
    call(f, Method.GET, s"/v1/datasets/${d.render}/placement", t(who)).map(_._2)
  private def projectGen(f: PgFixture, t: Map[String, String], who: String, p: String): IO[Long] =
    call(f, Method.GET, s"/v1/projects/$p", t(who)).map(x => gen(x._2))
  /** carol (L-A admin) creates P-new with dan as its steward. */
  private def destination(f: PgFixture, t: Map[String, String]): IO[String] = for
    lab <- call(f, Method.GET, s"/v1/labs/${labA.render}", t("carol")).map(x => gen(x._2))
    made <- call(f, Method.POST, s"/v1/labs/${labA.render}/projects", t("carol"), Some(Json.obj("name" -> Json.fromString("P-new"),
      "steward" -> Json.fromString(Corpus.principal("dan").render), "expected" -> Json.obj(s"lab:${labA.render}" -> Json.fromLong(lab)))), Some("new-project"))
  yield made._2.hcursor.downField("created").get[String]("project").toOption.get

  test("stewardship transfer is atomic: one active steward before and after, retries do not transfer twice") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      place <- placementOf(f, t, "alice", restricted)
      from = place.hcursor.downField("stewards").downArray
      fromId = from.get[String]("id").toOption.get
      expected = Json.obj(s"stewardship:$fromId" -> Json.fromLong(gen(from.focus.get)), s"placement:${restricted.render}" -> Json.fromLong(gen(place)))
      body = Json.obj("principal" -> Json.fromString(Corpus.principal("dan").render), "expected" -> expected)
      byReader <- call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("bob"), Some(body), Some("t-0"))
      _ = assertEquals(byReader, Status.NotFound -> notFound)
      moved <- call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("alice"), Some(body), Some("t-1"))
      _ = assertEquals(moved._1, Status.Created)
      // A retry reauthorizes first (ADR 001): the former steward no longer holds authority, so it is denied, and nothing transfers twice.
      retried <- call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("alice"), Some(body), Some("t-1"))
      _ = assertEquals(retried, Status.NotFound -> notFound)
      after <- placementOf(f, t, "dan", restricted)
      active = after.hcursor.downField("stewards").focus.get.asArray.get.filter(_.hcursor.get[String]("status").toOption.contains("active"))
      _ = assertEquals(active.flatMap(_.hcursor.get[String]("principal").toOption), Vector(Corpus.principal("dan").render))
      aliceNow <- call(f, Method.GET, s"/v1/datasets/${restricted.render}/placement", t("alice"))
      _ = assertEquals(aliceNow, Status.NotFound -> notFound)
      again <- call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("dan"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("bob").render),
        "expected" -> Json.obj(s"stewardship:$fromId" -> Json.fromLong(gen(from.focus.get) + 1), s"placement:${restricted.render}" -> Json.fromLong(gen(after))))), Some("t-2"))
      _ = assertEquals(again._1, Status.Conflict)
    yield () }.unsafeRunSync()
  }

  test("a dataset move needs the dataset, source and destination stewards; it keeps releases, grants and stewards and ends source inheritance") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      target <- destination(f, t)
      before <- call(f, Method.GET, s"/v1/datasets/${open.render}/releases/${Corpus.release("D-open", "R2").render}", t("alice")).map(_._2)
      bobBefore <- f.system.catalog.check(AccessContext(Corpus.principal("bob")), open, Action.ReadMetadata)
      _ = assert(bobBefore.isRight, "bob reads D-open through P-memory inheritance")
      place <- placementOf(f, t, "alice", open)
      expected = (p: Long, src: Long, dst: Long) => Json.obj(s"placement:${open.render}" -> Json.fromLong(p),
        s"project:${memory.render}" -> Json.fromLong(src), s"project:$target" -> Json.fromLong(dst))
      src <- projectGen(f, t, "alice", memory.render)
      dst <- projectGen(f, t, "dan", target)
      requested <- call(f, Method.POST, s"/v1/datasets/${open.render}/moves", t("alice"), Some(Json.obj("destination" -> Json.fromString(target),
        "keepLink" -> Json.True, "expected" -> expected(gen(place), src, dst))), Some("mv-1"))
      _ = assertEquals(requested._1, Status.Created)
      move = requested._2.hcursor.downField("created").get[String]("move").toOption.get
      view <- call(f, Method.GET, s"/v1/moves/$move", t("alice")).map(_._2)
      _ = assertEquals(view.hcursor.downField("approvals").keys.map(_.toSet), Some(Set("DatasetSteward", "SourceSteward")))
      _ = assert(view.hcursor.downField("preview").downField("lost").focus.exists(_.asArray.exists(_.exists(_.hcursor.get[String]("principal").toOption.contains(Corpus.principal("bob").render)))), view.noSpaces)
      _ = assertEquals(view.hcursor.downField("preview").get[Int]("survivingGrants").toOption, Some(0))
      stranger <- call(f, Method.GET, s"/v1/moves/$move", t("bob"))
      _ = assertEquals(stranger, Status.NotFound -> notFound)
      early <- call(f, Method.POST, s"/v1/moves/$move/commit", t("alice"), Some(Json.obj("expected" -> Json.obj(s"move:$move" -> Json.fromLong(gen(view))))), Some("mv-2"))
      _ = assertEquals(early._1, Status.Conflict)
      approved <- call(f, Method.POST, s"/v1/moves/$move/approve", t("dan"), Some(Json.obj("expected" -> Json.obj(s"move:$move" -> Json.fromLong(gen(view))))), Some("mv-3"))
      _ = assertEquals(approved._1, Status.Ok)
      view2 <- call(f, Method.GET, s"/v1/moves/$move", t("dan")).map(_._2)
      committed <- call(f, Method.POST, s"/v1/moves/$move/commit", t("dan"), Some(Json.obj("expected" -> Json.obj(s"move:$move" -> Json.fromLong(gen(view2))))), Some("mv-4"))
      _ = assertEquals(committed._1, Status.Ok)
      after <- placementOf(f, t, "alice", open)
      _ = assertEquals((after.hcursor.get[String]("project").toOption, after.hcursor.get[String]("policy").toOption), (Some(target), Some("ExplicitOnly")))
      bobAfter <- f.system.catalog.check(AccessContext(Corpus.principal("bob")), open, Action.ReadMetadata)
      _ = assert(bobAfter.isLeft, "source-project inheritance ends with the move")
      release <- call(f, Method.GET, s"/v1/datasets/${open.render}/releases/${Corpus.release("D-open", "R2").render}", t("alice")).map(_._2)
      _ = assertEquals(release.hcursor.get[String]("manifest").toOption, before.hcursor.get[String]("manifest").toOption)
      links <- sql"SELECT count(*) FROM organization_link WHERE dataset_id=${open.render}::uuid AND project_id=${memory.render}::uuid"
        .query[Long].unique.transact(f.database.transactor)
      _ = assertEquals(links, 1L)
    yield () }.unsafeRunSync()
  }

  test("a concurrent project change invalidates a pending move, and only the operator recovers a dataset with no steward") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      target <- destination(f, t)
      place <- placementOf(f, t, "alice", restricted)
      src <- projectGen(f, t, "alice", memory.render)
      dst <- projectGen(f, t, "dan", target)
      requested <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/moves", t("alice"), Some(Json.obj("destination" -> Json.fromString(target),
        "expected" -> Json.obj(s"placement:${restricted.render}" -> Json.fromLong(gen(place)), s"project:${memory.render}" -> Json.fromLong(src),
          s"project:$target" -> Json.fromLong(dst)))), Some("mv-a"))
      move = requested._2.hcursor.downField("created").get[String]("move").toOption.get
      // alice adds a participant to the source project after the request: the frozen preview is stale.
      _ <- call(f, Method.POST, s"/v1/projects/${memory.render}/participants", t("alice"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("outsider").render),
        "role" -> Json.fromString("reader"), "expected" -> Json.obj(s"project:${memory.render}" -> Json.fromLong(src)))), Some("mv-b"))
      view <- call(f, Method.GET, s"/v1/moves/$move", t("dan")).map(_._2)
      stale <- call(f, Method.POST, s"/v1/moves/$move/approve", t("dan"), Some(Json.obj("expected" -> Json.obj(s"move:$move" -> Json.fromLong(gen(view))))), Some("mv-c"))
      _ = assertEquals(stale._1, Status.Conflict)
      // Recovery: disabling alice leaves D-restricted with no eligible steward; only the operator connection can appoint one.
      snapshot <- f.database.organization.snapshot
      alice = Corpus.principal("alice")
      _ <- checked(f.database.organization.execute(f.system.context, OrganizationMutation(IdempotencyKey.from("disable-alice").toOption.get,
        Map(OrganizationRef.Principal(alice) -> snapshot.generation(OrganizationRef.Principal(alice)).get), OrganizationCommand.DisablePrincipal(alice))))
      after <- f.database.organization.snapshot
      recover = (key: String) => OrganizationMutation(IdempotencyKey.from(key).toOption.get,
        Map(OrganizationRef.Placement(restricted) -> after.generation(OrganizationRef.Placement(restricted)).get),
        OrganizationCommand.RecoverSteward(restricted, Id[DatasetStewardshipTag](java.util.UUID.randomUUID()), Corpus.principal("sam"),
          OrganizationDescription.from("Steward disabled; custodian approved sam").toOption.get))
      byUser <- checked(credentials(f).authenticate(t("sam"))).flatMap(ctx => f.database.organization.execute(ctx, recover("recover-sam")))
      _ = assertEquals(byUser, Left(Problem.Denied))
      recovered <- f.database.organization.execute(f.system.context, recover("recover-op"))
      _ = assert(recovered.isRight, recovered.toString)
      samSees <- placementOf(f, t, "sam", restricted)
      _ = assert(samSees.hcursor.downField("stewards").focus.exists(_.asArray.exists(_.exists(_.hcursor.get[String]("principal").toOption.contains(Corpus.principal("sam").render)))))
      refused <- f.database.organization.execute(f.system.context, recover("recover-again").copy(expected = Map(OrganizationRef.Placement(restricted) ->
        after.generation(OrganizationRef.Placement(restricted)).get.next.toOption.get)))
      _ = assert(refused.left.exists(_.isInstanceOf[Problem.Conflict]), refused.toString)
    yield () }.unsafeRunSync()
  }

  test("concurrent transfers of one stewardship resolve to exactly one; a project steward cannot make themselves dataset steward") {
    PostgresFixture.resource.use { f => for
      t <- world(f)
      place <- placementOf(f, t, "alice", restricted)
      from = place.hcursor.downField("stewards").downArray
      fromId = from.get[String]("id").toOption.get
      expected = Json.obj(s"stewardship:$fromId" -> Json.fromLong(gen(from.focus.get)), s"placement:${restricted.render}" -> Json.fromLong(gen(place)))
      to = (who: String) => Json.obj("principal" -> Json.fromString(Corpus.principal(who).render), "expected" -> expected)
      raced <- (call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("alice"), Some(to("dan")), Some("race-a")),
        call(f, Method.POST, s"/v1/stewardships/$fromId/transfer", t("alice"), Some(to("carol")), Some("race-b"))).parTupled
      // The policy lock serializes them. The loser is refused: 409 if it saw the stale generation, or the uniform 404 once
      // reauthorization finds that alice no longer holds the stewardship it names.
      statuses = Vector(raced._1._1, raced._2._1)
      _ = assert(statuses.count(_ == Status.Created) == 1 && statuses.exists(Set(Status.Conflict, Status.NotFound)), statuses.toString)
      after <- sql"SELECT count(*) FROM organization_stewardship WHERE dataset_id=${restricted.render}::uuid AND status='Active'"
        .query[Long].unique.transact(f.database.transactor)
      _ = assertEquals(after, 1L)
      // sam stewards the project but not this ExplicitOnly dataset: no self-appointment, no inheritance switch.
      current <- placementOf(f, t, (if raced._1._1 == Status.Created then "dan" else "carol"), restricted)
      selfAppoint <- call(f, Method.POST, s"/v1/datasets/${open.render}/stewards", t("sam"), Some(Json.obj("principal" -> Json.fromString(Corpus.principal("sam").render),
        "expected" -> Json.obj(s"placement:${open.render}" -> Json.fromLong(1L)))), Some("esc-1"))
      project <- projectGen(f, t, "sam", memory.render)
      inherit <- call(f, Method.POST, s"/v1/datasets/${restricted.render}/inheritance", t("sam"), Some(Json.obj("policy" -> Json.fromString("ProjectMembers"),
        "previewDigest" -> Json.fromString("0" * 64), "expected" -> Json.obj(s"placement:${restricted.render}" -> Json.fromLong(gen(current)),
          s"project:${memory.render}" -> Json.fromLong(project)))), Some("esc-2"))
      _ = assertEquals(Vector(selfAppoint, inherit).distinct, Vector(Status.NotFound -> notFound))
    yield () }.unsafeRunSync()
  }

