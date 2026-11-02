package sanctity.postgres

import io.circe.Json
import sanctity.policy.*
import java.net.URI
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.{Executors,TimeUnit}
import scala.jdk.CollectionConverters.*

/** Files are verified privately first. Import, the closed gate and durable session
  * become visible in one PostgreSQL transaction, even for a V013 predecessor.
  */
private[postgres] object RecoveryBundleRestore:
  import PolicyWire.{check,text,field}
  private val noFollow = LinkOption.NOFOLLOW_LINKS
  private val directory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
  private val file = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
  private def bytes(j: Json): Array[Byte] = j.printWith(io.circe.Printer.noSpaces.copy(sortKeys=true)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
  private def force(path: Path, isDirectory: Boolean): Unit =
    val c = FileChannel.open(path,if isDirectory then StandardOpenOption.READ else StandardOpenOption.WRITE)
    try c.force(true) finally c.close()
  private def write(path: Path, value: Array[Byte]): Unit =
    Files.createFile(path,file)
    Files.write(path,value,StandardOpenOption.WRITE)
    force(path,false)
  def storage(manifest: Json, bundle: Path, target: Path, operation: String): Unit =
    val identity = Json.obj("operation" -> Json.fromString(operation),"backup" -> Json.fromString(BackupBundle.manifestDigest(manifest)))
    val marker = target.resolve("recovery-session.json")
    if Files.exists(target,noFollow) then
      check(target.toRealPath() == target && Files.isRegularFile(marker,noFollow) &&
        java.util.Arrays.equals(Files.readAllBytes(marker),bytes(identity)),"restore-storage-owner")
    else
      check(target.isAbsolute && target.normalize() == target && target.getParent.toRealPath() == target.getParent &&
        !target.startsWith(bundle) && !bundle.startsWith(target),"restore-storage-target")
      val partial = target.resolveSibling(".pending-restore-"+UUID.randomUUID())
      Files.createDirectory(partial,directory)
      val started = System.nanoTime()
      field(manifest,"objects").asArray.get.foreach { record =>
        val hash = text(record,"sha256"); val relative = s"objects/${hash.take(2)}/$hash"
        val input = bundle.resolve("storage").resolve(relative)
        val output = partial.resolve(relative)
        Files.createDirectories(output.getParent,directory)
        Files.createFile(output,file)
        val in = Files.newInputStream(input,noFollow)
        val out = Files.newOutputStream(output,StandardOpenOption.WRITE)
        try
          val buffer = new Array[Byte](65536)
          var n = in.read(buffer); var size = 0L
          val expected = field(record,"size").asNumber.get.toLong.get
          while n >= 0 do
            size += n
            check(size <= expected && System.nanoTime()-started < TimeUnit.MINUTES.toNanos(5),"restore-copy-bound")
            out.write(buffer,0,n); n = in.read(buffer)
          check(size == expected,"restore-copy-size")
        finally { in.close();out.close() }
        check(BackupBundle.hashFile(output) == hash,"restore-copy-integrity")
        force(output,false)
      }
      write(partial.resolve("recovery-session.json"),bytes(identity))
      val walk = Files.walk(partial)
      try walk.iterator().asScala.filter(p => Files.isDirectory(p,noFollow)).toVector.sortBy(_.getNameCount).reverse.foreach(p => force(p,true))
      finally walk.close()
      Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE);force(target.getParent,true)
    verifyStorage(manifest,target)

  def verifyStorage(manifest: Json, target: Path): Unit =
    check(target.toRealPath() == target,"restore-storage-alias")
    val expected = field(manifest,"objects").asArray.get.map { record =>
      val hash = text(record,"sha256"); val relative = s"objects/${hash.take(2)}/$hash"
      val path = target.resolve(relative)
      check(path.toRealPath() == path && Files.size(path) == field(record,"size").asNumber.get.toLong.get &&
        BackupBundle.hashFile(path) == hash,"restore-storage-integrity")
      relative
    }.toSet + "recovery-session.json"
    val walk = Files.walk(target)
    try
      val observed = walk.iterator().asScala.filter(p => !Files.isDirectory(p,noFollow)).map { p =>
        check(!Files.isSymbolicLink(p),"restore-storage-symlink");target.relativize(p).toString
      }.toSet
      check(observed == expected,"restore-storage-inventory")
    finally walk.close()

  private def process(config: DatabaseConfig, executable: String, args: String*): ProcessBuilder =
    val uri = URI.create(config.jdbcUrl.stripPrefix("jdbc:"))
    check(uri.getScheme == "postgresql" && uri.getHost != null && uri.getUserInfo == null && uri.getQuery == null &&
      uri.getPath.matches("/[A-Za-z0-9_-]+"),"restore-connection-shape")
    val builder = new ProcessBuilder((executable +: args).asJava)
    val env = builder.environment()
    env.put("PGHOST",uri.getHost);env.put("PGPORT",(if uri.getPort < 0 then 5432 else uri.getPort).toString)
    env.put("PGDATABASE",uri.getPath.drop(1));env.put("PGUSER",config.user);env.put("PGPASSWORD",config.password)
    env.put("PGCONNECT_TIMEOUT","10")
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
  private def literal(value: String): String = "'"+value.replace("'","''")+"'"

  def catalog(config: DatabaseConfig, bundle: Path, manifest: Json, plan: Json,
      migrations: Vector[(Int,String,String)], pgRestore: String, psql: String): Unit =
    val schema = config.schema
    check(text(manifest,"schema") == schema,"restore-schema")
    val expected = field(manifest,"migrations")
    val sourceCount = expected.asArray.get.size
    check(Set(13,14)(sourceCount) && expected == Json.arr(migrations.take(sourceCount).map((v,c,_) =>
      Json.obj("version" -> Json.fromInt(v),"checksum" -> Json.fromString(c)))*),"restore-migrations")
    val receiver = process(config,psql,"-X","--quiet","--set=ON_ERROR_STOP=1")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    def stop(p: Process): Unit =
      p.descendants().forEach(child => {child.destroyForcibly();()})
      if p.isAlive then {p.destroyForcibly();()}
    val sender = try process(config,pgRestore,"--exit-on-error","--no-owner","--no-acl","--file=-")
      .redirectInput(bundle.resolve("catalog.dump").toFile).start()
    catch case e: Exception =>
      stop(receiver)
      throw e
    val timer = Executors.newSingleThreadScheduledExecutor((r: Runnable) =>
      val t = new Thread(r,"sanctity-restore-deadline");t.setDaemon(true);t)
    val deadline = timer.schedule(new Runnable {def run(): Unit = {stop(sender);stop(receiver)}},300,TimeUnit.SECONDS)
    val input = sender.getInputStream;val output = receiver.getOutputStream
    def sql(value: String): Unit = output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    try
      sql(s"BEGIN; SELECT pg_advisory_xact_lock(hashtextextended(${literal(schema)},0));\n")
      val buffer = new Array[Byte](65536);var n = input.read(buffer);var total = 0L
      while n >= 0 do
        total += n;check(total <= BackupBundle.MaximumBytes,"restore-sql-bound")
        output.write(buffer,0,n);n = input.read(buffer)
      check(sender.waitFor(5,TimeUnit.SECONDS) && sender.exitValue() == 0,"restore-dump")
      // The private schema was absent before import; no ordinary runtime receives
      // grants, and no imported row is visible before this closed-state commit.
      sql(s"\nSET search_path TO \"$schema\",pg_catalog;\n")
      sql("DO $$ BEGIN IF (SELECT jsonb_agg(jsonb_build_object('version',version,'checksum',checksum) ORDER BY version) FROM schema_migration) IS DISTINCT FROM "+
        literal(expected.noSpaces)+"::jsonb THEN RAISE EXCEPTION 'restore schema identity'; END IF; END $$;\n")
      migrations.drop(sourceCount).foreach { (version,checksum,source) =>
        sql(source+"\n")
        sql(s"INSERT INTO schema_migration(version,checksum) VALUES($version,${literal(checksum)});\n")
      }
      sql("UPDATE repository_state SET recovery_required=TRUE;\n")
      val columns = Vector("operation","backupDigest","operator","recoveryIdentity","writerIdentity").map(k => literal(text(plan,k)))
      sql("INSERT INTO organization_recovery_session(operation,backup_digest,operator_id,recovery_identity,writer_identity,phase,plan) VALUES("+
        columns.mkString(",")+",'Restored',"+literal(plan.noSpaces)+"::jsonb);\nCOMMIT;\n")
      output.close()
      check(receiver.waitFor(10,TimeUnit.SECONDS) && receiver.exitValue() == 0,"restore-transaction")
    finally
      stop(sender);stop(receiver);deadline.cancel(false);timer.shutdownNow()
      input.close();output.close()
