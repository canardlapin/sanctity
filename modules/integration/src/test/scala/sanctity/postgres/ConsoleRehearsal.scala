package sanctity.postgres

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.{Host, Port}
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.jawn.JawnParser
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import sanctity.app.{ApiServer, CoverageFixture, EarlierBuild, ServiceInterpreters}
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.http.ConsoleAssets
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Test-only entry point: real console/API/PostgreSQL, with browser credentials passed over stdin. */
object ConsoleRehearsal extends IOApp.Simple:
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def ensure(ok: Boolean, message: String): IO[Unit] = IO.raiseUnless(ok)(new IllegalStateException(message))
  private val parser = new JawnParser()
  private val label = "Console rehearsal n-back pilot"

  def run: IO[Unit] =
    val output = Path.of(sys.env.getOrElse("SANCTITY_CONSOLE_REHEARSAL_OUTPUT",
      throw new IllegalStateException("Run scripts/check-console-backend.sh"))).toAbsolutePath
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val repository = new NativeRepository(f.system.catalog, f.system.store, ServiceInterpreters(f.system.store), ids)
      val credentials = f.database.credentials(ids)
      for
        _ <- ConsoleSuite.source(f.root.resolve("console"))
        _ <- IO.blocking(Files.writeString(f.root.resolve("console/task-nback_bold.json"),
          """{"TaskName":"nback","RepetitionTime":1.5,"EchoTime":0.03,"FlipAngle":52}""" + "\n"))
        proposal <- checked(repository.importNative(f.system.context, f.system.dataset.id,
          new NativeDirectorySource[IO](f.root.resolve("console"))))
        initial <- checked(repository.accept(f.system.context, proposal.candidate.id,
          IdempotencyKey.from("console-rehearsal-import").toOption.get))
        // Record a release before coverage levels were part of the interpretation, then serve it with today's rules.
        legacy <- f.system.catalog.create(f.system.context, f.system.dataset.custodian)
        earlier = new NativeRepository(f.system.catalog, f.system.store,
          new EarlierBuild(ServiceInterpreters(f.system.store), EarlierBuild.withoutLevels), ids)
        legacySource = f.root.resolve("legacy-reading")
        _ <- CoverageFixture.described(legacySource)
        oldPreview <- checked(earlier.importNative(f.system.context, legacy.id, new NativeDirectorySource[IO](legacySource)))
        oldRelease <- checked(earlier.accept(f.system.context, oldPreview.candidate.id, IdempotencyKey.from("console-earlier-reading").toOption.get))
        colleague <- ids.next[PrincipalTag]
        recipient <- ids.next[PrincipalTag]
        _ <- checked(f.system.catalog.grant(f.system.context, f.system.dataset.id, colleague, None,
          Set(Action.ReadMetadata, Action.ReadContent, Action.Propose, Action.Accept)))
        _ <- checked(f.system.catalog.grant(f.system.context, legacy.id, colleague,
          Some(oldRelease.release.release), Set(Action.ReadMetadata)))
        // Provision a known recipient through ordinary authority operations, then remove all access.
        bootstrap <- checked(f.system.catalog.grant(f.system.context, f.system.dataset.id, recipient,
          Some(initial.release.release), Set(Action.ReadMetadata)))
        _ <- checked(f.system.catalog.revoke(f.system.context, f.system.dataset.id, bootstrap.id))
        ownerToken <- checked(credentials.issue(f.system.context.principal, "console rehearsal curator", 600))
        colleagueToken <- checked(credentials.issue(colleague, "console rehearsal colleague", 600))
        recipientToken <- checked(credentials.issue(recipient, "console rehearsal recipient", 600))
        // Organization through the operator path: the dataset lands in a legacy project its curator stewards.
        report <- f.database.organization.preview(f.system.context.principal)
        _ <- f.database.organization.bootstrap(f.system.context.principal, PostgresOrganizationCatalog.previewDigest(report))
        api = ApiServer.routes(f.database, f.system.store, ids)
        call = (method: Method, target: String, body: Option[Json]) =>
          val base = Request[IO](method, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer ${ownerToken.secret}"),
            Header.Raw(CIString("Idempotency-Key"), "console-rehearsal-label"))
          api.run(body.fold(base)(j => base.withEntity(j.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))))
            .flatMap(r => r.as[String].map(t => r.status -> parser.parse(t).getOrElse(Json.Null)))
        dataset = f.system.dataset.id.render
        placement <- call(Method.GET, s"/v1/datasets/$dataset/placement", None)
        generation = placement._2.hcursor.get[Long]("generation").toOption
        renamed <- call(Method.POST, s"/v1/datasets/$dataset/rename", Some(Json.obj("label" -> Json.fromString(label),
          "expected" -> Json.obj(s"placement:$dataset" -> Json.fromLong(generation.getOrElse(-1L))))))
        _ <- ensure(renamed._1 == Status.Ok, s"dataset label not set: ${renamed._2.noSpaces}")
        projects <- call(Method.GET, "/v1/projects", None)
        labs <- call(Method.GET, "/v1/labs", None)
        project = projects._2.hcursor.downField("items").downArray
        lab = labs._2.hcursor.downField("items").downArray
        app = ConsoleAssets.withApi(api)
        _ <- EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(0).get)
          .withHttpApp(app).build.use { server =>
            val config = Json.obj(
              "baseUrl" -> Json.fromString(s"http://127.0.0.1:${server.address.getPort}"),
              "dataset" -> Json.fromString(f.system.dataset.id.render),
              "custodian" -> Json.fromString(f.system.dataset.custodian.render),
              "legacyDataset" -> Json.fromString(legacy.id.render),
              "legacyRelease" -> Json.fromString(oldRelease.release.release.render),
              "legacySidecar" -> Json.fromString(CoverageFixture.eegSidecar),
              "initialRelease" -> Json.fromString(initial.release.release.render),
              "recipient" -> Json.fromString(recipient.render),
              "label" -> Json.fromString(label),
              "project" -> project.downField("id").focus.getOrElse(Json.Null),
              "projectName" -> project.downField("name").focus.getOrElse(Json.Null),
              "labName" -> lab.downField("name").focus.getOrElse(Json.Null),
              "ownerToken" -> Json.fromString(ownerToken.secret),
              "colleagueToken" -> Json.fromString(colleagueToken.secret),
              "recipientToken" -> Json.fromString(recipientToken.secret))
            browser(output, config)
          }
        _ <- verifyPersistence(f, output)
      yield ()
    }.timeout(6.minutes)

  private def browser(output: Path, config: Json): IO[Unit] =
    val log = output.resolve("browser.log")
    Resource.make(IO.blocking {
      new ProcessBuilder("node", "tests/console/rehearsal.js", output.toString)
        .directory(Path.of(sys.env("SANCTITY_CONSOLE_REHEARSAL_ROOT")).toFile)
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
    })(process => IO.blocking {
      if process.isAlive then
        process.destroy()
        if !process.waitFor(5, TimeUnit.SECONDS) then { val _ = process.destroyForcibly() }
    }).use { process =>
      for
        _ <- IO.blocking {
          val input = process.getOutputStream
          try input.write(config.noSpaces.getBytes(StandardCharsets.UTF_8)) finally input.close()
        }
        finished <- IO.interruptibleMany(process.waitFor(240, TimeUnit.SECONDS))
        _ <- IO.blocking(Files.readString(log)).flatMap(IO.print)
        _ <- ensure(finished && process.exitValue() == 0, s"Console browser rehearsal failed; inspect $log")
      yield ()
    }

  /** Query durable rows independently after Chromium and the HTTP server have closed. */
  private def verifyPersistence(f: PgFixture, output: Path): IO[Unit] =
    for
      receipt <- IO.blocking(Files.readString(output.resolve("browser.json"))).flatMap(s => IO.fromEither(parser.parse(s)))
      cursor = receipt.hcursor.downField("persisted")
      head <- IO.fromEither(cursor.get[String]("head")).map(UUID.fromString)
      selection <- IO.fromEither(cursor.get[String]("selection"))
      grant <- IO.fromEither(cursor.get[String]("grant")).map(UUID.fromString)
      dataset = UUID.fromString(f.system.dataset.id.render)
      actualHead <- sql"SELECT head FROM dataset WHERE id=$dataset".query[UUID].unique.transact(f.database.transactor)
      releases <- sql"SELECT count(*) FROM release WHERE dataset_id=$dataset".query[Long].unique.transact(f.database.transactor)
      receipts <- sql"SELECT count(*) FROM acceptance_receipt WHERE dataset_id=$dataset".query[Long].unique.transact(f.database.transactor)
      selected <- sql"SELECT count(*) FROM frozen_selection WHERE id=$selection".query[Long].unique.transact(f.database.transactor)
      revoked <- sql"SELECT revoked_at IS NOT NULL FROM access_grant WHERE id=$grant AND dataset_id=$dataset".query[Boolean].unique.transact(f.database.transactor)
      _ <- ensure(actualHead == head && releases == 5 && receipts == 5 && selected == 1 && revoked,
        "Post-browser database state disagrees with the rehearsal: expected five releases/receipts, frozen selection and revoked grant")
      growthDataset <- IO.fromEither(cursor.get[String]("growthDataset")).flatMap(s => Checked.lift[IO, DatasetId](Id.parse[DatasetTag](s)))
      growthBase <- IO.fromEither(cursor.get[String]("growthBase")).flatMap(s => Checked.lift[IO, ReleaseId](Id.parse[ReleaseTag](s)))
      growthHead <- IO.fromEither(cursor.get[String]("growthHead")).flatMap(s => Checked.lift[IO, ReleaseId](Id.parse[ReleaseTag](s)))
      growthCount <- sql"SELECT count(*) FROM release WHERE dataset_id=${UUID.fromString(growthDataset.render)}".query[Long].unique.transact(f.database.transactor)
      _ <- ensure(growthCount == 2, "Browser growth should produce exactly two releases")
      _ <- verifyExport(f, ReleaseRef(growthDataset, growthBase), output.resolve("first-session"), f.root.resolve("export-base"))
      _ <- verifyExport(f, ReleaseRef(growthDataset, growthHead), output.resolve("both-sessions"), f.root.resolve("export-head"))
      result = Json.obj("growthReleases" -> Json.fromLong(growthCount), "bothExportsMatch" -> Json.True, "passed" -> Json.True, "verifiedAfterBrowserAndServerClosed" -> Json.True,
        "head" -> Json.fromString(head.toString), "releases" -> Json.fromLong(releases),
        "acceptanceReceipts" -> Json.fromLong(receipts), "frozenSelections" -> Json.fromLong(selected),
        "browserGrantRevoked" -> Json.fromBoolean(revoked))
      _ <- IO.blocking(Files.writeString(output.resolve("persistence.json"), result.spaces2 + "\n"))
      _ <- IO.println("PostgreSQL persistence after browser/server shutdown: PASS")
    yield ()

  private def verifyExport(f: PgFixture, ref: ReleaseRef, expected: Path, target: Path): IO[Unit] =
    def paths(root: Path) = Using.resource(Files.walk(root))(_.iterator.asScala.map(root.relativize).toSet)
    checked(f.system.repository.exportFull(f.system.context, ref, new DirectoryExport[IO](target))) *>
      IO.blocking {
        val inventory = paths(expected)
        require(inventory == paths(target), s"Export inventory differs for ${ref.release.render}")
        inventory.filter(p => Files.isRegularFile(expected.resolve(p))).foreach { p =>
          require(Files.mismatch(expected.resolve(p), target.resolve(p)) == -1L, s"Export differs at $p")
        }
      }
