package sanctity.postgres

import cats.syntax.all.*
import doobie.free.connection as FC
import doobie.util.transactor.Strategy
import io.circe.{Json, Printer}
import sanctity.application.Failure
import sanctity.kernel.Problem
import sanctity.policy.*
import java.sql.{Connection, ResultSet}
import java.util.IdentityHashMap
import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}
import scala.util.Using
import scala.util.control.NonFatal

/** Trust is supplied by the process, never loaded from a catalog or its backup. */
final class PolicyConnection(val installation: String, val writer: String, val client: PolicyWitnessClient):
  PolicyWire.id(installation)
  PolicyWire.hash(writer)
  override def toString: String = "PolicyConnection(<protected>)"

/** The transaction strategy owns the JDBC connection through local commit and remote
  * finalization. No successful effect value escapes before this strategy completes.
  * A live rollback proof is process-local and is never persisted in a backup.
  */
private[postgres] object PolicyBridge:
  import PolicyWire.*
  private val sessions = new IdentityHashMap[Connection, Session]()
  private val timer = new ScheduledThreadPoolExecutor(1, (r: Runnable) =>
    val t = new Thread(r,"sanctity-policy-permit"); t.setDaemon(true); t)
  timer.setRemoveOnCancelPolicy(true)

  private final class Session(val trust: Option[PolicyConnection], val evict: Connection => Unit):
    var current: Option[Json] = None
    var command: Option[Json] = None
    var commitAttempted = false
    @volatile var expired = false
    private var armed = true
    var expires = Long.MaxValue
    var deadline: Option[ScheduledFuture[?]] = None
    def stop(): Unit = synchronized { armed = false; deadline.foreach(_.cancel(false)); deadline = None }
    def expire(c: Connection): Unit = synchronized {
      if armed then
        expired = true
        // Complete abort before stop permits this proxy to return to its pool.
        try c.abort((r: Runnable) => r.run()) catch { case NonFatal(_) => () }
        finally evict(c)
    }
    def arm(c: Connection): Unit =
      expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      deadline = Some(timer.schedule(new Runnable { def run(): Unit = expire(c) },5,TimeUnit.SECONDS))
    def valid(): Unit = requireOpen(!expired && System.nanoTime() < expires,"policy admission expired")
    def boundServer(c: Connection): Unit =
      valid()
      val remaining = TimeUnit.NANOSECONDS.toMillis(expires-System.nanoTime())
      requireOpen(remaining > 0,"policy admission expired")
      // Closing a JDBC socket does not immediately interrupt a running server
      // statement. PostgreSQL 17 must also terminate the whole phase transaction,
      // including time spent between statements or waiting on the witness.
      update(c,s"SET LOCAL transaction_timeout='${remaining}ms'")
      valid()

  private def session(c: Connection): Session = sessions.synchronized {
    Option(sessions.get(c)).getOrElse(throw Failure(Problem.Operational("trusted policy transaction required")))
  }
  private def requireOpen(ok: Boolean, message: String): Unit =
    if !ok then throw Failure(Problem.Operational(message))
  private def trust(s: Session): PolicyConnection = s.trust.getOrElse(
    throw Failure(Problem.Operational("independently retained policy trust required")))

  def strategy(config: Option[PolicyConnection], evict: Connection => Unit): Strategy = Strategy(
    FC.setAutoCommit(false) *> FC.raw(c => sessions.synchronized { sessions.put(c,new Session(config,evict)); () }),
    FC.raw(finish), FC.raw(rollback), FC.raw { c => sessions.synchronized {
      Option(sessions.remove(c)).foreach(_.stop())
    } })

  private[postgres] def rows[A](c: Connection, sql: String, args: Seq[String] = Seq.empty)(read: ResultSet => A): Vector[A] =
    Using.resource(c.prepareStatement(sql)) { statement =>
      args.zipWithIndex.foreach((v,i) => statement.setString(i+1,v))
      Using.resource(statement.executeQuery()) { rs =>
        val result = Vector.newBuilder[A]
        while rs.next() do result += read(rs)
        result.result()
      }
    }
  private[postgres] def update(c: Connection, sql: String, args: Seq[String] = Seq.empty): Unit =
    Using.resource(c.prepareStatement(sql)) { statement =>
      args.zipWithIndex.foreach((v,i) => statement.setString(i+1,v))
      statement.executeUpdate(); ()
    }
  private def json(value: String): Json = io.circe.jawn.parse(value).fold(_ => throw PolicyFailure("catalog-json"),identity)
  private val sorted = Printer.noSpaces.copy(sortKeys=true)
  private def local(c: Connection): (String,String,String,String,String) = rows(c,
    "SELECT installation::text,writer_epoch::text,revision::text,applied_hash,durability_mode FROM organization_policy WHERE singleton")(
    r => (r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))).head

  private def call(s: Session, action: String, command: Json): Json =
    val current = s.current
    val result = trust(s).client.call(PolicyWitnessClient.request(action,command,
      current.fold(0L)(j => natural(text(j,"sequence"))),current.fold(zero)(j => text(j,"journalHash"))))
    val now = field(result,"current")
    check(text(now,"installation") == trust(s).installation && PolicyCapabilities.supported(text(now,"capabilities")),
      "current-binding")
    s.current = Some(now)
    result

  private def record(s: Session, response: Json, kind: String, command: Json): Json =
    val certificate = field(response,"record")
    val body = trust(s).client.verifyRecord(certificate)
    check(text(body,"kind") == kind && field(body,"command") == command,"exact-certificate")
    certificate

  private def event(c: Connection, operation: String): Option[Json] =
    rows(c,"SELECT payload::text,applied_hash FROM organization_event WHERE id=?::uuid",Seq(operation)) { r =>
      envelope(json(r.getString(1)).printWith(sorted),r.getString(2),capabilities(c))
    }.headOption

  private[postgres] def capabilities(c: Connection): String =
    val profile = rows(c,"SELECT command->>'capabilities' FROM policy_enrollment")(_.getString(1)).headOption
      .getOrElse(throw PolicyFailure("enrollment-required"))
    check(PolicyCapabilities.supported(profile), "capabilities")
    profile

  def envelope(payload: String, applied: String, profile: String = PolicyCapabilities.digest): Json =
    val p = json(payload)
    val bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val result = obj("version" -> str(PolicyJournal.eventVersion),"capabilities" -> str(profile),
      "installation" -> field(p,"installation"),"epoch" -> field(p,"epoch"),"operation" -> field(p,"event"),
      "revision" -> field(p,"revision"),"previousRevision" -> field(p,"previousRevision"),
      "previousHash" -> field(p,"previousHash"),"appliedHash" -> str(applied),"kind" -> field(p,"kind"),
      "payload" -> str(base64(bytes)),"payloadDigest" -> str(digest(bytes)))
    PolicyJournal.validateEvent(result)
    result

  private def matches(c: Connection, s: Session): Unit =
    val (installation,epoch,revision,head,mode) = local(c)
    val current = s.current.get
    check(mode == "independent" && installation == trust(s).installation &&
      text(current,"capabilities") == capabilities(c) &&
      text(current,"epoch") == epoch && field(current,"barrier").isNull &&
      text(current,"writer") == trust(s).writer && field(current,"enrolled").asBoolean.contains(true),"admission-identity")
    val pending = field(current,"pending")
    if pending.isNull then
      check(text(current,"revision") == revision && text(current,"appliedHash") == head,"admission-head")
    else
      PolicyJournal.validateEvent(pending)
      check(text(pending,"revision") == revision && text(pending,"appliedHash") == head &&
        event(c,text(pending,"operation")).contains(pending),"pending-live-event-required")

  /** Called only after the installation lock has been acquired. Fresh proof per SQL
    * transaction; local principal/dataset checks still run for every admission.
    */
  def admit(c: Connection, write: Boolean): Unit =
    val s = session(c)
    val mode = local(c)._5
    if mode == "local-engineering" then
      requireOpen(s.trust.isEmpty,"configured witness forbids local policy downgrade")
    else
      requireOpen(mode == "independent","policy enrollment is closed")
      if s.current.isEmpty then
        s.arm(c)
        try
          s.boundServer(c)
          call(s,"CURRENT",obj())
          matches(c,s)
          verifyAcknowledgements(c,s)
          // This binary's exact schema (through V013, dataset labels). Move with every migration.
          check(rows(c,"SELECT count(*)=13 AND max(version)=13 FROM schema_migration")(_.getBoolean(1)).head,"producer-schema")
          check(rows(c,"""SELECT NOT EXISTS(SELECT 1 FROM principal WHERE authority_eligibility <> '{"version":1,"value":{"Local":{}}}'::jsonb)
            AND NOT EXISTS(SELECT 1 FROM dataset WHERE authority_lifecycle<>'Available')
            AND NOT EXISTS(SELECT 1 FROM release WHERE authority_lifecycle<>'Available')""")(_.getBoolean(1)).head,
            "unsupported-authority-producer")
          // Backstop for a blocked SQL statement or lost client. The connection is
          // additionally aborted at the absolute permit deadline, even while idle.
          update(c,"SET LOCAL statement_timeout='5s'")
          update(c,"SET LOCAL idle_in_transaction_session_timeout='5s'")
        catch case NonFatal(_) => throw Failure(Problem.Operational("current policy witness admission unavailable"))
      s.valid()
      if write && s.command.isEmpty then reconcile(c,s)

  /** PREPARE is recorded before effects and included in the atomic local pending event. */
  def prepare(c: Connection, payload: String, applied: String): Option[String] =
    if local(c)._5 == "local-engineering" then None
    else
      val s = session(c)
      s.valid()
      val command = envelope(payload,applied,capabilities(c))
      requireOpen(s.command.isEmpty,"one policy event per transaction required")
      s.command = Some(command) // retained even when the PREPARE response is lost
      try
        val response = call(s,"PREPARE",command)
        val certificate = record(s,response,"PREPARE",command)
        s.valid()
        Some(certificate.noSpaces)
      catch case NonFatal(_) => throw Failure(Problem.PolicyOutcomeUnknown(text(command,"operation")))

  private def lock(c: Connection): Unit =
    requireOpen(!rows(c,"SELECT recovery_required FROM repository_state WHERE singleton FOR SHARE")(_.getBoolean(1)).head,
      "repository recovery is required")
    rows(c,"SELECT singleton FROM organization_policy WHERE singleton FOR UPDATE")(_.getBoolean(1))
    ()

  private def acknowledge(c: Connection, s: Session, command: Json): Unit =
    val op = text(command,"operation")
    check(event(c,op).contains(command),"committed-exact-event-required")
    val response = call(s,"COMMIT",command)
    val certificate = record(s,response,"COMMIT",command)
    check(natural(text(s.current.get,"revision")) >= natural(text(command,"revision")),"commit-head")
    update(c,"INSERT INTO policy_acknowledgement(event_id,certificate) VALUES(?::uuid,?::jsonb) ON CONFLICT(event_id) DO NOTHING",
      Seq(op,certificate.noSpaces))
    val existing = rows(c,"SELECT certificate::text FROM policy_acknowledgement WHERE event_id=?::uuid",Seq(op))(r => json(r.getString(1))).head
    check(existing == certificate,"acknowledgement-conflict")

  /** A new process may finalize a proven local event, but can never abort an absent
    * one. Missing local intent could be an older backup and remains closed.
    */
  private def verifyAcknowledgements(c: Connection, s: Session): Unit =
    rows(c,"SELECT event_id::text,certificate::text FROM policy_acknowledgement")(r => r.getString(1) -> json(r.getString(2)))
      .foreach { (op,certificate) =>
        val body = trust(s).client.verifyRecord(certificate)
        check(text(body,"kind") == "COMMIT" && event(c,op).contains(field(body,"command")),"stored-acknowledgement")
      }

  private def reconcile(c: Connection, s: Session): Unit =
    val missing = rows(c,"SELECT e.id::text FROM organization_event e LEFT JOIN policy_acknowledgement a ON a.event_id=e.id " +
      "WHERE e.durability='Pending' AND a.event_id IS NULL ORDER BY e.revision")(_.getString(1))
    check(missing.size <= 1,"unresolved-order")
    missing.foreach { op =>
      try acknowledge(c,s,event(c,op).get)
      catch case NonFatal(_) => throw Failure(Problem.PolicyPending(op))
    }

  private def finish(c: Connection): Unit =
    val s = session(c)
    s.valid()
    s.commitAttempted = true
    try c.commit()
    catch case NonFatal(e) =>
      s.command match
        case Some(command) => throw Failure(Problem.PolicyOutcomeUnknown(text(command,"operation")))
        case None => throw e
    s.stop()
    s.command.foreach { command =>
      // This second bounded phase can only acknowledge the committed event. It
      // cannot admit new authority effects or extend the original admission permit.
      val finalizer = new Session(s.trust,s.evict)
      finalizer.arm(c)
      try
        finalizer.boundServer(c)
        update(c,"SET LOCAL statement_timeout='5s'")
        update(c,"SET LOCAL idle_in_transaction_session_timeout='5s'")
        lock(c)
        call(finalizer,"CURRENT",obj())
        matches(c,finalizer)
        acknowledge(c,finalizer,command)
        finalizer.valid()
        c.commit()
      catch case NonFatal(_) => throw Failure(Problem.PolicyPending(text(command,"operation")))
      finally finalizer.stop()
    }

  private def rollback(c: Connection): Unit =
    val s = session(c)
    s.stop()
    val rolledBack = try { c.rollback(); true } catch { case NonFatal(_) => false }
    // This is the only ABORT producer: the very connection that prepared has
    // rolled back without ever attempting COMMIT. A restarted process has no proof.
    if rolledBack && !s.commitAttempted then s.command.foreach { command =>
      val resolver = new Session(s.trust,s.evict)
      resolver.arm(c)
      try
        resolver.boundServer(c)
        update(c,"SET LOCAL statement_timeout='5s'")
        update(c,"SET LOCAL idle_in_transaction_session_timeout='5s'")
        lock(c)
        val (installation,epoch,revision,head,mode) = local(c)
        check(mode == "independent" && installation == text(command,"installation") && epoch == text(command,"epoch") &&
          revision == text(command,"previousRevision") && head == text(command,"previousHash") &&
          event(c,text(command,"operation")).isEmpty,"live-rollback-proof")
        call(resolver,"CURRENT",obj())
        resolver.valid()
        if field(resolver.current.get,"pending") == command then
          record(resolver,call(resolver,"ABORT",command),"ABORT",command)
        c.commit()
      catch case NonFatal(_) =>
        // Preserve the original explicit outcome. Failed cleanup is not absence
        // proof and never authorizes a second attempt to abort or apply effects.
        try c.rollback() catch { case NonFatal(_) => () }
      finally resolver.stop()
    }
