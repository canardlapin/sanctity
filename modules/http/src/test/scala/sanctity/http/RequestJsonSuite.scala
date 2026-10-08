package sanctity.http

import munit.FunSuite
import sanctity.kernel.*

final class RequestJsonSuite extends FunSuite:
  private def parse(s: String) = RequestJson.parse(s.getBytes("UTF-8"))
  test("JSON rejects duplicate keys, invalid UTF-8, excessive bodies and nesting") {
    assert(parse("""{"path":"safe","path":"other"}""").isLeft)
    assert(RequestJson.parse(Array(0xc3.toByte, 0x28.toByte)).isLeft)
    assert(RequestJson.parse(Array.fill(RequestJson.maxBytes + 1)(32.toByte)).isLeft)
    assert(parse("[" * 34 + "0" + "]" * 34).isLeft)
  }
  test("public query grammar preserves session correlation and decimal precision") {
    val json = parse("""{"op":"hasSession","conditions":[{"op":"suffix","value":"T1w"},{"op":"metadataBelow","name":"RepetitionTime","value":1.234567890123456789}]}""").toOption.get
    assertEquals(RequestJson.participant(json), Right(ParticipantPredicate.HasSession(Vector(
      ArtifactPredicate.Suffix("T1w"), ArtifactPredicate.MetadataBelow("RepetitionTime", BigDecimal("1.234567890123456789"))))))
    assert(RequestJson.participant(parse("""{"op":"fieldAtLeast","name":"age","value":1e999999999}""").toOption.get).isLeft)
    assert(RequestJson.artifact(parse("""{"op":"suffix","value":"bold","unknown":"ignored?"}""").toOption.get).isLeft)
  }
