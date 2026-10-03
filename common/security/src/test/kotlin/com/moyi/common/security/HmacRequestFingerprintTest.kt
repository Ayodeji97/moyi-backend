package com.moyi.common.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Ruling P8: `request_hash` is a MAC, not a hash. A short entry must not be
 * recoverable from the stored value by hashing guesses.
 */
internal class HmacRequestFingerprintTest {
    private val fingerprint = HmacRequestFingerprint(PersonalDataHasher.from(HashingProperties(secret = "a".repeat(32))))
    private val body = """{"text":"thank you"}""".toByteArray()

    @Test
    fun `the same request fingerprints the same way, which is what lets a retry match`() {
        fingerprint.of("POST", PATH, body) shouldBe fingerprint.of("POST", PATH, body.copyOf())
        fingerprint.of("POST", PATH, body) shouldMatch Regex("[A-Za-z0-9_-]{43}")
    }

    @Test
    fun `two secrets give two fingerprints, so a guess cannot be checked without the secret`() {
        val other = HmacRequestFingerprint(PersonalDataHasher.from(HashingProperties(secret = "b".repeat(32))))

        fingerprint.of("POST", PATH, body) shouldNotBe other.of("POST", PATH, body)
    }

    @Test
    fun `it is not a plain SHA-256 of the body, nor of method, path and body, in any encoding`() {
        // Return a bare digest from HmacRequestFingerprint and one of these matches.
        val framed = "4:POST${PATH.length}:$PATH".toByteArray() + body
        val candidates =
            listOf(body, "POST".toByteArray() + PATH.toByteArray() + body, framed)
                .map { MessageDigest.getInstance("SHA-256").digest(it) }
                .flatMap { digest ->
                    listOf(
                        digest.joinToString("") { "%02x".format(it) },
                        Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
                        Base64.getEncoder().encodeToString(digest),
                    )
                }

        candidates.forEach { fingerprint.of("POST", PATH, body) shouldNotBe it }
    }

    @Test
    fun `the method, the path and the body each change it`() {
        val base = fingerprint.of("POST", PATH, body)

        fingerprint.of("PUT", PATH, body) shouldNotBe base
        fingerprint.of("POST", "$PATH/other", body) shouldNotBe base
        fingerprint.of("POST", PATH, """{"text":"thank you!"}""".toByteArray()) shouldNotBe base
    }

    @Test
    fun `the framing is unambiguous, so moving a byte between method, path and body changes it`() {
        // Concatenate without the length prefixes and each pair below collides.
        fingerprint.of("PO", "ST/x", body) shouldNotBe fingerprint.of("POST", "/x", body)
        fingerprint.of("POST", "/x", "y".toByteArray()) shouldNotBe fingerprint.of("POST", "/xy", ByteArray(0))
    }

    private companion object {
        const val PATH = "/api/v1/bonds/3f2a/entries"
    }
}
