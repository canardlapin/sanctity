package sanctity.postgres

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import doobie.Fragment
import doobie.implicits.*
import io.circe.Json
import sanctity.application.*
import sanctity.app.PolicyClientConfiguration
import sanctity.kernel.*
import sanctity.objects.{FileBlobStore, NativeDirectorySource}
import sanctity.bids.BidsInterpreter
import sanctity.policy.PolicyWire.*
import sanctity.policy.PolicyWitnessClient
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.{Connection, PreparedStatement}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Fault controls exist only in the qualification JAR, never in the service artifact. */
object PolicyProcessQualification:
  private val root = Path.of("/work")
  private val ids = IdSource.random[IO]
  private def checked[A](f: IO[Either[Problem,A]]): IO[A] = f.flatMap(Checked.lift[IO,A])
  private def id[A](s: String): Id[A] = Id.parse[A](s).toOption.get
  private def saved: Json = parse(Files.readAllBytes(root.resolve("fixture.json")))
  private def emit(j: Json): Unit = { println(j.noSpaces); System.out.flush() }
  private def marker(stage: String): Unit =
    Files.writeString(root.resolve("boundary.json"),obj("stage" -> str(stage),"pid" -> str(ProcessHandle.current().pid().toString),
      "at" -> str(Instant.now.toString)).noSpaces)
  private def pause(stage: String): Unit = { marker(stage); new CountDownLatch(1).await() }
  private def problem(e: Throwable): String = e match
    case Failure(p) => p match
      case Problem.PolicyPending(_) => "pending"
      case Problem.PolicyOutcomeUnknown(_) => "unknown"
      case Problem.Denied => "denied"
      case _ => "refused"
    case _ => "refused"
  private def database(trusted: Boolean): Resource[IO,PostgresDatabase[IO]] =
    val config = DatabaseConfig.environment(sys.env).toOption.get
    val connection = if trusted then PolicyClientConfiguration.load(Path.of("/trust/writer.json")).map(Some(_))
      else Resource.pure[IO,Option[PolicyConnection]](None)
    connection.flatMap(c => PostgresDatabase.open[IO](config,c))
  private def execute(connection: Connection, method: Method, args: Array[Object]): Object =
    try method.invoke(connection,args*) catch case e: InvocationTargetException => throw e.getCause
  private def faulting(db: PostgresDatabase[IO], stage: String): PostgresDatabase[IO] =
    val xa = db.transactor
    val wrapped = xa.copy(connect0 = kernel => xa.connect(kernel).map { c =>
      var event = false
      var acknowledged = false
      var commits = 0
      Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
        def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object =
          val args = Option(arguments).getOrElse(Array.empty[Object])
          if method.getName == "prepareStatement" && args.headOption.exists(_.isInstanceOf[String]) then
            val sql = args(0).toString.trim
            if stage == "before-prepare" && sql == "SELECT current_user" then pause(stage)
            if sql.startsWith("INSERT INTO policy_acknowledgement") && stage == "before-receipt-insert" then pause(stage)
            val statement = execute(c,method,args).asInstanceOf[PreparedStatement]
            Proxy.newProxyInstance(classOf[PreparedStatement].getClassLoader,Array(classOf[PreparedStatement]),new InvocationHandler {
              def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object =
                val values = Option(arguments).getOrElse(Array.empty[Object])
                try
                  val result = method.invoke(statement,values*)
                  if method.getName.startsWith("execute") then
                    if sql.startsWith("INSERT INTO organization_event") then event = true
                    if sql.startsWith("INSERT INTO policy_acknowledgement") then acknowledged = true
                  result
                catch case e: InvocationTargetException => throw e.getCause
            }).asInstanceOf[PreparedStatement]
          else if method.getName == "commit" && event then
            if commits == 0 && stage == "before-local-commit" then pause(stage)
            if acknowledged && stage == "before-receipt-commit" then pause(stage)
            val result = execute(c,method,args)
            commits += 1
            if commits == 1 && stage == "after-local-commit" then pause(stage)
            if commits == 1 && stage == "lost-local-response" then
              marker(stage); throw new java.sql.SQLException("qualification lost local response","08006")
            if acknowledged && stage == "after-receipt-commit" then pause(stage)
            if acknowledged && stage == "lost-receipt-response" then
              marker(stage); throw new java.sql.SQLException("qualification lost receipt response","08006")
            result
          else execute(c,method,args)
      }).asInstanceOf[Connection]
    })
    new PostgresDatabase[IO](wrapped,db.schema)

  private def setup: IO[Json] = database(false).use { db =>
    val cat = db.catalog(ids)
    val name = OrganizationName.from("Qualification person").toOption.get
    for
      _ <- db.migrate
      owner <- ids.next[PrincipalTag]
      recipient <- ids.next[PrincipalTag]
      disabled <- ids.next[PrincipalTag]
      custodian <- ids.next[CustodianTag]
      dataset <- cat.create(AccessContext(owner),custodian)
      _ <- IO.blocking {
        Files.createDirectories(root.resolve("source"))
        Files.writeString(root.resolve("source/dataset_description.json"),"""{"Name":"Process qualification","BIDSVersion":"1.10.0","DatasetType":"raw"}""")
      }
      store <- FileBlobStore.open[IO](root.resolve("blobs"))
      repository = new NativeRepository[IO](cat,store,new BidsInterpreter[IO](store),ids)
      imports = new DurableImports[IO](cat,db.journal(ids),store,repository,ids)
      source = new NativeDirectorySource[IO](root.resolve("source"))
      initial <- checked(imports.begin(AccessContext(owner),dataset.id,IdempotencyKey.from("accepted-source").toOption.get,source))
      accepted <- checked(imports.resume(AccessContext(owner),initial.id,source,publish=true))
      release = accepted.receipt.get.release.release
      pending <- cat.create(AccessContext(owner),custodian)
      target <- checked(cat.grant(AccessContext(owner),dataset.id,recipient,None,Set(Action.ReadMetadata)))
      revoked <- checked(cat.grant(AccessContext(owner),dataset.id,recipient,Some(release),Set(Action.ReadContent)))
      _ <- checked(cat.revoke(AccessContext(owner),dataset.id,revoked.id))
      credential <- checked(db.credentials(ids).issue(recipient,"historical revoked",3600))
      _ <- checked(db.credentials(ids).revoke(credential.id))
      active <- checked(db.credentials(ids).issue(owner,"current owner",3600))
      _ <- checked(cat.grant(AccessContext(owner),dataset.id,disabled,None,Set(Action.ReadMetadata)))
      inactive <- checked(db.credentials(ids).issue(disabled,"disabled principal",3600))
      preview <- db.organization.preview(owner)
      _ <- db.organization.bootstrap(owner,PostgresOrganizationCatalog.previewDigest(preview))
      before <- db.organization.snapshot
      ref = OrganizationRef.Principal(disabled)
      _ <- checked(db.organization.execute(AccessContext(owner),OrganizationMutation(IdempotencyKey.from("historical-disable").toOption.get,
        Map(ref -> before.generation(ref).get),OrganizationCommand.DisablePrincipal(disabled))))
      // Real current lease rows: retain status/expiry but never export these capabilities.
      operation <- checked(db.journal(ids).begin(AccessContext(owner),pending.id,IdempotencyKey.from("pending-intake").toOption.get,
        "qualification",Vector.empty))
      _ <- checked(db.journal(ids).claim(AccessContext(owner),operation.id,60))
      _ <- Fragment.const("INSERT INTO outbox_delivery(sequence,target,lease_token,lease_until,attempts) SELECT sequence,'qualification',gen_random_uuid(),clock_timestamp()+interval '1 hour',0 FROM outbox ON CONFLICT DO NOTHING").update.run.transact(db.transactor)
      installation <- sql"SELECT installation::text FROM organization_policy".query[String].unique.transact(db.transactor)
      _ <- db.grantRuntime("old_runtime")
      fixture = obj("installation" -> str(installation),"owner" -> str(owner.render),"recipient" -> str(recipient.render),
        "release" -> str(release.render),"disabled" -> str(disabled.render),"dataset" -> str(dataset.id.render),"grant" -> str(target.id.render),
        "revokedGrant" -> str(revoked.id.render),"revokedCredential" -> str(credential.id.render),
        "disabledCredential" -> str(inactive.id.render),"activeCredential" -> str(active.id.render),"name" -> str(name.value))
      _ <- IO.blocking(Files.writeString(root.resolve("fixture.json"),fixture.noSpaces))
      _ <- IO.blocking {
        Files.writeString(root.resolve("secrets.json"),obj("active" -> str(active.secret),"revoked" -> str(credential.secret),"disabled" -> str(inactive.secret)).noSpaces)
        Files.setPosixFilePermissions(root.resolve("secrets.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
      }
    yield fixture
  }

  private def operation(action: String, stage: String): IO[Json] = database(true).use { original =>
    val f = saved
    val ctx = AccessContext(id[PrincipalTag](text(f,"owner")))
    val dataset = id[DatasetTag](text(f,"dataset"))
    val db = if stage.isEmpty then original else faulting(original,stage)
    action match
      case "revoke" => checked(db.catalog(ids).revoke(ctx,dataset,id[GrantTag](text(f,"grant"))))
        .as(obj("status" -> str("durable")))
      case "grant" => ids.next[PrincipalTag].flatMap(person => checked(db.catalog(ids).grant(ctx,dataset,person,None,Set(Action.ReadMetadata))))
        .as(obj("status" -> str("durable")))
      case "resolve" => db.policy.resolve
      case "readiness" => db.checkRuntimeRole *> db.checkOrganizationReady *> db.policy.readiness
      case "check" => db.catalog(ids).check(AccessContext(id[PrincipalTag](text(f,"recipient"))),dataset,Action.ReadMetadata)
        .map(result => obj("allowed" -> Json.fromBoolean(result.isRight)))
      case "restrictions" =>
        val secrets = parse(Files.readAllBytes(root.resolve("secrets.json")))
        for
          revoked <- db.credentials(ids).authenticate(text(secrets,"revoked"))
          disabled <- db.credentials(ids).authenticate(text(secrets,"disabled"))
          active <- db.credentials(ids).authenticate(text(secrets,"active"))
          oldGrant <- db.catalog(ids).check(AccessContext(id[PrincipalTag](text(f,"recipient"))),dataset,Action.ReadContent,Some(id[ReleaseTag](text(f,"release"))))
        yield obj("revokedCredentialDenied" -> Json.fromBoolean(revoked.isLeft),"disabledCredentialDenied" -> Json.fromBoolean(disabled.isLeft),
          "activeCredentialAccepted" -> Json.fromBoolean(active.isRight),"revokedGrantDenied" -> Json.fromBoolean(oldGrant.isLeft))
      case "hold" =>
        val transaction = for
          _ <- OrganizationSql.guard(false)
          _ <- sql"UPDATE outbox_delivery SET attempts=attempts+1".update.run
          _ <- doobie.free.connection.raw(c => {
            val backend = PolicyBridge.rows(c,"SELECT pg_backend_pid()")(_.getInt(1)).head
            Files.writeString(root.resolve("boundary.json"),obj("stage" -> str("admitted"),"pid" -> str(ProcessHandle.current().pid().toString),
              "backend" -> str(backend.toString),"at" -> str(Instant.now.toString)).noSpaces)
            Thread.sleep(6500)
          })
        yield ()
        transaction.transact(original.transactor).as(obj("status" -> str("unexpected-commit")))
      case "watch" =>
        original.checkRuntimeRole *> original.checkOrganizationReady *> IO.blocking(marker("watch-ready")) *>
          (IO.realTimeInstant.flatMap { at => original.checkOrganizationReady.attempt.flatMap { result => IO.blocking {
            val line = obj("at" -> str(at.toString),"admitted" -> Json.fromBoolean(result.isRight)).noSpaces+"\n"
            Files.writeString(root.resolve("watch.jsonl"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND); ()
          } } } *> IO.sleep(scala.concurrent.duration.DurationInt(100).millis)).foreverM
      case _ => IO.raiseError(new IllegalArgumentException("qualification action"))
  }

  private def remote(action: String): IO[Json] = PolicyClientConfiguration.load(Path.of("/trust/recovery.json")).use { recovery =>
    IO.blocking {
      val current = field(recovery.client.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current")
      action match
        case "current" => current
        case "barrier" =>
          val command = obj("operation" -> str(UUID.randomUUID().toString),"installation" -> field(current,"installation"),
            "epoch" -> str((natural(text(current,"epoch"))+1).toString),"operator" -> str(recovery.writer),
            "at" -> str(Instant.now.toString),"reason" -> str("qualified synthetic recovery"),"challenge" -> str(UUID.randomUUID().toString))
          val result = recovery.client.call(PolicyWitnessClient.request("RECOVERY_BARRIER",command,natural(text(current,"sequence")),text(current,"journalHash")))
          recovery.client.verifyRecord(field(result,"record")); result
        case "retry-barrier" =>
          val retained = recovery.client.call(PolicyWitnessClient.request("RECORD",obj("sequence" -> field(current,"sequence")),0,zero))
          val record = recovery.client.verifyRecord(field(retained,"record"))
          check(text(record,"kind") == "RECOVERY_BARRIER","barrier-record-required")
          val response = recovery.client.call(PolicyWitnessClient.request("RECOVERY_BARRIER",field(record,"command"),0,zero))
          check(field(response,"record") == field(retained,"record") && field(response,"current") == current,"exact-barrier-retry")
          obj("exact" -> Json.True)
        case "records" =>
          val records = (1L to natural(text(current,"sequence"))).map { sequence =>
            val response = recovery.client.call(PolicyWitnessClient.request("RECORD",obj("sequence" -> str(sequence.toString)),0,zero))
            recovery.client.verifyRecord(field(response,"record"))
          }
          Json.arr(records*)
        case _ => throw new IllegalArgumentException("remote action")
    }
  }

  private def adversarial: IO[Json] = remote("records").flatMap { records =>
    PolicyClientConfiguration.load(Path.of("/trust/writer.json")).use { writer => IO.blocking {
      val command = field(records.asArray.get.last,"command")
      val before = field(writer.client.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current")
      def call(action: String, value: Json): Json = writer.client.call(PolicyWitnessClient.request(action,value,
        natural(text(before,"sequence")),text(before,"journalHash")))
      val duplicate = call("COMMIT",command)
      val prepare = call("PREPARE",command)
      check(field(duplicate,"record") == field(prepare,"record"),"exact-terminal-retry")
      check(field(duplicate,"current") == before,"duplicate-head")
      val payload = io.circe.jawn.parse(utf8(unbase64(text(command,"payload")))).toOption.get
      def envelope(value: Json): Json =
        val encoded = value.printWith(io.circe.Printer.noSpaces.copy(sortKeys=true))
        val applied = Digest.framed("sanctity.policy-local.v2",Vector(text(value,"previousHash"),text(value,"event"),
          text(value,"revision"),digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).value
        PolicyBridge.envelope(encoded,applied)
      val changed = envelope(payload.mapObject(_.add("reason",str("changed exact operation"))))
      val ahead = envelope(payload.mapObject(o => o.add("event",str(UUID.randomUUID().toString))
        .add("revision",str((natural(text(command,"revision"))+2).toString))
        .add("previousRevision",str((natural(text(command,"revision"))+1).toString))
        .add("previousHash",field(before,"appliedHash"))))
      val cases = Vector("changed-payload" -> changed,"reordered-event" -> ahead,
        "unknown-capability" -> command.mapObject(_.add("capabilities",str(zero))),
        "foreign-installation" -> command.mapObject(_.add("installation",str(UUID.randomUUID().toString))))
      cases.foreach { (_,value) => check(scala.util.Try(call("PREPARE",value)).isFailure,"adversarial-request-accepted") }
      check(field(writer.client.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current") == before,"adversarial-head-changed")
      obj("exactDuplicateTerminal" -> Json.True,"negativeCases" -> Json.arr(cases.map((name,_) => str(name))*))
    } }
  }

  def main(args: Array[String]): Unit =
    Files.writeString(root.resolve("pid"),ProcessHandle.current().pid().toString)
    val action = args.head
    val work = action match
      case "setup" => setup
      case "adversarial" => adversarial
      case "current" | "barrier" | "records" | "retry-barrier" => remote(action)
      case "enroll" => database(false).use { db =>
        PolicyClientConfiguration.load(Path.of("/trust/writer.json")).use { writer =>
          PolicyClientConfiguration.load(Path.of("/trust/recovery.json")).use { recovery => db.policy.enroll(writer,recovery.client) }
        }
      }
      case "runtime-profile" => database(true).use(_.grantRuntime("new_runtime")).as(obj("status" -> str("profiled")))
      case other => operation(other,args.lift(1).getOrElse(""))
    work.attempt.unsafeRunSync() match
      case Right(value) => emit(obj("ok" -> Json.True,"result" -> value))
      case Left(error) =>
        emit(obj("ok" -> Json.False,"outcome" -> str(problem(error)),"type" -> str(error.getClass.getSimpleName)))
        System.exit(2)
