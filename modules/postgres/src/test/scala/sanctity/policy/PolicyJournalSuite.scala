package sanctity.policy

import io.circe.{Json, Printer}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

private[policy] object PolicyFixtures:
  import PolicyWire.*
  val installation = "11111111-1111-4111-8111-111111111111"
  val writer = "1" * 64
  val recovery = "2" * 64
  val now = Instant.parse("2026-10-14T12:00:00Z")
  def operation(): String = UUID.randomUUID().toString
  def enrollment(writer: String = writer): Json =
    val bytes = "synthetic checkpoint bytes; catalog integration is qualified separately".getBytes(UTF_8)
    obj("version" -> str(PolicyJournal.enrollmentVersion),"capabilities" -> str(PolicyCapabilities.digest),
      "installation" -> str(installation),"operation" -> str(operation()),"epoch" -> str("0"),
      "revision" -> str("7"),"appliedHash" -> str("7" * 64),"writer" -> str(writer),
      "checkpoint" -> str(base64(bytes)),"checkpointDigest" -> str(digest(bytes)))
  def event(previous: PolicyJournal.State, kind: String = "GrantRevoked", op: String = operation()): Json =
    val changes = obj("version" -> Json.fromInt(1),"after" -> str("synthetic-revoked"))
    val intent = obj("kind" -> str(kind),"scope" -> str("synthetic-dataset"),"key" -> str(op),"changes" -> changes)
    val intentBytes = intent.printWith(Printer.noSpaces.copy(sortKeys=true)).getBytes(UTF_8)
    val source = obj("version" -> str("policy-local-v2"),"installation" -> str(installation),
      "epoch" -> str(previous.epoch.toString),"event" -> str(op),"revision" -> str((previous.revision+1).toString),
      "previousRevision" -> str(previous.revision.toString),"previousHash" -> str(previous.appliedHash),
      "kind" -> str(kind),"actor" -> str(installation),"databaseActor" -> str("synthetic-operator"),
      "scope" -> str("synthetic-dataset"),"key" -> str(op),"reason" -> str(kind),"approvalReferences" -> Json.arr(),
      "intentDigest" -> str(digest(intentBytes)),"at" -> str(now.toString),"request" -> Json.Null,
      "changes" -> changes,"extra" -> obj())
    val bytes = source.printWith(Printer.noSpaces.copy(sortKeys=true)).getBytes(UTF_8)
    val applied = sanctity.kernel.Digest.framed("sanctity.policy-local.v2",Vector(previous.appliedHash,op,
      (previous.revision+1).toString,digest(bytes))).value
    obj("version" -> str(PolicyJournal.eventVersion),"capabilities" -> str(PolicyCapabilities.digest),
      "installation" -> str(installation),"epoch" -> str(previous.epoch.toString),"operation" -> str(op),
      "revision" -> str((previous.revision+1).toString),"previousRevision" -> str(previous.revision.toString),
      "previousHash" -> str(previous.appliedHash),"appliedHash" -> str(applied),"kind" -> str(kind),
      "payload" -> str(base64(bytes)),"payloadDigest" -> str(digest(bytes)))
  def barrier(state: PolicyJournal.State, who: String = recovery): Json = obj("operation" -> str(operation()),
    "installation" -> str(installation),"epoch" -> str((state.epoch+1).toString),"reason" -> str("synthetic-primary-loss"),
    "operator" -> str(who),"at" -> str(now.toString),"challenge" -> str(operation()))
  def request(action: String, command: Json, sequence: Long = 0, head: String = zero): Json =
    PolicyWitnessClient.request(action,command,sequence,head)
  def step(state: PolicyJournal.State, kind: String, command: Json): PolicyJournal.State =
    PolicyJournal.applyRecord(state,PolicyJournal.record(state,kind,command,now))
  def enrolled: PolicyJournal.State = step(PolicyJournal.State(installation),"ENROLL",enrollment())
  def change(value: Json, key: String, to: String): Json = value.mapObject(_.add(key,str(to)))
  def directory(): Path =
    val root = Files.createTempDirectory("sanctity-witness-test-").toRealPath()
    Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"))
    root
  def remove(root: Path): Unit =
    val entries = Files.walk(root)
    try entries.iterator().asScala.toVector.reverse.foreach(Files.delete) finally entries.close()

