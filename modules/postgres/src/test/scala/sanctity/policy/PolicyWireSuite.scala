package sanctity.policy

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator

final class PolicyWireSuite extends munit.FunSuite:
  import PolicyWire.*
  private def hex(value: String): Array[Byte] = value.grouped(2).map(Integer.parseInt(_,16).toByte).toArray

  test("RFC8032 Ed25519 test vector 1: empty message, exact signature") {
    val privateBytes = hex("302e020100300506032b657004220420" + "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val publicBytes = hex("302a300506032b6570032100" + "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
      "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    assertEquals(signBytes(Array.emptyByteArray,privateKey(privateBytes)).toVector,signature.toVector)
    assert(verifyBytes(Array.emptyByteArray,signature,publicKey(publicBytes)))
    assert(!verifyBytes(Array[Byte](0),signature,publicKey(publicBytes)))
    assert(!verifyBytes(Array.emptyByteArray,signature.drop(1),publicKey(publicBytes)))
  }
  test("RFC8032 Ed25519 test vector 2: one-byte message") {
    val key = privateKey(hex("302e020100300506032b657004220420" + "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"))
    val public = publicKey(hex("302a300506032b6570032100" + "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"))
    val signature = hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
      "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
    assertEquals(signBytes(hex("72"),key).toVector,signature.toVector)
    assert(verifyBytes(hex("72"),signature,public))
  }
  test("RFC8785 UTF-16 property sorting vector and recursive ordering") {
    val source = """{"\u20ac":"Euro Sign","\r":"Carriage Return","\ufb33":"Hebrew Letter Dalet With Dagesh","1":"One","\ud83d\ude00":"Emoji: Grinning Face","\u0080":"Control","\u00f6":"Latin Small Letter O With Diaeresis"}"""
    val expected = Vector("\r","1","\u0080","ö","€","😀","דּ")
    assertEquals(new String(canonical(parse(source)),UTF_8),
      "{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\",\"ö\":\"Latin Small Letter O With Diaeresis\",\"€\":\"Euro Sign\",\"😀\":\"Emoji: Grinning Face\",\"דּ\":\"Hebrew Letter Dalet With Dagesh\"}")
    assertEquals(parse(source).asObject.get.keys.toVector.sorted,expected)
    assertEquals(new String(canonical(parse(""" {"z":[{"b":false,"a":null}],"a":true} """)),UTF_8),
      """{"a":true,"z":[{"a":null,"b":false}]}""")
  }
  test("RFC8785 literal and control escaping; no Unicode normalization") {
    val text = "€$\u000f\nA'B\"\\\\\"/"
    assertEquals(new String(canonical(str(text)),UTF_8),"\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"")
    assertNotEquals(digest(str("é")),digest(str("e\u0301")))
    assertEquals(new String(canonical(str("\b\t\n\f\r\u001f")),UTF_8),"\"\\b\\t\\n\\f\\r\\u001f\"")
  }
  for input <- Vector("{\"x\":true,\"x\":false}","{\"x\":true,\"\\u0078\":false}",
      "{\"x\":1}","{\"x\":1.0}","{\"x\":1e0}","{\"x\":-0}","{\"x\":NaN}",
      "\"\\ud800\"","\"\\udc00\"","[true,]","true false") do
    test(s"reject malformed or non-profile JSON: $input") { intercept[PolicyFailure](parse(input)) }
  test("reject invalid UTF-8, raw lone surrogates, depth and byte overflow") {
    intercept[PolicyFailure](parse(Array[Byte](34,0xc0.toByte,0xaf.toByte,34)))
    intercept[PolicyFailure](parse("\"\ud800\""))
    intercept[PolicyFailure](canonical(str("\ud800")))
    intercept[PolicyFailure](parse("[" * 49 + "null" + "]" * 49))
    intercept[PolicyFailure](parse(new Array[Byte](maximumBytes+1)))
  }
  test("integer/time/id/base64 encodings reject aliases and overflow") {
    assertEquals(natural(Long.MaxValue.toString),Long.MaxValue)
    for input <- Vector("-1","01","+1","1.0","9223372036854775808"," 1") do intercept[PolicyFailure](natural(input))
    intercept[PolicyFailure](increment(Long.MaxValue))
    intercept[PolicyFailure](instant("2026-10-14T00:00:00.000Z"))
    intercept[PolicyFailure](id("1-1-1-1-1"))
    intercept[PolicyFailure](unbase64("YQ=="))
    intercept[PolicyFailure](unbase64("YR"))
    assertEquals(unbase64("YQ").toVector,Vector(97.toByte))
  }
  test("signature binds version, pinned key and every payload byte") {
    val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val body = obj("authority" -> str("revoked"))
    val signed = sign(body,keys.getPrivate,keys.getPublic)
    assertEquals(verify(signed,keys.getPublic),body)
    intercept[PolicyFailure](verify(signed,other.getPublic))
    val changed = signed.mapObject(_.add("signed",field(signed,"signed").mapObject(_.add("body",Json.Null))))
    intercept[PolicyFailure](verify(changed,keys.getPublic))
    intercept[PolicyFailure](verify(signed.mapObject(_.add("ignored",str("no"))),keys.getPublic))
    val version = signed.mapObject(_.add("signed",field(signed,"signed").mapObject(_.add("version",str("future")))))
    intercept[PolicyFailure](verify(version,keys.getPublic))
  }
  test("a valid older signature cannot answer a new challenge, request, installation or response version") {
    val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val install = java.util.UUID.randomUUID().toString
    val nonce = java.util.UUID.randomUUID().toString
    val requestHash = "a" * 64
    val body = obj("version" -> str("policy-response-v1"),"installation" -> str(install),
      "challenge" -> str(nonce),"requestDigest" -> str(requestHash),"result" -> obj())
    def signed(value: Json): Json = sign(value,keys.getPrivate,keys.getPublic)
    assertEquals(PolicyWitnessClient.verifyResponse(signed(body),keys.getPublic,install,nonce,requestHash),obj())
    for (k,v) <- Vector("version" -> "future","installation" -> java.util.UUID.randomUUID().toString,
        "challenge" -> java.util.UUID.randomUUID().toString,"requestDigest" -> zero) do
      intercept[PolicyFailure](PolicyWitnessClient.verifyResponse(signed(body.mapObject(_.add(k,str(v)))),
        keys.getPublic,install,nonce,requestHash))
  }
