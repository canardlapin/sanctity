package sanctity.policy

import io.circe.Json
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.{PrivateKey, PublicKey}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** Single installation, single writer process. Journal files and head are forced before
  * acknowledgment. Startup verifies every signature and transition. No automatic init,
  * truncation, repair, key replacement, rollback or recovery-opening path exists.
  */
final class PolicyWitnessStore private (
    root: Path, installation: String, key: PrivateKey, public: PublicKey,
    lockChannel: FileChannel, lock: FileLock, private var state: PolicyJournal.State,
    private var records: Vector[Json], afterForce: String => Unit) extends AutoCloseable:
  import PolicyWire.*
  import PolicyWitnessStore.*
  private var poisoned = false

  override def close(): Unit = synchronized { lock.release(); lockChannel.close() }

  private def append(kind: String, command: Json): Json =
    check(!poisoned, "store-restart-required")
    val body = PolicyJournal.record(state,kind,command,Instant.now())
    val signed = sign(body,key,public)
    val next = PolicyJournal.applyRecord(state,body)
    try
      write(root,recordName(next.sequence),canonical(signed),replace=false)
      afterForce("record")
      write(root,"head.json",canonical(sign(head(next),key,public)),replace=true)
      afterForce("head")
      state = next
      records = records :+ signed
      signed
    catch
      case _: Exception =>
        poisoned = true // Never continue from memory after an ambiguous disk write.
        throw PolicyFailure("store-outcome-unknown")

  private def result(record: Option[Json] = None): Json = obj("current" -> state.current,
    "record" -> record.getOrElse(Json.Null))

  /** The HTTP layer authenticates both the role and certificate digest before this call. */
  def execute(role: String, certificate: String, request: Json): Json = synchronized {
    check(!poisoned && lock.isValid, "store-unavailable")
    fields(request,Set("action","command","expectedSequence","expectedHash"))
    natural(text(request,"expectedSequence")); hash(text(request,"expectedHash"))
    val action = text(request,"action")
    val command = field(request,"command")
    hash(certificate)
    check(Set("writer","recovery").contains(role), "role")
    if role == "writer" then check(state.writer.contains(certificate) && state.barrier.isEmpty, "writer-fenced")
    def cas(): Unit =
      check(natural(text(request,"expectedSequence")) == state.sequence && text(request,"expectedHash") == state.journalHash, "journal-head")
    def retry(kind: String)(fresh: => Json): Json =
      val op = id(text(command,"operation"))
      state.events.get(op) match
        case None => fresh
        case Some((original,status)) =>
          check(original == command, "operation-payload")
          val valid = if kind == "PREPARE" then Set("PREPARE","COMMIT") else Set(kind)
          check(valid(status), "operation-terminal")
          val matching = records.reverseIterator.find { envelope =>
            val b = verify(envelope,public)
            text(field(b,"command"),"operation") == op && text(b,"kind") == status
          }.getOrElse(throw PolicyFailure("missing-record"))
          result(Some(matching))
    action match
      case "CURRENT" =>
        fields(command,Set.empty)
        result()
      case "ENROLL" =>
        check(role == "recovery", "role")
        retry(action) { cas(); result(Some(append(action,command))) }
      case "PREPARE" =>
        check(role == "writer", "role")
        retry(action) { cas(); result(Some(append(action,command))) }
      case "COMMIT" | "ABORT" =>
        // A recovery credential cannot assert absence in the live catalog. The writer
        // adapter must hold its exclusive catalog lock while attesting COMMIT or ABORT.
        check(role == "writer", "role")
        val op = id(text(command,"operation"))
        state.events.get(op) match
          case Some((original,"PREPARE")) =>
            check(original == command, "operation-payload")
            cas()
            result(Some(append(action,command)))
          case _ => retry(action) { throw PolicyFailure("preparation-required") }
      case "RECOVERY_BARRIER" =>
        check(role == "recovery" && text(command,"operator") == certificate, "role")
        retry(action) { cas(); result(Some(append(action,command))) }
      case "RECORD" =>
        check(role == "recovery", "role")
        fields(command,Set("sequence"))
        val sequence = natural(text(command,"sequence"))
        check(sequence >= 1 && sequence <= state.sequence, "sequence")
        result(Some(records((sequence - 1).toInt)))
      case _ => throw PolicyFailure("action")
  }

  def diagnostics: Json = synchronized {
    obj("version" -> str("policy-readiness-v1"), "installation" -> str(installation),
      "capabilities" -> str(state.capabilities), "enrolled" -> Json.fromBoolean(state.enrolled),
      "sequence" -> str(state.sequence.toString), "epoch" -> str(state.epoch.toString),
      "pending" -> Json.fromBoolean(state.pending.nonEmpty), "barrier" -> Json.fromBoolean(state.barrier.nonEmpty),
      "ready" -> Json.fromBoolean(!poisoned && state.enrolled && state.pending.isEmpty && state.barrier.isEmpty),
      "durabilityBoundary" -> str("local-fsync-store; separate-account and off-host qualification required"))
  }