final class PolicyJournalSuite extends munit.FunSuite:
  import PolicyWire.*
  import PolicyFixtures.*
  test("one pending preparation; COMMIT advances policy and ABORT only journal order") {
    val initial = enrolled
    val e = event(initial)
    val prepared = step(initial,"PREPARE",e)
    assertEquals(prepared.revision,initial.revision)
    intercept[PolicyFailure](step(prepared,"PREPARE",event(initial)))
    val aborted = step(prepared,"ABORT",e)
    assertEquals(aborted.revision,initial.revision)
    assertEquals(aborted.appliedHash,initial.appliedHash)
    val next = event(aborted)
    val committed = step(step(aborted,"PREPARE",next),"COMMIT",next)
    assertEquals(committed.sequence,5L)
    assertEquals(committed.revision,initial.revision+1)
    intercept[PolicyFailure](step(committed,"ABORT",next))
    intercept[PolicyFailure](step(aborted,"PREPARE",e))
  }
  test("barrier advances epoch and retains the unresolved event; no terminal guess or reopening") {
    val initial = enrolled
    val e = event(initial)
    val pending = step(initial,"PREPARE",e)
    val closed = step(pending,"RECOVERY_BARRIER",barrier(pending))
    assertEquals(closed.pending,Some(e))
    assertEquals(closed.writer,None)
    assertEquals(closed.epoch,1L)
    for kind <- Vector("PREPARE","COMMIT","ABORT","RELEASE") do intercept[PolicyFailure](step(closed,kind,e))
  }
  test("reordered, missing, unknown, foreign and changed-head records are rejected") {
    val initial = enrolled
    val r = PolicyJournal.record(initial,"PREPARE",event(initial),now)
    for (k,v) <- Vector("sequence" -> "3","previous" -> zero,"version" -> "future", "kind" -> "FUTURE",
        "installation" -> operation()) do intercept[PolicyFailure](PolicyJournal.applyRecord(initial,change(r,k,v)))
    intercept[PolicyFailure](step(initial,"ENROLL",enrollment()))
  }
  test("closed capabilities, exact source payload identity and integer bounds") {
    val e = event(enrolled)
    for (k,v) <- Vector("kind" -> "SessionIssued","version" -> "future","capabilities" -> zero,"revision" -> "9223372036854775808",
        "installation" -> operation(),"epoch" -> "9","operation" -> operation(),"payloadDigest" -> zero,"appliedHash" -> zero) do
      intercept[PolicyFailure](PolicyJournal.validateEvent(change(e,k,v)))
    intercept[PolicyFailure](PolicyJournal.validateEvent(e.mapObject(_.add("ignored",Json.Null))))
    assertEquals(PolicyCapabilities.kinds.size,19)
  }
  test("event and checkpoint bounds leave room for complete signed acknowledgments") {
    val tooLarge = new Array[Byte](maximumEventBytes+1)
    val e = event(enrolled).mapObject(_.add("payload",str(base64(tooLarge))).add("payloadDigest",str(digest(tooLarge))))
    intercept[PolicyFailure](PolicyJournal.validateEvent(e))
    val checkpoint = new Array[Byte](maximumCheckpointBytes+1)
    val enrollmentTooLarge = enrollment().mapObject(_.add("checkpoint",str(base64(checkpoint))).add("checkpointDigest",str(digest(checkpoint))))
    intercept[PolicyFailure](PolicyJournal.validateEnrollment(enrollmentTooLarge))
    val valid = enrollment().mapObject(_.add("checkpoint",str(base64(checkpoint.drop(1)))).add("checkpointDigest",str(digest(checkpoint.drop(1)))))
    PolicyJournal.validateEnrollment(valid)
    val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val record = PolicyJournal.record(PolicyJournal.State(installation),"ENROLL",valid,now)
    val state = PolicyJournal.applyRecord(PolicyJournal.State(installation),record)
    val response = sign(obj("current" -> state.current,"record" -> sign(record,key.getPrivate,key.getPublic)),key.getPrivate,key.getPublic)
    assert(canonical(response).length < maximumBytes)
  }
  test("durable store preserves exact retries and terminal outcomes through reopen") {
    val root = directory(); val storePath = root.resolve("journal")
    val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    try
      PolicyWitnessStore.initialize(storePath,installation,key.getPrivate,key.getPublic)
      val store = PolicyWitnessStore.open(storePath,installation,key.getPrivate,key.getPublic)
      val enroll = enrollment()
      val first = store.execute("recovery",recovery,request("ENROLL",enroll))
      val current = field(first,"current")
      val s = step(PolicyJournal.State(installation),"ENROLL",enroll)
      val e = event(s)
      val prepare = request("PREPARE",e,1,text(current,"journalHash"))
      val prepared = store.execute("writer",writer,prepare)
      assertEquals(store.execute("writer",writer,prepare),prepared)
      intercept[PolicyFailure](store.execute("writer",writer,request("PREPARE",change(e,"kind","GrantIssued"))))
      store.close()
      val restarted = PolicyWitnessStore.open(storePath,installation,key.getPrivate,key.getPublic)
      try
        val state = field(restarted.execute("writer",writer,request("CURRENT",obj())),"current")
        assertEquals(field(state,"pending"),e)
        val commit = request("COMMIT",e,natural(text(state,"sequence")),text(state,"journalHash"))
        val committed = restarted.execute("writer",writer,commit)
        assertEquals(restarted.execute("writer",writer,commit),committed)
        assertEquals(restarted.execute("writer",writer,prepare),committed)
        intercept[PolicyFailure](restarted.execute("writer",writer,request("ABORT",e)))
      finally restarted.close()
    finally remove(root)
  }
  test("simultaneous writer processes cannot open a store; unknown role and unsigned identities cannot act") {
    val root = directory(); val path = root.resolve("journal")
    val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    try
      PolicyWitnessStore.initialize(path,installation,key.getPrivate,key.getPublic)
      val store = PolicyWitnessStore.open(path,installation,key.getPrivate,key.getPublic)
      try
        intercept[Exception](PolicyWitnessStore.open(path,installation,key.getPrivate,key.getPublic))
        intercept[PolicyFailure](store.execute("writer",writer,request("ENROLL",enrollment())))
        intercept[PolicyFailure](store.execute("admin",recovery,request("CURRENT",obj())))
        store.execute("recovery",recovery,request("ENROLL",enrollment()))
        intercept[PolicyFailure](store.execute("writer",recovery,request("CURRENT",obj())))
        intercept[PolicyFailure](store.execute("recovery",recovery,request("ABORT",event(enrolled))))
      finally store.close()
    finally remove(root)
  }
  for damage <- Vector("missing-head","missing-tail","missing-middle","tamper","foreign-key","foreign-installation","permissions","unknown-file") do
    test(s"startup refuses $damage without automatic repair") {
      val root = directory(); val path = root.resolve("journal")
      val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      try
        PolicyWitnessStore.initialize(path,installation,key.getPrivate,key.getPublic)
        val store = PolicyWitnessStore.open(path,installation,key.getPrivate,key.getPublic)
        val enroll = enrollment()
        val result = store.execute("recovery",recovery,request("ENROLL",enroll))
        val s = step(PolicyJournal.State(installation),"ENROLL",enroll)
        store.execute("writer",writer,request("PREPARE",event(s),1,text(field(result,"current"),"journalHash")))
        store.close()
        damage match
          case "missing-head" => Files.delete(path.resolve("head.json"))
          case "missing-tail" => Files.delete(path.resolve("record-0000000000000000002.json"))
          case "missing-middle" => Files.delete(path.resolve("record-0000000000000000001.json"))
          case "tamper" => Files.writeString(path.resolve("record-0000000000000000002.json"),"{}")
          case "permissions" => Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwxr-xr-x"))
          case "unknown-file" => Files.writeString(path.resolve("unexpected"),"x")
          case _ => ()
        intercept[Exception](PolicyWitnessStore.open(path,if damage == "foreign-installation" then operation() else installation,
          if damage == "foreign-key" then other.getPrivate else key.getPrivate,
          if damage == "foreign-key" then other.getPublic else key.getPublic))
      finally remove(root)
    }
  for boundary <- Vector("record","head") do
    test(s"ambiguous disk failure after $boundary force poisons live store; restart replays forward only") {
      val root = directory(); val path = root.resolve("journal")
      val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      try
        PolicyWitnessStore.initialize(path,installation,key.getPrivate,key.getPublic)
        val store = PolicyWitnessStore.openObserved(path,installation,key.getPrivate,key.getPublic,
          stage => if stage == boundary then throw new java.io.IOException("synthetic"))
        val enroll = enrollment()
        intercept[PolicyFailure](store.execute("recovery",recovery,request("ENROLL",enroll)))
        intercept[PolicyFailure](store.execute("recovery",recovery,request("CURRENT",obj())))
        store.close()
        val restarted = PolicyWitnessStore.open(path,installation,key.getPrivate,key.getPublic)
        try
          val response = restarted.execute("recovery",recovery,request("ENROLL",enroll))
          assertEquals(text(field(response,"current"),"sequence"),"1")
        finally restarted.close()
      finally remove(root)
    }
