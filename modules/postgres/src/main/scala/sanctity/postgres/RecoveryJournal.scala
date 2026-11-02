package sanctity.postgres

import io.circe.Json
import sanctity.policy.*

/** Every read is tied to fresh authenticated CURRENT responses. No backup timestamp
  * or locally supplied transcript can substitute for the live witness.
  */
private[postgres] object RecoveryJournal:
  import PolicyWire.*
  final case class Verified(current: Json, enrollment: Json, projection: RecoveryProjection.Projection,
      records: Vector[Json], journalDigest: String, writerHistory: Set[String]):
    def summary: Json = obj("head" -> current,"checkpointDigest" -> field(enrollment,"checkpointDigest"),
      "projectionDigest" -> str(projection.digest),"journalDigest" -> str(journalDigest),
      "records" -> str(records.size.toString))

  def current(trust: PolicyConnection): Json =
    val result = field(trust.client.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current")
    check(text(result,"installation") == trust.installation && field(result,"enrolled").asBoolean.contains(true) &&
      PolicyCapabilities.supported(text(result,"capabilities")),"recovery-live-installation")
    result

  def read(trust: PolicyConnection, enrollmentCertificate: Json, backupHead: Json,
      barrier: Option[Json]): Verified =
    val head = current(trust)
    check(field(head,"pending").isNull,"recovery-unresolved-preparation")
    barrier match
      case None => check(field(head,"barrier").isNull,"recovery-existing-barrier")
      case Some(expected) => check(field(head,"barrier") == expected && field(head,"writer").isNull,"recovery-barrier-binding")
    val maximum = natural(text(head,"sequence"))
    check(maximum > 0 && maximum <= 10000,"recovery-history-bound")
    val backupSequence = natural(text(backupHead,"sequence"))
    check(backupSequence > 0 && backupSequence <= maximum,"recovery-backup-prefix")
    val started = System.nanoTime()
    var state = PolicyJournal.State(trust.installation)
    var projection: Option[RecoveryProjection.Projection] = None
    var enrollment: Option[Json] = None
    var size = 0L
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    val records = (1L to maximum).map { n =>
      check(System.nanoTime()-started < java.util.concurrent.TimeUnit.MINUTES.toNanos(5),"recovery-history-deadline")
      val response = trust.client.call(PolicyWitnessClient.request("RECORD",obj("sequence" -> str(n.toString)),0,zero))
      check(field(response,"current") == head,"recovery-head-changed")
      val certificate = field(response,"record")
      val encoded = canonical(certificate)
      size += encoded.length
      check(size <= 128L*1024*1024,"recovery-transcript-bound")
      hash.update(encoded)
      val record = trust.client.verifyRecord(certificate)
      state = PolicyJournal.applyRecord(state,record)
      if n == backupSequence then check(state.current == backupHead,"recovery-backup-head")
      val command = field(record,"command")
      text(record,"kind") match
        case "ENROLL" =>
          check(n == 1 && certificate == enrollmentCertificate,"recovery-checkpoint-authenticity")
          enrollment = Some(command)
          projection = Some(RecoveryProjection.initial(command))
        case "COMMIT" => projection = Some(RecoveryProjection.committed(projection.get,command,certificate))
        case "RECOVERY_RELEASE" => projection = Some(RecoveryProjection.released(projection.get,field(command,"at")))
        case "PREPARE" | "ABORT" | "RECOVERY_BARRIER" => ()
        case _ => throw PolicyFailure("recovery-unsupported-record")
      certificate
    }.toVector
    check(state.current == head && current(trust) == head,"recovery-fresh-head")
    Verified(head,enrollment.get,projection.get,records,hash.digest().map(b => f"${b & 255}%02x").mkString,state.retiredWriters ++ state.writer)
