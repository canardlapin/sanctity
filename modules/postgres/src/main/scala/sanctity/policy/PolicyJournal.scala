package sanctity.policy

import io.circe.Json
import java.time.Instant

/** The wire capability set is closed. Enrollment/upgrade must explicitly add new producers. */
object PolicyCapabilities:
  val version = "current-authority-v1"
  val kinds: Set[String] = Set("CreateLab","RenameLab","CreateProject","RenameProject",
    "AddMember","EndMember","AddParticipant","EndParticipant","PlaceNewDataset",
    "AddSteward","EndSteward","SetInheritance","ArchiveProject","ArchiveLab","DisablePrincipal",
    "GrantIssued","GrantRevoked","CredentialIssued","CredentialRevoked")
  val inventory: Json = PolicyWire.obj(
    "version" -> PolicyWire.str(version),
    "events" -> Json.arr(kinds.toVector.sorted.map(PolicyWire.str)*),
    "historicalFormats" -> Json.arr(PolicyWire.str("organization-local-v1"),PolicyWire.str("policy-local-v2")),
    "unsupportedProducers" -> Json.arr(Vector("identity","sponsorship","session","lifecycle","retention","hold").map(PolicyWire.str)*))
  val digest: String = PolicyWire.digest(inventory)

  // Never change v1's signed identity. New installations enroll this separately
  // named profile; existing journals retain their enrolled profile indefinitely.
  val governedKinds: Set[String] = kinds ++ Set("RenameDataset", "TransferSteward", "RequestMove",
    "ApproveMove", "CommitMove", "WithdrawMove", "RecoverSteward")
  val governedInventory: Json = inventory.mapObject(_.add("version", PolicyWire.str("current-authority-v2"))
    .add("events", Json.arr(governedKinds.toVector.sorted.map(PolicyWire.str)*)))
  val governedDigest: String = PolicyWire.digest(governedInventory)
  def supported(value: String): Boolean = value == digest || value == governedDigest
  def events(value: String): Set[String] =
    PolicyWire.check(supported(value), "capabilities")
    if value == digest then kinds else governedKinds
  def description(value: String): Json =
    PolicyWire.check(supported(value), "capabilities")
    if value == digest then inventory else governedInventory

/** A signed record reducer, shared by the live witness and offline verification.
  * Recovery barriers can close admission, but this component cannot reopen a catalog.
  */
