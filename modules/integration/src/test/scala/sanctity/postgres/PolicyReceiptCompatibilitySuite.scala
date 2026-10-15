package sanctity.postgres

import munit.FunSuite
import sanctity.kernel.*

final class PolicyReceiptCompatibilitySuite extends FunSuite:
  import OrganizationCodecs.given
  private val legacy = """{"version":1,"value":{"sequence":7,"actor":"00000000-0000-0000-0000-000000000001","key":"old-organization-write","affected":[],"at":"2026-01-01T00:00:00Z"}}"""

  test("V008 organization receipts decode as local-only without a history rewrite") {
    val receipt=JsonCodecs.decode[OrganizationReceipt](legacy).toOption.get
    assertEquals(receipt.sequence,7L)
    assertEquals(receipt.durability,PolicyDurability.LocalOnly)
    val current=JsonCodecs.encode(receipt)
    assert(current.contains("\"durability\":\"LocalOnly\""))
    assertEquals(JsonCodecs.decode[OrganizationReceipt](current),Right(receipt))
  }

  test("unknown durability cannot be decoded as local success") {
    Vector("future-version").foreach { state =>
      val forged=legacy.replace("\"sequence\":7",s"\"sequence\":7,\"durability\":\"$state\"")
      assert(JsonCodecs.decode[OrganizationReceipt](forged).isLeft)
    }
  }

  test("pending and durable status remain explicit in the receipt codec") {
    Vector(PolicyDurability.Pending,PolicyDurability.Durable).foreach { state =>
      val receipt=JsonCodecs.decode[OrganizationReceipt](legacy).toOption.get.copy(durability=state)
      assertEquals(JsonCodecs.decode[OrganizationReceipt](JsonCodecs.encode(receipt)),Right(receipt))
    }
  }
