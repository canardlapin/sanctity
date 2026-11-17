package sanctity.postgres

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import doobie.implicits.*
import io.circe.Json
import io.circe.jawn.JawnParser
import munit.FunSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import sanctity.app.{ApiServer, ServiceInterpreters, SyntheticFixture}
import sanctity.bids.{MriInputPlanner, QcDerivativeFormat}
import sanctity.application.*
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import java.nio.file.Files

final class HttpSuite extends FunSuite:
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def key(s: String) = IdempotencyKey.from(s).toOption.get
  private def path(s: String) = RelPath.from(s).toOption.get
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, f.system.repository, credentials(f)).app
  private def request(method: Method, target: String, token: String, json: Option[Json] = None): Request[IO] =
    val req = Request[IO](method, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    json.fold(req)(j => req.withEntity(j.noSpaces).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
  private def decode(response: Response[IO]): IO[Json] = response.as[String].flatMap(t => IO.fromEither(new JawnParser().parse(t)))
  private def string(j: Json, field: String): String = j.hcursor.get[String](field).toOption.get
  private def publish(f: PgFixture): IO[AcceptanceReceipt] =
    checked(f.system.repository.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
      .flatMap(p => checked(f.system.repository.accept(f.system.context, p.candidate.id, key("initial"))))
  private def endpoint(r: ReleaseRef): String = s"/v1/datasets/${r.dataset.render}/releases/${r.release.render}"

  test("one upload protocol admits raw, archival and declared QC data and fences growth retries") {
    PostgresFixture.resource.use { f =>
      val ids = IdSource.random[IO]
      val app = ApiServer.routes(f.database, f.system.store, ids)
      def response(req: Request[IO], status: Status) =
        app.run(req).flatMap(r => r.as[String].map { body =>
          assertEquals(r.status, status, s"${req.method} ${req.uri}: $body")
          new JawnParser().parse(body).getOrElse(Json.Null)
        })
      def inventory(files: Map[String, String], base: Option[String], replace: Vector[String]) = Json.obj(
        "base" -> base.fold(Json.Null)(Json.fromString), "replace" -> Json.arr(replace.map(Json.fromString)*),
        "files" -> Json.arr(files.toVector.sortBy(_._1).map { (name, value) =>
          val bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          Json.obj("path" -> Json.fromString(name), "size" -> Json.fromInt(bytes.length),
            "sha256" -> Json.fromString(Digest.bytes(bytes).value))
        }*))
      def begin(token: String, dataset: DatasetId, k: String, files: Map[String, String],
          base: Option[String] = None, replace: Vector[String] = Vector.empty, status: Status = Status.Created) =
        response(request(Method.POST, s"/v1/datasets/${dataset.render}/uploads", token, Some(inventory(files, base, replace)))
          .putHeaders(Header.Raw(CIString("Idempotency-Key"), k)), status)
      def put(token: String, op: String, name: String, data: String, status: Status) =
        response(request(Method.PUT, s"/v1/uploads/$op/file?path=$name", token).withEntity(data)
          .putHeaders(Header.Raw(CIString("Content-Type"), "application/octet-stream")), status)
      def post(token: String, op: String, action: String, status: Status) =
        response(request(Method.POST, s"/v1/uploads/$op/$action", token), status)
      def publishFiles(token: String, dataset: DatasetId, files: Map[String, String]) =
        for
          session <- begin(token, dataset, "upload-initial", files)
          op = string(session, "id")
          _ <- post(token, op, "preview", Status.Conflict)
          _ <- files.toVector.traverse_((name, value) => put(token, op, name, value, Status.NoContent))
          preview <- post(token, op, "preview", Status.Ok)
          _ = assert(preview.hcursor.downField("candidate").downField("assessment").get[Boolean]("admissible").contains(true), preview.noSpaces)
          accepted <- post(token, op, "accept", Status.Ok)
          replay <- post(token, op, "accept", Status.Ok)
          _ = assertEquals(accepted, replay)
          id = accepted.hcursor.downField("ref").get[String]("release").toOption.get
          release <- response(request(Method.GET, s"/v1/datasets/${dataset.render}/releases/$id", token), Status.Ok)
        yield (id, release)
      for
        token <- checked(credentials(f).issue(f.system.context.principal, "curator", 1800)).map(_.secret)
        recording = "sub-01/beh/sub-01_task-choice_beh.tsv"
        raw = Map("dataset_description.json" -> """{"Name":"Raw","DatasetType":"raw","BIDSVersion":"1.11.2"}""",
          "task-choice_beh.json" -> """{"TaskName":"choice","response":{"Description":"Response"}}""", recording -> "response\nleft\n")
        first <- publishFiles(token, f.system.dataset.id, raw)
        _ = assertEquals(string(first._2, "profile"), "sanctity-bids-v1")
        viewDataset <- f.system.repository.create(f.system.context, f.system.dataset.custodian)
        selected <- response(request(Method.POST, "/v1/selections", token, Some(Json.obj(
          "releases" -> Json.arr(first._2.hcursor.downField("ref").focus.get),
          "artifacts" -> Json.obj("op" -> Json.fromString("suffix"), "value" -> Json.fromString("beh"))))), Status.Created)
        view <- response(request(Method.POST, s"/v1/datasets/${viewDataset.id.render}/views", token, Some(Json.obj(
          "source" -> first._2.hcursor.downField("ref").focus.get, "selection" -> Json.fromString(string(selected, "id")),
          "modality" -> Json.fromString("beh"), "disclosure" -> Json.obj("behaviour" -> Json.arr(Json.fromString("response")))))), Status.Created)
        _ = assert(view.hcursor.downField("candidate").downField("assessment").get[Boolean]("admissible").contains(true), view.noSpaces)
        archive <- f.system.repository.create(f.system.context, f.system.dataset.custodian)
        archived <- publishFiles(token, archive.id, Map("archive_description.json" -> """{"Name":"Protocol"}""", "notes.txt" -> "first"))
        _ = assertEquals(string(archived._2, "profile"), "sanctity-archive-v1")
        derivative <- f.system.repository.create(f.system.context, f.system.dataset.custodian)
        run <- ids.next[RunTag]
        source = ReleaseRef(f.system.dataset.id, Id.parse[ReleaseTag](first._1).toOption.get)
        sourceUri = QcDerivativeFormat.sourceUri(source)
        blob = BlobRef.from(Digest.bytes(Array.emptyByteArray), 0).toOption.get
        producer = ProducerIdentity(QcDerivativeFormat.Producer, "1", blob, "fixture")
        descriptor = Json.obj("Name" -> Json.fromString("Header QC"), "BIDSVersion" -> Json.fromString("1.11.2"),
          "DatasetType" -> Json.fromString("derivative"), "GeneratedBy" -> Json.arr(Json.obj("Name" -> Json.fromString(producer.name), "Version" -> Json.fromString("1"))),
          "DatasetLinks" -> Json.obj("input" -> Json.fromString(sourceUri)),
          "SourceDatasets" -> Json.arr(Json.obj("URL" -> Json.fromString(sourceUri), "Version" -> Json.fromString(first._1))),
          "SanctityHeaderQc" -> QcDerivativeFormat.declaration(run, source, blob.digest, producer, Map.empty))
        image = path("sub-01/func/sub-01_task-rest_bold.nii")
        qc <- publishFiles(token, derivative.id, Map("dataset_description.json" -> descriptor.noSpaces, "qc.json" -> QcDerivativeFormat.sidecar(Vector(image)).noSpaces,
          "qc.tsv" -> (QcDerivativeFormat.Columns.mkString("\t") + "\n" + image.value + "\t2\t2\t2\t3\t2\t2\n")))
        _ = assertEquals(string(qc._2, "profile"), "sanctity-header-qc-v2")
        _ = assert(qc._2.hcursor.downField("assessment").get[Vector[Json]]("findings").toOption.get.exists(j => string(j, "code") == "QcDeclaredProvenance"))
        edit = Map("notes.txt" -> "next")
        _ <- begin(token, archive.id, "without-confirmation", edit, Some(archived._1), status = Status.Conflict)
        session <- begin(token, archive.id, "confirmed", edit, Some(archived._1), edit.keys.toVector)
        op = string(session, "id")
        _ <- put(token, op, "notes.txt", "nope", Status.BadRequest)
        _ <- post(token, op, "preview", Status.Conflict)
        _ <- put(token, op, "notes.txt", "next", Status.NoContent)
        _ <- put(token, op, "notes.txt", "next", Status.NoContent)
        other <- begin(token, archive.id, "parallel", Map("other.txt" -> "other"), Some(archived._1))
        alternate = string(other, "id")
        _ <- put(token, alternate, "other.txt", "other", Status.NoContent)
        _ <- post(token, alternate, "preview", Status.Ok)
        _ <- post(token, op, "preview", Status.Ok)
        _ <- post(token, op, "accept", Status.Ok)
        _ <- post(token, alternate, "accept", Status.Conflict)
        again <- begin(token, archive.id, "confirmed", edit, Some(archived._1), edit.keys.toVector)
        _ = assertEquals(string(again, "id"), op)
        _ <- begin(token, archive.id, "different-key", edit, Some(archived._1), edit.keys.toVector, Status.Conflict)
      yield ()
    }.unsafeRunSync()
  }

  test("MRI header QC readiness uses the checks from the service's raw dataset interpreter") {
    PostgresFixture.resource.use { f =>
      val repo = new NativeRepository(f.system.catalog, f.system.store, ServiceInterpreters(f.system.store), IdSource.random[IO])
      for
        preview <- checked(repo.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("source"))))
        c = preview.candidate
        roots = c.interpretation.facts.filter(f => f.suffix == "bold" && f.extension == "nii" && f.participant == "sub-01").map(_.path)
        readiness <- Checked.lift[IO, InputReadiness](new MriInputPlanner().assess(c.manifest, c.interpretation, c.assessment, roots, MriInputPlanner.Contract))
        _ = assert(readiness.ready, readiness.requirements.filter(_.status != RequirementStatus.Satisfied).toString)
      yield ()
    }.unsafeRunSync()
  }

  test("credentials store only digests, expire, rotate independently and cannot provision an unknown principal") {
    PostgresFixture.resource.use { f =>
      val auth = credentials(f)
      for
        token <- checked(auth.issue(f.system.context.principal, "test", 3600))
        replacement <- checked(auth.issue(f.system.context.principal, "rotation", 3600))
        _ = assert(!token.toString.contains(token.secret))
        stored <- sql"SELECT token_digest FROM api_credential".query[String].to[Vector].transact(f.database.transactor)
        _ = assert(stored.forall(s => s.length == 64 && !s.contains(token.secret)))
        invalid <- auth.authenticate(token.secret.dropRight(1) + (if token.secret.last == 'A' then "B" else "A"))
        _ = assertEquals(invalid, Left(Problem.Denied))
        ctx <- checked(auth.authenticate(token.secret))
        _ = assertEquals(ctx.principal, f.system.context.principal)
        _ <- checked(auth.revoke(token.id))
        denied <- auth.authenticate(token.secret)
        _ = assertEquals(denied, Left(Problem.Denied))
        _ <- checked(auth.authenticate(replacement.secret))
        _ <- sql"""UPDATE api_credential SET issued_at=clock_timestamp()-interval '2 hours',
          expires_at=clock_timestamp()-interval '1 hour'""".update.run.transact(f.database.transactor)
        expired <- auth.authenticate(replacement.secret)
        _ = assertEquals(expired, Left(Problem.Denied))
        stranger <- IdSource.random[IO].next[PrincipalTag]
        unknown <- auth.issue(stranger, "unknown", 3600)
        _ = assertEquals(unknown, Left(Problem.Denied))
        excessive <- auth.issue(f.system.context.principal, "long", Int.MaxValue)
        _ = assert(excessive.isLeft)
      yield ()
    }.unsafeRunSync()
  }

  test("HTTP rejects missing, duplicated and query credentials and ignores forged principal headers") {
    PostgresFixture.resource.use { f =>
      for
        token <- checked(credentials(f).issue(f.system.context.principal, "owner", 3600))
        me <- api(f).run(request(Method.GET, "/v1/me", token.secret))
        _ = assertEquals(me.status, Status.Ok)
        j <- decode(me)
        _ = assertEquals(string(j, "principal"), f.system.context.principal.render)
        missing <- api(f).run(Request[IO](Method.GET, Uri.unsafeFromString("/v1/me"))
          .putHeaders(Header.Raw(CIString("X-Principal"), f.system.context.principal.render)))
        _ = assertEquals(missing.status, Status.Unauthorized)
        _ = assert(missing.headers.headers.exists(_.name == CIString("WWW-Authenticate")))
        query <- api(f).run(Request[IO](Method.GET, Uri.unsafeFromString("/v1/me").withQueryParam("access_token", token.secret)))
        _ = assertEquals(query.status, Status.Unauthorized)
        duplicate <- api(f).run(Request[IO](Method.GET, Uri.unsafeFromString("/v1/me"), headers = Headers(
          Header.Raw(CIString("Authorization"), s"Bearer ${token.secret}"),
          Header.Raw(CIString("Authorization"), s"Bearer ${token.secret}"))))
        _ = assertEquals(duplicate.status, Status.Unauthorized)
        _ <- checked(credentials(f).revoke(token.id))
        revoked <- api(f).run(request(Method.GET, "/v1/me", token.secret))
        _ = assertEquals(revoked.status, Status.Unauthorized)
      yield ()
    }.unsafeRunSync()
  }

  test("real HTTP curation preserves old metadata, returns one receipt on retries and exports exact bytes") {
    PostgresFixture.resource.use { f =>
      for
        initial <- publish(f)
        token <- checked(credentials(f).issue(f.system.context.principal, "curator", 3600))
        _ <- EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(0).get)
          .withHttpApp(api(f)).build.use { server =>
            EmberClientBuilder.default[IO].build.use { client =>
              val base = s"http://127.0.0.1:${server.address.getPort}"
              def call(req: Request[IO]): IO[(Status, Json)] = client.run(req).use(r => decode(r).map(r.status -> _))
              for
                view <- call(request(Method.GET, base + endpoint(initial.release), token.secret))
                _ = assertEquals(view._1, Status.Ok)
                _ = assert(!view._2.noSpaces.contains(f.root.toString))
                _ = assert(!view._2.noSpaces.contains("\"activity\""))
                _ = assert(!view._2.noSpaces.contains("\"retained\""))
                change = Json.obj("path" -> Json.fromString(SyntheticFixture.sharedSidecar),
                  "text" -> Json.fromString(SyntheticFixture.correctedJson), "reason" -> Json.fromString("HTTP curation"))
                preview <- call(request(Method.POST, base + endpoint(initial.release) + "/corrections", token.secret, Some(change)))
                _ = assertEquals(preview._1, Status.Created)
                candidate = preview._2.hcursor.downField("candidate").get[String]("id").toOption.get
                _ = assert(preview._2.hcursor.get[Vector[Json]]("metadataChanges").toOption.get.nonEmpty)
                accept = request(Method.POST, s"$base/v1/candidates/$candidate/accept", token.secret)
                  .putHeaders(Header.Raw(CIString("Idempotency-Key"), "http-accept"))
                first <- call(accept)
                again <- call(accept)
                _ = assertEquals(first._1, Status.Ok)
                _ = assertEquals(first, again)
                newRelease = first._2.hcursor.downField("ref").get[String]("release").toOption.get
                original <- call(request(Method.GET, base + endpoint(initial.release) + "/metadata", token.secret)
                  .withUri(Uri.unsafeFromString(base + endpoint(initial.release) + "/metadata").withQueryParam("path", SyntheticFixture.firstImage)))
                current <- call(request(Method.GET, s"$base/v1/datasets/${initial.release.dataset.render}/releases/$newRelease/metadata", token.secret)
                  .withUri(Uri.unsafeFromString(s"$base/v1/datasets/${initial.release.dataset.render}/releases/$newRelease/metadata").withQueryParam("path", SyntheticFixture.firstImage)))
                _ = assertEquals(original._2.hcursor.downField("fields").downField("RepetitionTime").get[BigDecimal]("value").toOption.get, BigDecimal("1.5"))
                _ = assertEquals(current._2.hcursor.downField("fields").downField("RepetitionTime").get[BigDecimal]("value").toOption.get, BigDecimal("1.8"))
                bytes <- client.run(request(Method.GET, base + endpoint(initial.release) + "/file", token.secret)
                  .withUri(Uri.unsafeFromString(base + endpoint(initial.release) + "/file").withQueryParam("path", SyntheticFixture.firstImage))).use { r =>
                    IO(assertEquals(r.status, Status.Ok)) *> r.body.compile.toVector
                  }
                expected <- IO.blocking(Files.readAllBytes(f.root.resolve("source").resolve(SyntheticFixture.firstImage)))
                _ = assertEquals(bytes, expected.toVector)
              yield ()
            }
          }
      yield ()
    }.unsafeRunSync()
  }

  test("fixed-release sharing, selection pages and revocation use the same current authority") {
    PostgresFixture.resource.use { f =>
      for
        initial <- publish(f)
        owner <- checked(credentials(f).issue(f.system.context.principal, "owner", 3600))
        guest <- IdSource.random[IO].next[PrincipalTag]
        grant <- api(f).run(request(Method.POST, s"/v1/datasets/${f.system.dataset.id.render}/grants", owner.secret,
          Some(Json.obj("recipient" -> Json.fromString(guest.render), "release" -> Json.fromString(initial.release.release.render),
            "actions" -> Json.arr(Json.fromString("ReadMetadata"), Json.fromString("ReadContent")))))).flatMap(decode)
        token <- checked(credentials(f).issue(guest, "guest", 3600))
        allowed <- api(f).run(request(Method.GET, endpoint(initial.release), token.secret))
        _ = assertEquals(allowed.status, Status.Ok)
        // Curation history is dataset-wide: the fixed-release recipient sees the release without it; the curator sees it.
        shared <- decode(allowed)
        _ = assert(shared.hcursor.downField("provenance").focus.exists(_.isNull), "fixed-release grant must not see provenance")
        own <- api(f).run(request(Method.GET, endpoint(initial.release), owner.secret)).flatMap(decode)
        _ = assertEquals(own.hcursor.downField("provenance").get[String]("acceptedBy").toOption, Some(f.system.context.principal.render))
        head <- api(f).run(request(Method.GET, s"/v1/datasets/${f.system.dataset.id.render}", token.secret))
        _ = assertEquals(head.status, Status.NotFound)
        forbidden <- api(f).run(request(Method.POST, endpoint(initial.release) + "/corrections", token.secret,
          Some(Json.obj("path" -> Json.fromString(SyntheticFixture.sharedSidecar), "text" -> Json.fromString("{}"), "reason" -> Json.fromString("no")))))
        _ = assertEquals(forbidden.status, Status.NotFound)
        query = Json.obj("releases" -> Json.arr(Json.obj("dataset" -> Json.fromString(initial.release.dataset.render),
          "release" -> Json.fromString(initial.release.release.render))), "participants" -> Json.obj(
          "op" -> Json.fromString("fieldAtLeast"), "name" -> Json.fromString("age"), "value" -> Json.fromInt(30)))
        selected <- api(f).run(request(Method.POST, "/v1/selections", token.secret, Some(query))).flatMap(decode)
        selection = string(selected, "id")
        page <- api(f).run(request(Method.GET, s"/v1/selections/$selection?offset=0&limit=1", token.secret)).flatMap(decode)
        _ = assertEquals(page.hcursor.get[Int]("total").toOption.get, 1)
        _ = assertEquals(page.hcursor.downField("hits").downArray.get[String]("participant").toOption.get, "sub-02")
        revoked <- api(f).run(request(Method.DELETE, s"/v1/datasets/${f.system.dataset.id.render}/grants/${string(grant, "id")}", owner.secret))
        _ = assertEquals(revoked.status, Status.NoContent)
        denied <- api(f).run(request(Method.GET, s"/v1/selections/$selection", token.secret))
        _ = assertEquals(denied.status, Status.NotFound)
        lost <- api(f).run(request(Method.GET, endpoint(initial.release), token.secret))
        _ = assertEquals(lost.status, Status.NotFound)
      yield ()
    }.unsafeRunSync()
  }

  test("cached contexts cannot publish after credential revocation; active file delivery stops at a chunk boundary") {
    PostgresFixture.resource.use { f =>
      for
        _ <- IO.blocking {
          val image = f.root.resolve("source").resolve(SyntheticFixture.firstImage)
          // Preserve a valid image header while making delivery span several chunks.
          val _ = Files.write(image, java.util.Arrays.copyOf(Files.readAllBytes(image), 200000))
        }
        initial <- publish(f)
        token <- checked(credentials(f).issue(f.system.context.principal, "stream", 3600))
        ctx <- checked(credentials(f).authenticate(token.secret))
        preview <- checked(f.system.repository.correctMetadata(ctx, initial.release, path(SyntheticFixture.sharedSidecar),
          SyntheticFixture.correctedJson, "prepared before revocation"))
        response <- api(f).run(request(Method.GET, endpoint(initial.release) + "/file", token.secret)
          .withUri(Uri.unsafeFromString(endpoint(initial.release) + "/file").withQueryParam("path", SyntheticFixture.firstImage)))
        delivered <- Ref.of[IO, Long](0)
        attempt <- response.body.chunks.evalTap(c => delivered.update(_ + c.size) *> checked(credentials(f).revoke(token.id))).compile.drain.attempt
        _ = assert(attempt.left.toOption.exists { case Failure(Problem.Denied) => true; case _ => false })
        count <- delivered.get
        _ = assertEquals(count, 65536L)
        denied <- f.system.repository.accept(ctx, preview.candidate.id, key("revoked-context"))
        _ = assertEquals(denied, Left(Problem.Denied))
        unchanged <- checked(f.system.catalog.dataset(f.system.context, f.system.dataset.id))
        _ = assertEquals(unchanged.head, Some(initial.release.release))
      yield ()
    }.unsafeRunSync()
  }

  test("recovery stays closed to HTTP and invalidates even still-active restored tokens") {
    PostgresFixture.resource.use { f =>
      for
        _ <- publish(f)
        token <- checked(credentials(f).issue(f.system.context.principal, "before-restore", 3600))
        policy <- f.database.recovery.capturePolicy
        _ <- f.database.recovery.requireReconciliation
        closed <- api(f).run(request(Method.GET, "/v1/me", token.secret))
        _ = assertEquals(closed.status, Status.ServiceUnavailable)
        _ <- checked(f.database.recovery.reconcile(policy, policy.digest, f.system.store))
        stale <- api(f).run(request(Method.GET, "/v1/me", token.secret))
        _ = assertEquals(stale.status, Status.Unauthorized)
        renewed <- checked(credentials(f).issue(f.system.context.principal, "after-restore", 3600))
        open <- api(f).run(request(Method.GET, "/v1/me", renewed.secret))
        _ = assertEquals(open.status, Status.Ok)
      yield ()
    }.unsafeRunSync()
  }

  test("untrusted JSON and raw internal capabilities are rejected without disclosing operational details") {
    PostgresFixture.resource.use { f =>
      for
        initial <- publish(f)
        token <- checked(credentials(f).issue(f.system.context.principal, "bad-requests", 3600))
        raw <- api(f).run(request(Method.POST, endpoint(initial.release) + "/corrections", token.secret)
          .withEntity("""{"path":"x.json","text":"{}","reason":"x","manifest":{"digest":"guess"}}""")
          .putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
        _ = assertEquals(raw.status, Status.BadRequest)
        duplicate <- api(f).run(request(Method.POST, endpoint(initial.release) + "/corrections", token.secret)
          .withEntity("""{"path":"x.json","path":"y.json","text":"{}","reason":"x"}""")
          .putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
        _ = assertEquals(duplicate.status, Status.BadRequest)
        traversal <- api(f).run(request(Method.GET, endpoint(initial.release) + "/file?path=..%2Fsecret", token.secret))
        _ = assertEquals(traversal.status, Status.BadRequest)
        body <- traversal.as[String]
        _ = assertEquals(body, """{"error":"invalid_request"}""")
        _ = assert(traversal.headers.headers.exists(h => h.name == CIString("Cache-Control") && h.value == "no-store"))
        noDigest <- api(f).run(request(Method.GET, "/v1/blobs/" + "0" * 64, token.secret))
        _ = assertEquals(noDigest.status, Status.NotFound)
        large <- api(f).run(request(Method.POST, endpoint(initial.release) + "/corrections", token.secret)
          .withEntity(" " * (4 * 1024 * 1024 + 1)).putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
        _ = assertEquals(large.status, Status.PayloadTooLarge)
      yield ()
    }.unsafeRunSync()
  }

  test("operator CLI resumes imports and writes credentials privately without overwriting an existing file") {
    PostgresFixture.resource.use { f =>
      val cli = sanctity.app.RepositoryCli
      val args = List("import", f.system.context.principal.render, f.system.dataset.id.render,
        f.root.resolve("source").toString, f.root.resolve("storage").toString, "cli-import")
      val secretFile = f.root.resolve("credential.json")
      val issue = List("issue-credential", f.system.context.principal.render, "3600", "cli-client", secretFile.toString)
      for
        _ <- cli.command(f.database, args)
        _ <- cli.command(f.database, args)
        releases <- sql"SELECT count(*) FROM release".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(releases, 1L)
        _ <- cli.command(f.database, issue)
        permissions <- IO.blocking(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(secretFile)))
        _ = assertEquals(permissions, "rw-------")
        original <- IO.blocking(Files.readString(secretFile))
        document <- IO.fromEither(new JawnParser().parse(original))
        ctx <- checked(credentials(f).authenticate(string(document, "token")))
        _ = assertEquals(ctx.principal, f.system.context.principal)
        refused <- cli.command(f.database, issue).attempt
        _ = assert(refused.isLeft)
        unchanged <- IO.blocking(Files.readString(secretFile))
        _ = assert(unchanged == original)
        active <- sql"SELECT count(*) FROM api_credential WHERE revoked_at IS NULL".query[Long].unique.transact(f.database.transactor)
        _ = assertEquals(active, 1L)
        _ <- cli.command(f.database, List("revoke-credential", string(document, "credential")))
        denied <- credentials(f).authenticate(string(document, "token"))
        _ = assertEquals(denied, Left(Problem.Denied))
      yield ()
    }.unsafeRunSync()
  }