object PolicyJournal:
  import PolicyWire.*
  val recordVersion = "policy-journal-v1"
  val eventVersion = "policy-event-v1"
  val enrollmentVersion = "policy-enrollment-v1"

  final case class State(installation: String, sequence: Long = 0, journalHash: String = zero,
      epoch: Long = 0, revision: Long = 0, appliedHash: String = zero,
      enrolled: Boolean = false, writer: Option[String] = None,
      pending: Option[Json] = None, barrier: Option[Json] = None,
      events: Map[String, (Json, String)] = Map.empty,
      capabilities: String = PolicyCapabilities.digest):
    def current: Json = obj("installation" -> str(installation), "sequence" -> str(sequence.toString),
      "journalHash" -> str(journalHash), "epoch" -> str(epoch.toString), "revision" -> str(revision.toString),
      "appliedHash" -> str(appliedHash), "enrolled" -> Json.fromBoolean(enrolled),
      "writer" -> writer.fold(Json.Null)(str), "pending" -> pending.getOrElse(Json.Null),
      "barrier" -> barrier.getOrElse(Json.Null), "capabilities" -> str(capabilities))

  def validateEvent(event: Json): Unit =
    fields(event,Set("version","capabilities","installation","epoch","operation","revision","previousRevision",
      "previousHash","appliedHash","kind","payload","payloadDigest"))
    check(text(event,"version") == eventVersion && PolicyCapabilities.supported(text(event,"capabilities")), "capabilities")
    id(text(event,"installation")); id(text(event,"operation"))
    natural(text(event,"epoch"))
    val previous = natural(text(event,"previousRevision"))
    check(natural(text(event,"revision")) == increment(previous), "revision")
    hash(text(event,"previousHash")); hash(text(event,"appliedHash"))
    check(PolicyCapabilities.events(text(event,"capabilities")).contains(text(event,"kind")), "event-kind")
    val bytes = unbase64(text(event,"payload"))
    check(bytes.nonEmpty && bytes.length <= maximumEventBytes && digest(bytes) == hash(text(event,"payloadDigest")), "payload-size-digest")
    val source = parseCatalog(bytes)
    fields(source,Set("version","installation","epoch","event","revision","previousRevision","previousHash",
      "actor","databaseActor","scope","key","kind","reason","approvalReferences","intentDigest","at","request","changes","extra"))
    check(text(source,"version") == "policy-local-v2", "payload-version")
    id(text(source,"actor")); instant(text(source,"at")); hash(text(source,"intentDigest"))
    for name <- Vector("databaseActor","scope","key","reason") do
      check(text(source,name).nonEmpty && text(source,name).length <= 500, "payload-field")
    check(field(source,"approvalReferences").asArray.isDefined, "payload-field")
    val requested = field(source,"request")
    val intent = if requested.isNull then obj("kind" -> field(source,"kind"), "scope" -> field(source,"scope"),
      "key" -> field(source,"key"),"changes" -> field(source,"changes")) else requested
    val intentBytes = intent.printWith(io.circe.Printer.noSpaces.copy(sortKeys=true)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
    check(text(source,"intentDigest") == digest(intentBytes), "intent-digest")
    for (outer,inner) <- Vector("installation" -> "installation", "epoch" -> "epoch", "operation" -> "event",
        "revision" -> "revision", "previousRevision" -> "previousRevision", "previousHash" -> "previousHash", "kind" -> "kind") do
      check(text(event,outer) == text(source,inner), "payload-binding")
    val applied = sanctity.kernel.Digest.framed("sanctity.policy-local.v2",Vector(text(event,"previousHash"),
      text(event,"operation"),text(event,"revision"),text(event,"payloadDigest"))).value
    check(text(event,"appliedHash") == applied, "applied-hash")

  def validateEnrollment(enrollment: Json): Unit =
    fields(enrollment,Set("version","capabilities","installation","operation","epoch","revision","appliedHash",
      "writer","checkpoint","checkpointDigest"))
    check(text(enrollment,"version") == enrollmentVersion && PolicyCapabilities.supported(text(enrollment,"capabilities")), "capabilities")
    id(text(enrollment,"installation")); id(text(enrollment,"operation"))
    natural(text(enrollment,"epoch")); natural(text(enrollment,"revision"))
    hash(text(enrollment,"appliedHash")); hash(text(enrollment,"writer"))
    val bytes = unbase64(text(enrollment,"checkpoint"))
    check(bytes.nonEmpty && bytes.length <= maximumCheckpointBytes && digest(bytes) == hash(text(enrollment,"checkpointDigest")), "checkpoint-size-digest")

  /** The command is recorded exactly; changing a payload under an operation ID never retries. */
  def transition(state: State, kind: String, command: Json): State =
    kind match
      case "ENROLL" =>
        validateEnrollment(command)
        check(!state.enrolled && state.sequence == 0, "already-enrolled")
        check(text(command,"installation") == state.installation, "installation")
        state.copy(enrolled=true, epoch=natural(text(command,"epoch")), revision=natural(text(command,"revision")),
          appliedHash=text(command,"appliedHash"), writer=Some(text(command,"writer")), capabilities=text(command,"capabilities"),
          events=Map(text(command,"operation") -> (command -> "ENROLL")))
      case "PREPARE" =>
        validateEvent(command)
        check(text(command,"capabilities") == state.capabilities, "enrolled-capabilities")
        check(state.enrolled && state.barrier.isEmpty && state.pending.isEmpty, "closed-or-pending")
        check(text(command,"installation") == state.installation && natural(text(command,"epoch")) == state.epoch, "installation-epoch")
        check(natural(text(command,"previousRevision")) == state.revision && text(command,"previousHash") == state.appliedHash, "policy-head")
        val op = text(command,"operation")
        check(!state.events.contains(op), "operation-used")
        state.copy(pending=Some(command), events=state.events.updated(op,command -> "PREPARE"))
      case "COMMIT" | "ABORT" =>
        validateEvent(command)
        check(text(command,"capabilities") == state.capabilities, "enrolled-capabilities")
        check(state.barrier.isEmpty && state.pending.contains(command) && natural(text(command,"epoch")) == state.epoch,
          "exact-preparation-required")
        val op = text(command,"operation")
        if kind == "COMMIT" then
          state.copy(pending=None, revision=natural(text(command,"revision")), appliedHash=text(command,"appliedHash"),
            events=state.events.updated(op,command -> kind))
        else state.copy(pending=None,events=state.events.updated(op,command -> kind))
      case "RECOVERY_BARRIER" =>
        fields(command,Set("operation","installation","epoch","reason","operator","at","challenge"))
        id(text(command,"operation")); id(text(command,"challenge")); hash(text(command,"operator"))
        instant(text(command,"at"))
        check(text(command,"reason").nonEmpty && text(command,"reason").length <= 500, "reason")
        check(state.enrolled && state.barrier.isEmpty && text(command,"installation") == state.installation, "barrier")
        check(natural(text(command,"epoch")) == increment(state.epoch), "epoch")
        check(!state.events.contains(text(command,"operation")), "operation-used")
        // Preserve an unresolved intent. A barrier never makes its outcome disappear.
        state.copy(epoch=increment(state.epoch),writer=None,barrier=Some(command),
          events=state.events.updated(text(command,"operation"),command -> kind))
      case _ => throw PolicyFailure("record-kind")

  def record(state: State, kind: String, command: Json, at: Instant): Json =
    transition(state,kind,command)
    obj("version" -> str(recordVersion), "installation" -> str(state.installation),
      "sequence" -> str(increment(state.sequence).toString), "previous" -> str(state.journalHash),
      "kind" -> str(kind), "command" -> command, "at" -> str(at.toString))

  def applyRecord(state: State, record: Json): State =
    fields(record,Set("version","installation","sequence","previous","kind","command","at"))
    check(text(record,"version") == recordVersion && text(record,"installation") == state.installation, "record-version-installation")
    check(natural(text(record,"sequence")) == increment(state.sequence) && text(record,"previous") == state.journalHash, "journal-order")
    instant(text(record,"at"))
    val next = transition(state,text(record,"kind"),field(record,"command"))
    next.copy(sequence=increment(state.sequence),journalHash=digest(record))
