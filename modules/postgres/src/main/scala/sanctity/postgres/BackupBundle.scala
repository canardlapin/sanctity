package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.free.connection as FC
import doobie.implicits.*
import io.circe.Json
import sanctity.policy.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Operator-only, snapshot-consistent backup. The link is never proof that a restore
  * may reopen: the consumer must join a fresh independently trusted witness head.
  */
object BackupBundle:
  import PolicyWire.{obj, str, field, text, check}
  import JsonCodecs.given
  val version = "sanctity-backup-v2"
  // Backup manifests contain bounded integer sizes; the policy wire deliberately
  // rejects all numeric tokens. Keep their canonical encodings separate.
  private def canonical(value: Json): Array[Byte] =
    value.printWith(io.circe.Printer.noSpaces.copy(sortKeys=true)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
  def manifestDigest(value: Json): String = PolicyWire.digest(canonical(value))
  private val noFollow = LinkOption.NOFOLLOW_LINKS
  private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
  private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
  val MaximumBytes: Long = 64L * 1024 * 1024 * 1024
  val MaximumObjects = 100000
  private def parse(bytes: Array[Byte]): Json = io.circe.jawn.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8))
    .fold(_ => throw PolicyFailure("backup-json"),identity)
  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 255}%02x").mkString
  def hashFile(path: Path): String =
    check(Files.isRegularFile(path,noFollow),"backup-file")
    val hash = MessageDigest.getInstance("SHA-256")
    val channel = FileChannel.open(path,StandardOpenOption.READ,noFollow)
    try
      val buffer = ByteBuffer.allocate(65536)
      var n = channel.read(buffer)
      while n >= 0 do
        buffer.flip(); hash.update(buffer); buffer.clear()
        n = channel.read(buffer)
      hex(hash.digest())
    finally channel.close()
  private def forceDirectory(path: Path): Unit =
    val c = FileChannel.open(path,StandardOpenOption.READ)
    try c.force(true) finally c.close()
  private def write(path: Path, value: Json): Unit =
    val c = FileChannel.open(path,Set(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).asJava,privateFile)
    try
      val bytes = ByteBuffer.wrap(canonical(value))
      while bytes.hasRemaining do c.write(bytes)
      c.force(true)
    finally c.close()
  private def regular(root: Path, relative: String): Path =
    check(relative == "catalog.dump" || relative == "policy-link.json" || relative.matches("storage/objects/[0-9a-f]{2}/[0-9a-f]{64}"),"backup-path")
    val p = root.resolve(relative)
    check(Files.isRegularFile(p,noFollow) && p.toRealPath() == p.toAbsolutePath.normalize(),"backup-file")
    p
  private def fileRecord(path: Path): Json = obj("sha256" -> str(hashFile(path)),"size" -> Json.fromLong(Files.size(path)))

  /** A separate verifier checks every byte and rejects extras, missing files and
    * symlinks. It never opens repository access or trusts a timestamp as currency.
    */
  def verify(root: Path): Json =
    val manifest = root.resolve("bundle.json")
    check(root.toRealPath() == root.toAbsolutePath.normalize() && Files.isRegularFile(manifest,noFollow) &&
      Files.size(manifest) <= 32L * 1024 * 1024,"backup-manifest")
    val value = parse(Files.readAllBytes(manifest))
    check(text(value,"version") == version,"backup-version")
    PolicyWire.fields(value,Set("version","createdAt","artifact","schema","migrations","postgresVersionNumber",
      "installation","capabilities","policyHead","objects","files"))
    PolicyWire.hash(text(field(value,"artifact"),"sha256"))
    check(text(value,"schema").matches("[a-z][a-z0-9_]{0,62}"),"backup-schema")
    val files = field(value,"files").asObject.getOrElse(throw PolicyFailure("backup-files"))
    check(files.size <= MaximumObjects+2 && files.contains("catalog.dump") && files.contains("policy-link.json"),"backup-inventory")
    var total = 0L
    files.toVector.foreach { (name,expected) =>
      PolicyWire.fields(expected,Set("sha256","size"))
      PolicyWire.hash(text(expected,"sha256"))
      val p = regular(root,name)
      val size = field(expected,"size").asNumber.flatMap(_.toLong).getOrElse(throw PolicyFailure("backup-size"))
      check(size >= 0 && size <= MaximumBytes && Files.size(p) == size,"backup-size")
      total = Math.addExact(total,size)
      check(total <= MaximumBytes && hashFile(p) == text(expected,"sha256"),"backup-integrity")
      if name.startsWith("storage/objects/") then check(p.getFileName.toString == text(expected,"sha256") &&
        p.getParent.getFileName.toString == p.getFileName.toString.take(2),"backup-object")
    }
    val walk = Files.walk(root)
    try
      val observed = walk.iterator().asScala.filter(p => !Files.isDirectory(p,noFollow)).map { p =>
        check(!Files.isSymbolicLink(p),"backup-symlink")
        root.relativize(p).toString
      }.toSet
      check(observed == files.keys.toSet + "bundle.json","backup-extra-files")
    finally walk.close()
    val objects = field(value,"objects").asArray.getOrElse(throw PolicyFailure("backup-objects"))
    val required = objects.map { b =>
      val hash = PolicyWire.hash(text(b,"sha256"))
      s"storage/objects/${hash.take(2)}/$hash" -> b
    }
    check(required.map(_._1).distinct.size == required.size &&
      required.toMap == files.toMap.filter(_._1.startsWith("storage/objects/")),"backup-object-inventory")
    value

  def capture[F[_]: Async](db: PostgresDatabase[F], config: DatabaseConfig, trust: PolicyConnection,
      storage: Path, target: Path, artifact: Json, dumpExecutable: String = "pg_dump", seconds: Long = 300): F[Json] =
    val F = Async[F]
    val partial = target.resolveSibling(".pending-"+target.getFileName.toString+"-"+UUID.randomUUID().toString)
    val started = System.nanoTime()
    def within(): Unit = check(System.nanoTime()-started < TimeUnit.SECONDS.toNanos(seconds),"backup-deadline")
    for
      _ <- db.requireOperator *> db.checkCompatibility()
      _ <- F.blocking {
        check(seconds >= 10 && seconds <= 3600 && storage.toRealPath() == storage && target.isAbsolute &&
          target.normalize() == target && target.getParent.toRealPath() == target.getParent &&
          !target.startsWith(storage) && !Files.exists(target,noFollow),"backup-target")
        Files.createDirectory(partial,privateDirectory)
      }
      result <- (for
        captured <- FC.raw { c =>
          import PolicyBridge.{rows,update}
          within()
          c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
          update(c,"SET LOCAL statement_timeout='30s'")
          update(c,s"SET LOCAL idle_in_transaction_session_timeout='${seconds}s'")
          // MVCC pins this historical point without blocking ongoing publication.
          // A concurrent policy change before CURRENT makes capture refuse; one
          // after CURRENT belongs to the independently retained recovery suffix.
          check(!rows(c,"SELECT recovery_required FROM repository_state WHERE singleton")(_.getBoolean(1)).head,"backup-closed")
          val state = rows(c,"SELECT installation::text,writer_epoch::text,revision::text,applied_hash,durability_mode FROM organization_policy WHERE singleton")(
            r => (r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))).head
          check(state._5 == "independent" && state._1 == trust.installation,"backup-independent-policy-required")
          val profile = PolicyBridge.capabilities(c)
          val current = field(trust.client.call(PolicyWitnessClient.request("CURRENT",obj(),0,PolicyWire.zero)),"current")
          check(text(current,"installation") == state._1 && text(current,"epoch") == state._2 &&
            text(current,"revision") == state._3 && text(current,"appliedHash") == state._4 &&
            text(current,"writer") == trust.writer && text(current,"capabilities") == profile &&
            field(current,"pending").isNull && field(current,"barrier").isNull,"backup-policy-head")
          val enrollment = rows(c,"SELECT command::text,certificate::text FROM policy_enrollment")(
            r => parse(r.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)) ->
              parse(r.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8))).head
          val enrolled = trust.client.verifyRecord(enrollment._2)
          check(text(enrolled,"kind") == "ENROLL" && field(enrolled,"command") == enrollment._1,"backup-enrollment")
          check(!rows(c,"SELECT EXISTS(SELECT 1 FROM organization_event e LEFT JOIN policy_acknowledgement a ON a.event_id=e.id WHERE e.durability='Pending' AND a.event_id IS NULL)")(_.getBoolean(1)).head,"backup-unacknowledged")
          val objects = rows(c,"SELECT digest,size FROM blob ORDER BY digest")(r => r.getString(1) -> r.getLong(2)) ++
            rows(c,"SELECT payload::text FROM acquired_file") { r =>
              val entry = JsonCodecs.decode[sanctity.kernel.Entry](r.getString(1)).fold(p => throw sanctity.application.Failure(p),identity)
              entry match
                case sanctity.kernel.Entry.File(_,_,b) => b.digest.value -> b.size
                case _ => throw PolicyFailure("backup-acquired-file")
            }
          val required = objects.distinct.sortBy(_._1)
          check(required.size <= MaximumObjects && required.map(_._1).distinct.size == required.size &&
            required.forall((h,n) => h.matches("[0-9a-f]{64}") && n >= 0) &&
            required.foldLeft(BigInt(0))((n,b) => n+b._2) <= MaximumBytes,"backup-object-bound")
          val schema = rows(c,"SELECT version,checksum FROM schema_migration ORDER BY version")(
            r => obj("version" -> Json.fromInt(r.getInt(1)),"checksum" -> str(r.getString(2))))
          val server = rows(c,"SHOW server_version_num")(_.getInt(1)).head
          check(server >= 170000 && server < 180000,"backup-postgres-version")
          val snapshot = rows(c,"SELECT pg_export_snapshot()")(_.getString(1)).head
          val uri = URI.create(config.jdbcUrl.stripPrefix("jdbc:"))
          check(uri.getScheme == "postgresql" && uri.getHost != null && uri.getUserInfo == null && uri.getQuery == null &&
            uri.getPath.matches("/[A-Za-z0-9_-]+"),"backup-connection-shape")
          val builder = new ProcessBuilder(dumpExecutable,"--format=custom","--no-owner","--no-acl",
            "--schema="+config.schema,"--snapshot="+snapshot)
          val env = builder.environment()
          env.put("PGHOST",uri.getHost); env.put("PGPORT",(if uri.getPort < 0 then 5432 else uri.getPort).toString)
          env.put("PGDATABASE",uri.getPath.drop(1));env.put("PGUSER",config.user);env.put("PGPASSWORD",config.password)
          env.put("PGCONNECT_TIMEOUT","10")
          builder.redirectError(ProcessBuilder.Redirect.DISCARD)
          val process = builder.start()
          val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor((r: Runnable) =>
            val t = new Thread(r,"sanctity-backup-deadline"); t.setDaemon(true); t)
          def stopDump(): Unit =
            process.descendants().forEach(p => { p.destroyForcibly(); () })
            if process.isAlive then { process.destroyForcibly(); () }
          val remaining = TimeUnit.SECONDS.toNanos(seconds)-(System.nanoTime()-started)
          val deadline = timer.schedule(new Runnable { def run(): Unit = stopDump() },
            Math.max(0L,remaining),TimeUnit.NANOSECONDS)
          val dump = FileChannel.open(partial.resolve("catalog.dump"),
            Set(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).asJava,privateFile)
          try
            val input = process.getInputStream
            val buffer = new Array[Byte](65536)
            var total = 0L
            var n = input.read(buffer)
            while n >= 0 do
              within(); total = Math.addExact(total,n.toLong)
              check(total <= MaximumBytes,"backup-dump-bound")
              val bytes = ByteBuffer.wrap(buffer,0,n)
              while bytes.hasRemaining do dump.write(bytes)
              n = input.read(buffer)
            within()
            check(process.waitFor(5,TimeUnit.SECONDS) && process.exitValue() == 0,"backup-dump")
            dump.force(true)
          finally
            stopDump(); deadline.cancel(false); timer.shutdownNow(); dump.close()
          within()
          val link = obj("version" -> str("backup-policy-link-v1"),"enrollment" -> enrollment._2,"head" -> current,
            "authority" -> str("authenticated at capture; fresh independent replay required before restore opening"))
          write(partial.resolve("policy-link.json"),link)
          (required,schema,server,profile,current,field(enrollment._1,"checkpointDigest"))
        }.transact(db.transactor)
        result <- F.blocking {
          val (objects,schema,server,profile,current,checkpointDigest) = captured
          objects.foreach { (hash,size) =>
            within()
            val relative = s"storage/objects/${hash.take(2)}/$hash"
            val source = storage.resolve(s"objects/${hash.take(2)}/$hash")
            check(Files.isRegularFile(source,noFollow) && source.toRealPath() == source && Files.size(source) == size,"backup-source-object")
            val output = partial.resolve(relative)
            Files.createDirectories(output.getParent,privateDirectory)
            val in = FileChannel.open(source,StandardOpenOption.READ,noFollow)
            val out = FileChannel.open(output,Set(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).asJava,privateFile)
            val hashState = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try
              val buffer = ByteBuffer.allocate(65536)
              var n = in.read(buffer)
              while n >= 0 do
                within(); total = Math.addExact(total,n.toLong)
                check(total <= size,"backup-source-changed")
                buffer.flip(); hashState.update(buffer.asReadOnlyBuffer())
                while buffer.hasRemaining do out.write(buffer)
                buffer.clear(); n = in.read(buffer)
              out.force(true)
            finally { in.close(); out.close() }
            check(total == size && hex(hashState.digest()) == hash,"backup-source-integrity")
            forceDirectory(output.getParent)
          }
          val objectRecords = objects.map((h,n) => obj("sha256" -> str(h),"size" -> Json.fromLong(n)))
          val files = Vector("catalog.dump","policy-link.json").map(n => n -> fileRecord(partial.resolve(n))) ++
            objects.zip(objectRecords).map { case ((h,_),record) => s"storage/objects/${h.take(2)}/$h" -> record }
          val manifest = obj("version" -> str(version),"createdAt" -> str(Instant.now().toString),"artifact" -> artifact,
            "schema" -> str(config.schema),"migrations" -> Json.arr(schema*),"postgresVersionNumber" -> Json.fromInt(server),
            "installation" -> str(trust.installation),"capabilities" -> PolicyCapabilities.description(profile),
            "policyHead" -> current,"objects" -> Json.arr(objectRecords*),"files" -> Json.obj(files*))
          write(partial.resolve("bundle.json"),manifest)
          verify(partial)
          within()
          val directories = Files.walk(partial)
          try directories.iterator().asScala.filter(p => Files.isDirectory(p,noFollow)).toVector
            .sortBy(_.getNameCount).reverse.foreach(forceDirectory)
          finally directories.close()
          Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE)
          forceDirectory(target.getParent)
          obj("status" -> str("verified"),"bundle" -> str(manifestDigest(manifest)),"objects" -> Json.fromInt(objects.size),
            "bytes" -> Json.fromLong(files.map((_,j) => field(j,"size").asNumber.get.toLong.get).sum),
            "elapsedMilliseconds" -> Json.fromLong(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)),
            "checkpointDigest" -> checkpointDigest,"policyHeadDigest" -> str(PolicyWire.digest(current)))
        }
      yield result).handleErrorWith { error =>
        F.blocking {
          if Files.exists(partial,noFollow) then write(partial.resolve("incomplete.json"),obj("status" -> str("incomplete")))
        }.attempt *> F.raiseError(error)
      }
    yield result
