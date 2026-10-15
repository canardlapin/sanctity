package sanctity.policy

import io.circe.Json
import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

/** Real TLS and durable files; fault hooks are confined to this test entry point. */
final class CatalogWitnessFixture(material: Path, root: Path, val installation: String) extends AutoCloseable:
  import PolicyWire.*
  private val key = privateKey(Files.readAllBytes(material.resolve("signing-private.der")))
  private val public = publicKey(Files.readAllBytes(material.resolve("signing-public.der")))
  val writerFingerprint = PolicyTestTls.fingerprint(material,"writer")
  val recoveryFingerprint = PolicyTestTls.fingerprint(material,"recovery")
  private val failAt = new AtomicInteger(-1)
  private var heads = 0
  PolicyWitnessStore.initialize(root,installation,key,public)
  private def open(): PolicyWitnessStore = PolicyWitnessStore.openObserved(root,installation,key,public,stage =>
    if stage == "head" then
      heads += 1
      if heads == failAt.get() then throw new IllegalStateException("injected after durable head"))
  private var store = open()
  private def serve(port: Int): PolicyWitnessServer = PolicyWitnessServer.start(new InetSocketAddress("127.0.0.1",port),
    PolicyTestTls.context(material,"server"),store,installation,key,public,
    Map(writerFingerprint -> "writer",recoveryFingerprint -> "recovery"))
  private var server = serve(0)
  private val port = server.port
  val writer = new PolicyWitnessClient(URI.create(s"https://localhost:$port/v1/policy"),installation,PolicyTestTls.context(material,"writer"),public)
  val recovery = new PolicyWitnessClient(URI.create(s"https://localhost:$port/v1/policy"),installation,PolicyTestTls.context(material,"recovery"),public)
  def failHead(number: Int): Unit = failAt.set(number)
  def stop(): Unit = server.close()
  def restart(): Unit =
    server.close(); store.close(); failAt.set(-1); store = open(); server = serve(port)
  def current: Json = field(recovery.call(PolicyWitnessClient.request("CURRENT",obj(),0,zero)),"current")
  def command(action: String, command: Json): Json =
    val state = current
    recovery.call(PolicyWitnessClient.request(action,command,natural(text(state,"sequence")),text(state,"journalHash")))
  override def close(): Unit =
    server.close(); writer.close(); recovery.close(); store.close()
