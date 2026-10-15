package sanctity.policy

import io.circe.Json
import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.{KeyPairGenerator, KeyStore, PublicKey}
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/** Separate JVM used by the fault suite. It has no catalog connection or bearer secrets.
  * The kill points are reachable only through this test entry point, never the service CLI.
  */
object PolicyWitnessProcess:
  def main(args: Array[String]): Unit =
    val material = Path.of(args(0)); val storePath = Path.of(args(1))
    val key = PolicyWire.privateKey(Files.readAllBytes(material.resolve("signing-private.der")))
    val public = PolicyWire.publicKey(Files.readAllBytes(material.resolve("signing-public.der")))
    val stop = args.lift(2).getOrElse("")
    val store = PolicyWitnessStore.openObserved(storePath,PolicyFixtures.installation,key,public,stage =>
      if stage == stop then
        Files.writeString(material.resolve("boundary"),stage)
        new CountDownLatch(1).await())
    val server = PolicyWitnessServer.start(new InetSocketAddress("127.0.0.1",0),PolicyTestTls.context(material,"server"),
      store,PolicyFixtures.installation,key,public,Map(PolicyTestTls.fingerprint(material,"writer") -> "writer",
        PolicyTestTls.fingerprint(material,"recovery") -> "recovery"))
    println(s"READY:${server.port}")
    System.out.flush()
    try System.in.read()
    finally
      server.close()
      store.close()

