package com.moyi.common.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * **The three things made with the personal-data secret, as literal values.**
 *
 * Every other test of [PersonalDataHasher], [HmacRequestFingerprint] and
 * [HmacRepresentationDigest] asks the code under test twice and compares
 * its answers, or works the expected value out with the same `Mac` the code
 * calls. Each of those moves with the code: change the algorithm inside
 * `mac` to another with a thirty-two byte output and they all still agree
 * with themselves.
 *
 * **What breaks if a value here changes**, for the same secret:
 *
 *  - the hash: every stored `consent_records.ip_hash` (and every hashed
 *    address or e-mail in a rate-limit key) stops matching the value the
 *    running application computes for the same address. Nothing fails; the
 *    rows are simply orphaned.
 *  - the fingerprint: every `idempotency_keys.request_hash` recorded before
 *    the change no longer matches its own retry, so a client repeating a
 *    request under its `Idempotency-Key` is refused as a reuse of the key
 *    instead of being given the recorded answer.
 *  - the tag: every `ETag` a client holds earns a full `200` once. That one
 *    is harmless, and is pinned so that a change to it is a decision.
 *
 * So a change that turns this test red is a migration, not a refactoring:
 * it needs a plan for the stored rows, and an ADR.
 *
 * The values were computed **outside the JVM**, with openssl, for the secret
 * `a` × 32 (the same bytes as its key):
 *
 * ```
 * printf '203.0.113.7' | openssl dgst -sha256 -mac HMAC -macopt key:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa -binary | base64
 * ```
 *
 * then `+/` to `-_` and the padding dropped; the fingerprint's input is
 * `4:POST9:/api/v1/x{"a":1}` (each of method and path behind its length),
 * and the tag's is `moyi-etag-v1\n` followed by the body, printed with
 * `-hex`. They were seen to hold with the hasher as it was before `hash`
 * was routed through `mac` (the review of 5e27c3a), so nothing stored then
 * was orphaned by that change.
 */
internal class KnownAnswerTest {
    private val hasher = PersonalDataHasher.from(HashingProperties(secret = "a".repeat(32)))

    @Test
    fun `the hash of an address is the value stored rows were written with`() {
        hasher.hash("203.0.113.7") shouldBe "3Zaj62oGfS0c9kRkW73KM8Sda7XeDchbfm2Q95jZdNs"
        hasher.hash("203.0.113.7".toByteArray()) shouldBe "3Zaj62oGfS0c9kRkW73KM8Sda7XeDchbfm2Q95jZdNs"
    }

    @Test
    fun `the fingerprint of a request is the value recorded idempotency keys were written with`() {
        HmacRequestFingerprint(hasher).of("POST", "/api/v1/x", """{"a":1}""".toByteArray()) shouldBe
            "rfR8gkLEBv2dIrIYuLIa4eBeMFQ7XUTQghXDXTIXUYA"
    }

    @Test
    fun `the tag of a body is the value a client was last sent for it`() {
        HmacRepresentationDigest(hasher).of("""{"date":"2026-09-15","status":"REVEALED"}""".toByteArray()) shouldBe
            "31c9b8f86a4bb41cc07ee68ee9045392cd6354d981d29fb793481f66a96aedd2"
    }
}
