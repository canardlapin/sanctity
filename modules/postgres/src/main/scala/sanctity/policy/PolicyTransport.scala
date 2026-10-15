package sanctity.policy

import com.sun.net.httpserver.{HttpsConfigurator, HttpsExchange, HttpsParameters, HttpsServer}
import io.circe.Json
import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.security.{KeyStore, PrivateKey, PublicKey}
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.{ArrayBlockingQueue, ScheduledThreadPoolExecutor, ThreadPoolExecutor, TimeUnit}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLSocket, TrustManagerFactory}

object PolicyTls:
  /** Both peers must have their own private key and CA trust. No trust-all or cleartext mode. */
  def context(identity: KeyStore, password: Array[Char], anchors: KeyStore): SSLContext =
    val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    km.init(identity,password)
    val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tm.init(anchors)
    val ssl = SSLContext.getInstance("TLSv1.3")
    ssl.init(km.getKeyManagers,tm.getTrustManagers,null)
    ssl

final class PolicyWitnessServer private (
    server: HttpsServer, executor: ThreadPoolExecutor) extends AutoCloseable:
  val port: Int = server.getAddress.getPort
  override def close(): Unit =
    server.stop(0)
    executor.shutdownNow()
    ()

object PolicyWitnessServer:
  import PolicyWire.*
  def start(address: InetSocketAddress, tls: SSLContext, store: PolicyWitnessStore,
      installation: String, key: PrivateKey, public: PublicKey,
      identities: Map[String,String]): PolicyWitnessServer =
    id(installation)
    check(identities.nonEmpty && identities.values.toSet == Set("writer","recovery") &&
      identities.keys.forall(_.matches("[0-9a-f]{64}")), "client-identities")
    val server = HttpsServer.create(address,16)
    server.setHttpsConfigurator(new HttpsConfigurator(tls):
      override def configure(parameters: HttpsParameters): Unit =
        val settings = tls.getDefaultSSLParameters
        settings.setProtocols(Array("TLSv1.3"))
        settings.setNeedClientAuth(true)
        parameters.setSSLParameters(settings))
    val executor = new ThreadPoolExecutor(4,4,0L,TimeUnit.SECONDS,new ArrayBlockingQueue[Runnable](16),
      new ThreadPoolExecutor.AbortPolicy())
    server.setExecutor(executor)
    server.createContext("/v1/policy", exchange =>
      try
        check(exchange.getRequestMethod == "POST" && exchange.getRequestURI.toString == "/v1/policy", "http-request")
        val secure = exchange.asInstanceOf[HttpsExchange]
        val certificate = secure.getSSLSession.getPeerCertificates.head.asInstanceOf[X509Certificate]
        certificate.checkValidity()
        val fingerprint = digest(certificate.getEncoded)
        val role = identities.getOrElse(fingerprint,throw PolicyFailure("client-identity"))
        val lengths = Option(exchange.getRequestHeaders.get("Content-length"))
        check(lengths.exists(_.size() == 1), "http-length")
        val length = natural(lengths.get.get(0))
        check(length > 0 && length <= maximumBytes && exchange.getRequestHeaders.getFirst("Transfer-encoding") == null, "http-length")
        val bytes = exchange.getRequestBody.readNBytes(length.toInt)
        check(bytes.length == length, "http-body")
        val envelope = parse(bytes)
        fields(envelope,Set("version","installation","challenge","request"))
        check(text(envelope,"version") == version && text(envelope,"installation") == installation, "request-version-installation")
        id(text(envelope,"challenge"))
        val result = store.execute(role,fingerprint,field(envelope,"request"))
        val body = obj("version" -> str("policy-response-v1"),"installation" -> str(installation),
          "challenge" -> str(text(envelope,"challenge")),"requestDigest" -> str(digest(envelope)),"result" -> result)
        val response = canonical(sign(body,key,public))
        exchange.getResponseHeaders.set("Content-Type","application/json")
        exchange.getResponseHeaders.set("Cache-Control","no-store")
        exchange.sendResponseHeaders(200,response.length.toLong)
        exchange.getResponseBody.write(response)
      catch
        case _: Exception =>
          // No certificate subjects, policy payloads, paths or nested TLS/IO errors.
          val response = "{\"error\":\"policy-witness-request-failed\"}".getBytes(StandardCharsets.UTF_8)
          try
            exchange.sendResponseHeaders(409,response.length.toLong)
            exchange.getResponseBody.write(response)
          catch case _: Exception => ()
      finally exchange.close())
    server.start()
    new PolicyWitnessServer(server,executor)

/** One TLS connection and one POST per call. No redirects, reconnects or automatic
  * retries. The two-second deadline covers connect, TLS, write and the entire response.
  * A caller must resolve an unknown write outcome with its original operation identity.
  */