private[sanctity] object PolicyTestTls:
  private val password = "synthetic-witness-test-only"
  def keyStore(path: Path): KeyStore =
    val store = KeyStore.getInstance("PKCS12")
    val input = Files.newInputStream(path)
    try store.load(input,password.toCharArray) finally input.close()
    store
  def context(root: Path, who: String): javax.net.ssl.SSLContext =
    PolicyTls.context(keyStore(root.resolve(who+".p12")),password.toCharArray,keyStore(root.resolve("anchors.p12")))
  def fingerprint(root: Path, who: String): String =
    PolicyWire.digest(keyStore(root.resolve(who+".p12")).getCertificate(who).getEncoded)
  def generate(root: Path): Unit =
    val tool = Path.of(System.getProperty("java.home"),"bin","keytool").toString
    def run(arguments: String*): Unit =
      val process = new ProcessBuilder((Vector(tool) ++ arguments)*).redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
      if process.waitFor() != 0 then throw new IllegalStateException("synthetic keytool fixture failed: " + output)
    for who <- Vector("server","writer","recovery","stranger") do
      run("-genkeypair","-alias",who,"-keyalg","EC","-groupname","secp256r1","-dname","CN="+who,
        "-validity","2","-ext","SAN=dns:localhost","-keystore",root.resolve(who+".p12").toString,
        "-storetype","PKCS12","-storepass",password,"-keypass",password,"-noprompt")
      run("-exportcert","-alias",who,"-keystore",root.resolve(who+".p12").toString,"-storepass",password,
        "-file",root.resolve(who+".der").toString)
      run("-importcert","-alias",who,"-file",root.resolve(who+".der").toString,
        "-keystore",root.resolve("anchors.p12").toString,"-storetype","PKCS12","-storepass",password,"-noprompt")
    val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    Files.write(root.resolve("signing-private.der"),keys.getPrivate.getEncoded)
    Files.write(root.resolve("signing-public.der"),keys.getPublic.getEncoded)
    Files.writeString(root.resolve("password"),password)
    val entries = Files.list(root)
    try entries.forEach(path => Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")))
    finally entries.close()

final class PolicyTransportSuite extends munit.FunSuite:
  import PolicyWire.*
  import PolicyFixtures.*
  override val munitTimeout: Duration = 90.seconds
  private var material: Path = scala.compiletime.uninitialized
  override def beforeAll(): Unit =
    material = directory()
    PolicyTestTls.generate(material)
  override def afterAll(): Unit = remove(material)
  private def public: PublicKey = publicKey(Files.readAllBytes(material.resolve("signing-public.der")))
  private def initialize(path: Path): Unit = PolicyWitnessStore.initialize(path,installation,
    privateKey(Files.readAllBytes(material.resolve("signing-private.der"))),public)
  private final class Server(path: Path, boundary: String = "") extends AutoCloseable:
    private val process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,
      "-cp",System.getProperty("java.class.path"),"sanctity.policy.PolicyWitnessProcess",material.toString,path.toString,boundary)
      .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val reader = new BufferedReader(new InputStreamReader(process.getInputStream))
    private val ready = CompletableFuture.supplyAsync(() => reader.readLine()).get(15,TimeUnit.SECONDS)
    assert(ready != null && ready.startsWith("READY:"),"separate witness process did not start")
    val port = ready.stripPrefix("READY:").toInt
    def client(who: String, trust: PublicKey = public, hostname: String = "localhost"): PolicyWitnessClient =
      new PolicyWitnessClient(URI.create(s"https://$hostname:$port/v1/policy"),installation,PolicyTestTls.context(material,who),trust)
    def killed(): Unit =
      process.destroyForcibly()
      assert(process.waitFor(10,TimeUnit.SECONDS))
    override def close(): Unit =
      if process.isAlive then
        process.getOutputStream.close()
        if !process.waitFor(10,TimeUnit.SECONDS) then killed()
      reader.close()
  private def fixture(run: (Path,Server) => Unit): Unit =
    val root = directory(); val path = root.resolve("journal")
    initialize(path)
    val server = new Server(path)
    try run(path,server) finally { server.close(); remove(root) }
  private def call(server: Server, who: String, request: Json): Json =
    val client = server.client(who)
    try client.call(request) finally client.close()
  private def current(server: Server): Json = field(call(server,"recovery",request("CURRENT",obj())),"current")
  private def at(server: Server, action: String, command: Json, who: String = "writer"): Json =
    val state = current(server)
    call(server,who,request(action,command,natural(text(state,"sequence")),text(state,"journalHash")))
  private def enroll(server: Server): PolicyJournal.State =
    val command = enrollment(PolicyTestTls.fingerprint(material,"writer"))
    at(server,"ENROLL",command,"recovery")
    step(PolicyJournal.State(installation),"ENROLL",command)

  test("real separate TLS process: enrollment, PREPARE, COMMIT, exact retries and restart") {
    fixture { (path,server) =>
      val s = enroll(server); val e = event(s)
      at(server,"PREPARE",e)
      val committed = at(server,"COMMIT",e)
      assertEquals(at(server,"COMMIT",e),committed)
      server.close()
      val restarted = new Server(path)
      try
        assertEquals(text(current(restarted),"revision"),"8")
        assertEquals(at(restarted,"PREPARE",e),committed)
      finally restarted.close()
    }
  }
  test("mutual TLS rejects an unapproved client; signing pin and hostname are independently required") {
    fixture { (_,server) =>
      enroll(server)
      for (who,trust,host) <- Vector(("stranger",public,"localhost"),
          ("writer",KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic,"localhost"),
          ("writer",public,"127.0.0.1")) do
        val client = server.client(who,trust,host)
        try intercept[PolicyFailure](client.call(request("CURRENT",obj()))) finally client.close()
      val noIdentity = javax.net.ssl.SSLContext.getInstance("TLSv1.3")
      val tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
      tm.init(PolicyTestTls.keyStore(material.resolve("anchors.p12")))
      noIdentity.init(Array.empty[javax.net.ssl.KeyManager],tm.getTrustManagers,null)
      val noCertificate = new PolicyWitnessClient(URI.create(s"https://localhost:${server.port}/v1/policy"),installation,noIdentity,public)
      try intercept[PolicyFailure](noCertificate.call(request("CURRENT",obj()))) finally noCertificate.close()
    }
  }
  test("barrier fences the old writer over TLS and preserves pending intent across process restart") {
    fixture { (path,server) =>
      val s = enroll(server); val e = event(s)
      at(server,"PREPARE",e)
      val pending = step(s,"PREPARE",e)
      at(server,"RECOVERY_BARRIER",barrier(pending,PolicyTestTls.fingerprint(material,"recovery")),"recovery")
      intercept[PolicyFailure](call(server,"writer",request("CURRENT",obj())))
      intercept[PolicyFailure](at(server,"ABORT",e,"recovery"))
      server.close()
      val restarted = new Server(path)
      try
        assertEquals(field(current(restarted),"pending"),e)
        assertEquals(text(current(restarted),"epoch"),"1")
        intercept[PolicyFailure](at(restarted,"COMMIT",e))
      finally restarted.close()
    }
  }
  test("concurrent independent clients cannot branch or overtake a preparation") {
    fixture { (_,server) =>
      val s = enroll(server); val head = current(server)
      val requests = Vector(event(s),event(s)).map(e => request("PREPARE",e,1,text(head,"journalHash")))
      val outcomes = requests.map(req => CompletableFuture.supplyAsync(() => scala.util.Try(call(server,"writer",req)).isSuccess))
      assertEquals(outcomes.count(_.get(10,TimeUnit.SECONDS)),1)
      assertEquals(text(current(server),"sequence"),"2")
    }
  }
  test("two-second whole-request deadline has no automatic retry and leaves durable preparation unresolved") {
    fixture { (path,server) =>
      val state = enroll(server); val e = event(state); val before = current(server)
      server.close()
      Files.deleteIfExists(material.resolve("boundary"))
      val stalled = new Server(path,"head")
      try
        val client = stalled.client("writer")
        try
          val start = System.nanoTime()
          intercept[PolicyFailure](client.call(request("PREPARE",e,1,text(before,"journalHash"))))
          val elapsed = (System.nanoTime()-start).nanos
          assert(elapsed >= 1800.millis && elapsed < 3.seconds,s"deadline elapsed: $elapsed")
          assert(Files.exists(material.resolve("boundary")))
        finally client.close()
        stalled.killed()
        val restarted = new Server(path)
        try
          assertEquals(text(current(restarted),"sequence"),"2")
          assertEquals(field(current(restarted),"pending"),e)
          assertEquals(text(field(at(restarted,"PREPARE",e),"current"),"sequence"),"2")
        finally restarted.close()
      finally stalled.close()
    }
  }
  for action <- Vector("PREPARE","COMMIT","ABORT","RECOVERY_BARRIER"); boundary <- Vector("record","head") do
    test(s"kill separate witness after $action $boundary force before response; exact outcome survives") {
      fixture { (path,server) =>
        val s = enroll(server); val e = event(s)
        if action == "COMMIT" || action == "ABORT" then at(server,"PREPARE",e)
        val command = if action == "RECOVERY_BARRIER" then barrier(s,PolicyTestTls.fingerprint(material,"recovery")) else e
        val before = current(server)
        server.close()
        Files.deleteIfExists(material.resolve("boundary"))
        val faulting = new Server(path,boundary)
        try
          val who = if action == "RECOVERY_BARRIER" then "recovery" else "writer"
          val req = request(action,command,natural(text(before,"sequence")),text(before,"journalHash"))
          val outcome = CompletableFuture.supplyAsync(() => scala.util.Try(call(faulting,who,req)))
          val deadline = System.nanoTime()+10.seconds.toNanos
          while !Files.exists(material.resolve("boundary")) && System.nanoTime() < deadline do Thread.sleep(10)
          assert(Files.exists(material.resolve("boundary")),"fault boundary was not reached")
          faulting.killed()
          assert(outcome.get(5,TimeUnit.SECONDS).isFailure,"response must not claim success after kill")
          val restarted = new Server(path)
          try
            val recovered = current(restarted)
            assertEquals(natural(text(recovered,"sequence")),natural(text(before,"sequence"))+1)
            val resolved = at(restarted,action,command,who)
            assertEquals(at(restarted,action,command,who),resolved)
            if action == "PREPARE" then assertEquals(field(recovered,"pending"),e)
            if action == "COMMIT" then assertEquals(text(recovered,"revision"),"8")
            if action == "ABORT" then assertEquals(text(recovered,"revision"),"7")
            if action == "RECOVERY_BARRIER" then assertEquals(text(recovered,"epoch"),"1")
          finally restarted.close()
        finally faulting.close()
      }
    }
