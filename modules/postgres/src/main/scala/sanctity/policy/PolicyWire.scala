package sanctity.policy

import io.circe.Json
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.security.{KeyFactory, MessageDigest, PrivateKey, PublicKey, Signature}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.time.Instant
import java.util.{Base64, UUID}
import scala.util.control.NoStackTrace

/** Protocol errors deliberately contain neither payloads nor credentials. */
final case class PolicyFailure(code: String) extends RuntimeException(code) with NoStackTrace

/** RFC8785 string/literal profile. Numeric tokens are forbidden: every integer is a
  * canonical decimal string. Existing catalog JSON is retained as opaque UTF-8 bytes,
  * never rounded, normalized or rewritten by the signing layer.
  */
object PolicyWire:
  val version = "sanctity-policy-wire-v1"
  val maximumBytes = 8 * 1024 * 1024
  // PREPARE responses include both pending intent and the signed journal record.
  // Leave bounded room for that duplication and all response/signature envelopes.
  val maximumEventBytes = 1024 * 1024
  val maximumCheckpointBytes = 4 * 1024 * 1024
  val maximumDepth = 48
  val zero = "0" * 64
  private val parser = new io.circe.jawn.JawnParser(maxValueSize=Some(maximumBytes), allowDuplicateKeys=false)
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  def check(ok: Boolean, code: String): Unit = if !ok then throw PolicyFailure(code)
  def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  def str(value: String): Json = Json.fromString(value)
  def text(json: Json, key: String): String = json.hcursor.get[String](key).fold(_ => throw PolicyFailure("field"), identity)
  def field(json: Json, key: String): Json = json.asObject.flatMap(_(key)).getOrElse(throw PolicyFailure("field"))
  def fields(json: Json, expected: Set[String]): Unit =
    check(json.asObject.exists(_.keys.toSet == expected), "fields")
  def natural(value: String): Long =
    check(value.matches("0|[1-9][0-9]{0,18}"), "integer")
    value.toLongOption.filter(_ >= 0).getOrElse(throw PolicyFailure("integer"))
  def increment(value: Long): Long =
    check(value >= 0 && value < Long.MaxValue, "exhausted")
    value + 1
  def id(value: String): String =
    check(scala.util.Try(UUID.fromString(value)).toOption.exists(_.toString == value), "identifier")
    value
  def hash(value: String): String =
    check(value.matches("[0-9a-f]{64}"), "digest")
    value
  def instant(value: String): Instant =
    val parsed = scala.util.Try(Instant.parse(value)).getOrElse(throw PolicyFailure("time"))
    check(parsed.toString == value, "time")
    parsed
  def utf8(bytes: Array[Byte]): String =
    check(bytes.length <= maximumBytes, "size")
    try StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString
    catch case _: java.nio.charset.CharacterCodingException => throw PolicyFailure("utf8")
  private[policy] def parseCatalog(bytes: Array[Byte]): Json =
    val source = utf8(bytes)
    // Bound nesting BEFORE invoking the recursive JSON parser, including malicious input.
    var depth = 0
    var quoted = false
    var escaped = false
    source.foreach { c =>
      if quoted then
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then quoted = false
      else if c == '"' then quoted = true
      else if c == '[' || c == '{' then
        depth += 1
        check(depth <= maximumDepth, "depth")
      else if c == ']' || c == '}' then depth -= 1
    }
    val result = parser.parse(source).fold(_ => throw PolicyFailure("json"), identity)
    def unicode(value: Json): Unit = value.fold((), _ => (), _ => (), s => { canonical(str(s)); () },
      _.foreach(unicode), o => o.toVector.foreach { (k,v) => canonical(str(k)); unicode(v) })
    unicode(result)
    result
  def parse(bytes: Array[Byte]): Json =
    val json = parseCatalog(bytes)
    canonical(json) // Validate the profile even if the input is not itself canonical.
    json
  def parse(source: String): Json =
    val bytes = try
      val b = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(source))
      val result = new Array[Byte](b.remaining())
      b.get(result)
      result
    catch case _: java.nio.charset.CharacterCodingException => throw PolicyFailure("unicode")
    parse(bytes)

  def canonical(json: Json): Array[Byte] =
    val out = new java.lang.StringBuilder()
    def quoted(value: String): Unit =
      out.append('"')
      var i = 0
      while i < value.length do
        val c = value.charAt(i)
        c match
          case '"' => out.append("\\\"")
          case '\\' => out.append("\\\\")
          case '\b' => out.append("\\b")
          case '\t' => out.append("\\t")
          case '\n' => out.append("\\n")
          case '\f' => out.append("\\f")
          case '\r' => out.append("\\r")
          case x if x < ' ' => out.append(f"\\u${x.toInt}%04x")
          case x if Character.isHighSurrogate(x) =>
            check(i + 1 < value.length && Character.isLowSurrogate(value.charAt(i + 1)), "unicode")
            out.append(x).append(value.charAt(i + 1))
            i += 1
          case x =>
            check(!Character.isLowSurrogate(x), "unicode")
            out.append(x)
        i += 1
      out.append('"')
      ()
    def emit(value: Json, depth: Int): Unit =
      check(depth <= maximumDepth && out.length <= maximumBytes, "size-depth")
      value.fold(
        { out.append("null"); () },
        b => { out.append(if b then "true" else "false"); () },
        _ => throw PolicyFailure("numeric-token"),
        quoted,
        array =>
          out.append('[')
          array.zipWithIndex.foreach { (v,i) => if i > 0 then out.append(','); emit(v,depth+1) }
          out.append(']')
          (),
        objectValue =>
          out.append('{')
          // String ordering is unsigned UTF-16 code-unit ordering, as RFC8785 requires.
          objectValue.toVector.sortBy(_._1).zipWithIndex.foreach { case ((k,v),i) =>
            if i > 0 then out.append(',')
            quoted(k)
            out.append(':')
            emit(v,depth+1)
          }
          out.append('}')
          ())
    emit(json,0)
    val bytes = out.toString.getBytes(StandardCharsets.UTF_8)
    check(bytes.length <= maximumBytes, "size")
    bytes

  def digest(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
  def digest(json: Json): String = digest(canonical(json))
  def base64(bytes: Array[Byte]): String = encoder.encodeToString(bytes)
  def unbase64(value: String): Array[Byte] =
    check(value.length <= maximumBytes && value.matches("[A-Za-z0-9_-]*"), "base64")
    val result = scala.util.Try(decoder.decode(value)).getOrElse(throw PolicyFailure("base64"))
    check(base64(result) == value, "base64")
    result
  def publicKey(bytes: Array[Byte]): PublicKey =
    try KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes))
    catch case _: java.security.GeneralSecurityException => throw PolicyFailure("public-key")
  def privateKey(bytes: Array[Byte]): PrivateKey =
    try KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes))
    catch case _: java.security.GeneralSecurityException => throw PolicyFailure("private-key")
  def keyId(key: PublicKey): String = digest(key.getEncoded)
  def signBytes(bytes: Array[Byte], key: PrivateKey): Array[Byte] =
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(key)
    signer.update(bytes)
    signer.sign()
  def verifyBytes(bytes: Array[Byte], signature: Array[Byte], key: PublicKey): Boolean =
    if signature.length != 64 then false else
      val verifier = Signature.getInstance("Ed25519")
      verifier.initVerify(key)
      verifier.update(bytes)
      try verifier.verify(signature)
      catch case _: java.security.SignatureException => false
  def sign(body: Json, key: PrivateKey, public: PublicKey): Json =
    val bound = obj("version" -> str(version), "key" -> str(keyId(public)), "body" -> body)
    obj("signed" -> bound, "signature" -> str(base64(signBytes(canonical(bound),key))))
  def verify(envelope: Json, trusted: PublicKey): Json =
    fields(envelope,Set("signed","signature"))
    val bound = field(envelope,"signed")
    fields(bound,Set("version","key","body"))
    check(text(bound,"version") == version && text(bound,"key") == keyId(trusted), "key-version")
    check(verifyBytes(canonical(bound),unbase64(text(envelope,"signature")),trusted), "signature")
    field(bound,"body")