final class PolicyWitnessClient(endpoint: URI, installation: String, tls: SSLContext, trusted: PublicKey)
    extends AutoCloseable:
  import PolicyWire.*
  check(endpoint.getScheme == "https" && endpoint.getHost != null && endpoint.getUserInfo == null &&
    endpoint.getRawPath == "/v1/policy" && endpoint.getQuery == null && endpoint.getFragment == null &&
    (endpoint.getPort == -1 || endpoint.getPort > 0 && endpoint.getPort <= 65535), "endpoint")
  id(installation)
  private val timer = new ScheduledThreadPoolExecutor(1, (r: Runnable) =>
    val thread = new Thread(r,"sanctity-policy-deadline")
    thread.setDaemon(true)
    thread)
  timer.setRemoveOnCancelPolicy(true)
  override def close(): Unit = { timer.shutdownNow(); () }
  override def toString: String = "PolicyWitnessClient(<protected>)"

  def verifyRecord(certificate: Json): Json =
    val body = verify(certificate,trusted)
    check(text(body,"installation") == installation && text(body,"version") == PolicyJournal.recordVersion, "record-binding")
    body

  def call(request: Json): Json =
    val challenge = UUID.randomUUID().toString
    val envelope = obj("version" -> str(version),"installation" -> str(installation),
      "challenge" -> str(challenge),"request" -> request)
    val bytes = canonical(envelope)
    val socket = tls.getSocketFactory.createSocket().asInstanceOf[SSLSocket]
    val expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    val deadline = timer.schedule(new Runnable { def run(): Unit = socket.close() },2,TimeUnit.SECONDS)
    try
      val settings = socket.getSSLParameters
      settings.setProtocols(Array("TLSv1.3"))
      settings.setEndpointIdentificationAlgorithm("HTTPS")
      socket.setSSLParameters(settings)
      socket.setSoTimeout(2000)
      socket.connect(new InetSocketAddress(endpoint.getHost,if endpoint.getPort == -1 then 443 else endpoint.getPort),2000)
      socket.startHandshake()
      val host = endpoint.getRawAuthority
      val header = s"POST /v1/policy HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nContent-Length: ${bytes.length}\r\nConnection: close\r\n\r\n"
      socket.getOutputStream.write(header.getBytes(StandardCharsets.US_ASCII))
      socket.getOutputStream.write(bytes)
      socket.getOutputStream.flush()
      val input = socket.getInputStream
      var headerBytes = 0
      def line(): String =
        val out = new ByteArrayOutputStream()
        var previous = -1
        var done = false
        while !done do
          val byte = input.read()
          headerBytes += 1
          check(byte >= 0 && headerBytes <= 16384, "http-response")
          if previous == 13 && byte == 10 then done = true else out.write(byte)
          previous = byte
        val all = out.toByteArray
        check(all.nonEmpty && all.last == 13, "http-response")
        new String(all.dropRight(1),StandardCharsets.US_ASCII)
      check(line() == "HTTP/1.1 200 OK", "witness-rejected-or-outcome-unknown")
      var headers = Map.empty[String,String]
      var value = line()
      while value.nonEmpty do
        val i = value.indexOf(':')
        check(i > 0, "http-response")
        val name = value.take(i).toLowerCase(java.util.Locale.ROOT)
        check(!headers.contains(name), "http-response")
        headers = headers.updated(name,value.drop(i+1).trim)
        value = line()
      check(!headers.contains("transfer-encoding"), "http-response")
      val length = natural(headers.getOrElse("content-length",throw PolicyFailure("http-response")))
      check(length > 0 && length <= maximumBytes, "http-response")
      val response = input.readNBytes(length.toInt)
      check(response.length == length, "http-response")
      val verified = PolicyWitnessClient.verifyResponse(parse(response),trusted,installation,challenge,digest(envelope))
      check(System.nanoTime() < expires, "witness-deadline-outcome-unknown")
      verified
    catch
      case p: PolicyFailure => throw p
      case _: Exception => throw PolicyFailure("witness-unavailable-or-outcome-unknown")
    finally
      deadline.cancel(false)
      socket.close()

object PolicyWitnessClient:
  private[policy] def verifyResponse(response: Json, trusted: PublicKey, installation: String, challenge: String,
      requestDigest: String): Json =
    import PolicyWire.*
    val body = verify(response,trusted)
    fields(body,Set("version","installation","challenge","requestDigest","result"))
    check(text(body,"version") == "policy-response-v1" && text(body,"installation") == installation &&
      text(body,"challenge") == challenge && text(body,"requestDigest") == requestDigest, "response-binding")
    field(body,"result")

  def request(action: String, command: Json, sequence: Long, head: String): Json = PolicyWire.obj(
    "action" -> PolicyWire.str(action),"command" -> command,
    "expectedSequence" -> PolicyWire.str(sequence.toString),"expectedHash" -> PolicyWire.str(head))
