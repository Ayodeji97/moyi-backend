package com.moyi.common.security

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * An `ETag` is a MAC of the response body, not a hash of it: a short entry
 * must not be recoverable from a logged tag by hashing guesses.
 */
internal class HmacRepresentationDigestTest {
    private val hasher = PersonalDataHasher.from(HashingProperties(secret = SECRET))
    private val digest = HmacRepresentationDigest(hasher)
    private val body = """{"date":"2026-09-15","myEntry":{"text":"thank you"}}""".toByteArray()

    @Test
    fun `the same bytes give the same tag, and one changed byte another`() {
        digest.of(body) shouldBe digest.of(body.copyOf())
        digest.of(body) shouldMatch Regex("[0-9a-f]{64}")
        digest.of(body) shouldNotBe digest.of("""{"date":"2026-09-15","myEntry":{"text":"thank yov"}}""".toByteArray())
    }

    @Test
    fun `two secrets give two tags, so a guess at the body cannot be checked without the secret`() {
        val other = HmacRepresentationDigest(PersonalDataHasher.from(HashingProperties(secret = "b".repeat(32))))

        digest.of(body) shouldNotBe other.of(body)
        // And a secret made at startup is not the one made at the last startup.
        val ephemeral = HashingProperties(secretSource = HashingProperties.SecretSource.EPHEMERAL)
        HmacRepresentationDigest(PersonalDataHasher.from(ephemeral)).of(body) shouldNotBe
            HmacRepresentationDigest(PersonalDataHasher.from(ephemeral)).of(body)
    }

    @Test
    fun `it is not a plain SHA-256 of the body, with or without the label, in any spelling`() {
        // Return a bare digest from HmacRepresentationDigest and one of these matches.
        val candidates =
            listOf(body, HmacRepresentationDigest.DOMAIN + body)
                .map { MessageDigest.getInstance("SHA-256").digest(it) }
                .flatMap { hash ->
                    listOf(
                        hash.toHexString(),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(hash),
                        Base64.getEncoder().encodeToString(hash),
                    )
                }

        candidates shouldNotContain digest.of(body)
    }

    @Test
    fun `it is the HMAC-SHA256 of a label and the body under the personal-data secret, and no other use of that secret`() {
        // Computed here from the secret, not asked of the hasher: what the tag is, stated independently.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")) }
        digest.of(body) shouldBe mac.doFinal("moyi-etag-v1\n".toByteArray() + body).toHexString()

        // Not the MAC of the bare body, which is what an address hash or a fingerprint of the same bytes would be made of.
        digest.of(body) shouldNotBe hasher.mac(body).toHexString()
        Base64.getUrlDecoder().decode(hasher.hash(body)).toHexString() shouldNotBe digest.of(body)
        // And `hash` is still the MAC it was, spelt as it was.
        hasher.hash(body) shouldBe Base64.getUrlEncoder().withoutPadding().encodeToString(hasher.mac(body))
    }

    private companion object {
        val SECRET = "a".repeat(32)
    }
}
