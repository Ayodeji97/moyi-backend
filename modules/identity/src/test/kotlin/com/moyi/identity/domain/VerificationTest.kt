package com.moyi.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class VerificationTest {
    @Test
    fun `the hash is SHA-256 of the secret, lowercase hex`() {
        // A known vector, so the digest is pinned to the algorithm rather than
        // to whatever the code happens to compute: a builder that switched to
        // SHA-1 or added a salt would still produce 64 hex characters, and
        // would still never match a stored row.
        VerificationSecret("abc").hash().value shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `the same secret always hashes the same, and different secrets differ`() {
        VerificationSecret("one").hash() shouldBe VerificationSecret("one").hash()
        VerificationSecret("one").hash() shouldNotBe VerificationSecret("two").hash()
    }

    @Test
    fun `a secret does not print itself`() {
        // It is a bearer credential inside an event that somebody will log.
        val printed = VerificationSecret("s3cret-value").toString()

        printed shouldNotContain "s3cret"
        VerificationRequested(
            userId = UserId(UUID.randomUUID()),
            email = Email("ada@example.com"),
            displayName = "Ada",
            locale = "en",
            purpose = VerificationPurpose.EMAIL_VERIFICATION,
            tokenId = UUID.randomUUID(),
            secret = VerificationSecret("s3cret-value"),
            expiresAt = NOW,
            code = VerificationCode("024680"),
            codeExpiresAt = NOW,
        ).toString().let {
            it shouldNotContain "s3cret"
            it shouldNotContain "024680"
        }
    }

    @Test
    fun `a blank secret is refused, but no other shape is`() {
        // What a person presents is whatever their mail client gave them; the
        // answer to a mangled token is "not recognised", not a 500.
        shouldThrow<IllegalArgumentException> { VerificationSecret(" ") }
        VerificationSecret("anything-at-all%20even-this")
    }

    @Test
    fun `a token hash is exactly 64 lowercase hex characters`() {
        shouldThrow<IllegalArgumentException> { TokenHash("abc") }
        shouldThrow<IllegalArgumentException> { TokenHash("A".repeat(64)) }
        TokenHash("0".repeat(64))
    }

    @Test
    fun `a token is live until it is consumed or expires`() {
        val token =
            VerificationToken.issue(
                id = UUID.randomUUID(),
                userId = UserId(UUID.randomUUID()),
                purpose = VerificationPurpose.EMAIL_VERIFICATION,
                secret = SECRET,
                codeHash = CODE_HASH,
                codeTtl = CODE_TTL,
                now = NOW,
            )

        token.expiresAt shouldBe NOW.plus(TTL)
        token.tokenHash shouldBe SECRET.hash()
        token.isLive(NOW) shouldBe true
        token.isLive(NOW.plus(TTL).minusMillis(1)) shouldBe true
        // Exactly at expiry is expired: "expires at" is the first moment it does not work.
        token.isLive(NOW.plus(TTL)) shouldBe false
        token.copy(consumedAt = NOW).isLive(NOW) shouldBe false
    }

    @Test
    fun `the email verification TTL is FR-002's 24 hours`() {
        VerificationPurpose.EMAIL_VERIFICATION.ttl shouldBe Duration.ofHours(24)
    }

    @Test
    fun `a code is six ASCII digits, and nothing else`() {
        VerificationCode("000000").value shouldBe "000000"
        VerificationCode("987654")
        listOf("", "12345", "1234567", "12 456", "12a456", "١٢٣٤٥٦", "123456\n").forEach { value ->
            shouldThrow<IllegalArgumentException> { VerificationCode(value) }.message shouldNotContain value.ifEmpty { "x" }
        }
        VerificationCode("135790").toString() shouldNotContain "135790"
        CODE_HASH.toString() shouldNotContain CODE_HASH.value
    }

    @Test
    fun `a code may be typed for its own minutes, never past its link, while the row is live`() {
        val token = issued()

        token.codeExpiresAt shouldBe NOW.plus(CODE_TTL)
        token.codeIsLive(NOW) shouldBe true
        token.codeIsLive(NOW.plus(CODE_TTL).minusMillis(1)) shouldBe true
        token.codeIsLive(NOW.plus(CODE_TTL)) shouldBe false
        token.copy(consumedAt = NOW).codeIsLive(NOW) shouldBe false
        token.copy(codeHash = null, codeExpiresAt = null).codeIsLive(NOW) shouldBe false

        val longerThanItsLink =
            VerificationToken.issue(
                id = UUID.randomUUID(),
                userId = UserId(UUID.randomUUID()),
                purpose = VerificationPurpose.PASSWORD_RESET,
                secret = SECRET,
                codeHash = CODE_HASH,
                codeTtl = Duration.ofHours(2),
                now = NOW,
            )
        longerThanItsLink.codeExpiresAt shouldBe longerThanItsLink.expiresAt
    }

    @Test
    fun `five wrong codes burn it, four do not`() {
        val token = issued()

        token.copy(codeAttempts = VerificationToken.MAX_WRONG_CODES - 1).codeIsLive(NOW) shouldBe true
        token.copy(codeAttempts = VerificationToken.MAX_WRONG_CODES).codeIsLive(NOW) shouldBe false
        VerificationToken.MAX_WRONG_CODES shouldBe 5
    }

    @Test
    fun `the check accepts only the held code, while it is live`() {
        val token = issued()

        VerificationToken.acceptsCode(token, CODE_HASH, NOW) shouldBe true
        VerificationToken.acceptsCode(token, CodeHash("another"), NOW) shouldBe false
        VerificationToken.acceptsCode(null, CODE_HASH, NOW) shouldBe false
        VerificationToken.acceptsCode(null, CodeHash.UNMATCHABLE, NOW) shouldBe false
        VerificationToken.acceptsCode(token.copy(codeAttempts = 5), CODE_HASH, NOW) shouldBe false
        VerificationToken.acceptsCode(token, CODE_HASH, NOW.plus(CODE_TTL)) shouldBe false
        VerificationToken.acceptsCode(token.copy(consumedAt = NOW), CODE_HASH, NOW) shouldBe false
    }

    private fun issued() =
        VerificationToken.issue(
            id = UUID.randomUUID(),
            userId = UserId(UUID.randomUUID()),
            purpose = VerificationPurpose.EMAIL_VERIFICATION,
            secret = SECRET,
            codeHash = CODE_HASH,
            codeTtl = CODE_TTL,
            now = NOW,
        )

    private companion object {
        val CODE_HASH = CodeHash("a-keyed-hash-of-the-code")
        val CODE_TTL: Duration = Duration.ofMinutes(15)
        val NOW: Instant = Instant.parse("2026-09-23T12:00:00Z")
        val TTL: Duration = Duration.ofHours(24)
        val SECRET = VerificationSecret("a-known-secret")
    }
}