object PolicyWitnessStore:
  import PolicyWire.*
  private val noFollow = LinkOption.NOFOLLOW_LINKS
  private val directoryMode = PosixFilePermissions.fromString("rwx------")
  private val fileMode = PosixFilePermissions.fromString("rw-------")
  private def recordName(sequence: Long): String = f"record-$sequence%019d.json"
  private def head(state: PolicyJournal.State): Json = obj("version" -> str("policy-store-head-v1"),
    "installation" -> str(state.installation), "sequence" -> str(state.sequence.toString), "digest" -> str(state.journalHash))
  private def forceDirectory(root: Path): Unit =
    val channel = FileChannel.open(root,StandardOpenOption.READ)
    try channel.force(true) finally channel.close()
  private def write(root: Path, name: String, bytes: Array[Byte], replace: Boolean): Unit =
    val target = root.resolve(name)
    check(replace || !Files.exists(target,noFollow), "existing-file")
    val temp = Files.createTempFile(root,".pending-",".json",PosixFilePermissions.asFileAttribute(fileMode))
    try
      val channel = FileChannel.open(temp,StandardOpenOption.WRITE,noFollow)
      try
        val buffer = ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
      finally channel.close()
      if replace then Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
      else Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE)
      forceDirectory(root)
    finally Files.deleteIfExists(temp)
  private def owned(path: Path, directory: Boolean): Unit =
    check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path, "store-path")
    val owner = path.getFileSystem.getUserPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    check(Files.getOwner(path,noFollow) == owner &&
      Files.getPosixFilePermissions(path,noFollow) == (if directory then directoryMode else fileMode) &&
      (if directory then Files.isDirectory(path,noFollow) else Files.isRegularFile(path,noFollow)), "store-permissions")
  private def read(root: Path, name: String): Json =
    val p = root.resolve(name)
    owned(p,false)
    check(Files.size(p) <= maximumBytes, "size")
    val input = Files.newInputStream(p,noFollow)
    try parse(input.readNBytes(maximumBytes+1)) finally input.close()

  /** Explicit initialization; cannot be run over any existing directory. Private signing
    * key and client/recovery trust belong outside this store and outside catalog backups.
    */
  def initialize(root: Path, installation: String, key: PrivateKey, public: PublicKey): Unit =
    id(installation)
    owned(root.getParent,true)
    check(!Files.exists(root,noFollow), "existing-store")
    check(verifyBytes(Array.emptyByteArray,signBytes(Array.emptyByteArray,key),public), "key-pair")
    Files.createDirectory(root,PosixFilePermissions.asFileAttribute(directoryMode))
    val header = obj("version" -> str("policy-store-v1"),"installation" -> str(installation),"key" -> str(keyId(public)))
    write(root,"identity.json",canonical(sign(header,key,public)),replace=false)
    write(root,"head.json",canonical(sign(head(PolicyJournal.State(installation)),key,public)),replace=false)
    Files.createFile(root.resolve("writer.lock"),PosixFilePermissions.asFileAttribute(fileMode))
    forceDirectory(root)
    forceDirectory(root.getParent)

  def open(root: Path, installation: String, key: PrivateKey, public: PublicKey): PolicyWitnessStore =
    openObserved(root,installation,key,public,_ => ())

  private[policy] def openObserved(root: Path, installation: String, key: PrivateKey, public: PublicKey,
      afterForce: String => Unit): PolicyWitnessStore =
    id(installation)
    owned(root,true)
    check(verifyBytes(Array.emptyByteArray,signBytes(Array.emptyByteArray,key),public), "key-pair")
    val header = verify(read(root,"identity.json"),public)
    fields(header,Set("version","installation","key"))
    check(text(header,"version") == "policy-store-v1" && text(header,"installation") == installation && text(header,"key") == keyId(public), "store-identity")
    owned(root.resolve("writer.lock"),false)
    val channel = FileChannel.open(root.resolve("writer.lock"),StandardOpenOption.WRITE,noFollow)
    var held: Option[FileLock] = None
    try
      val lock = Option(channel.tryLock()).getOrElse(throw PolicyFailure("store-locked"))
      held = Some(lock)
      val expected = verify(read(root,"head.json"),public)
      fields(expected,Set("version","installation","sequence","digest"))
      check(text(expected,"version") == "policy-store-head-v1" && text(expected,"installation") == installation, "head-identity")
      val expectedSequence = natural(text(expected,"sequence"))
      hash(text(expected,"digest"))
      val entries = Files.list(root)
      val names = try entries.iterator().asScala.map(_.getFileName.toString).toVector.sorted finally entries.close()
      check(names.forall(n => Set("writer.lock","identity.json","head.json").contains(n) ||
        n.matches("record-[0-9]{19}\\.json") || n.matches("\\.pending-[A-Za-z0-9_-]+\\.json")), "unknown-store-file")
      var state = PolicyJournal.State(installation)
      var matched = expectedSequence == 0 && text(expected,"digest") == zero
      val records = names.filter(_.startsWith("record-")).map { name =>
        check(name == recordName(increment(state.sequence)), "journal-gap")
        val envelope = read(root,name)
        state = PolicyJournal.applyRecord(state,verify(envelope,public))
        if state.sequence == expectedSequence then matched = state.journalHash == text(expected,"digest")
        envelope
      }
      check(matched, "journal-truncated-or-forked")
      // A crash can leave a durable record ahead of the head marker. Only authenticated
      // contiguous forward replay is permitted; missing/older records never repair it.
      if state.sequence > expectedSequence then write(root,"head.json",canonical(sign(head(state),key,public)),replace=true)
      new PolicyWitnessStore(root,installation,key,public,channel,lock,state,records,afterForce)
    catch
      case error: Exception =>
        held.foreach(_.release())
        channel.close()
        throw error
