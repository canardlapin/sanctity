package sanctity.postgres

import cats.effect.{IO, Ref}
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
import sanctity.app.SyntheticFixture
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
        _ <- IO.blocking(Files.write(f.root.resolve("source").resolve(SyntheticFixture.firstImage), Array.fill[Byte](200000)(17)))
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
