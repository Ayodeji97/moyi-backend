package com.moyi.identity.service

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationRequested
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.security.VerificationProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.UUID

internal class VerificationEmailComposerTest {
    private val composer = VerificationEmailComposer(VerificationProperties(URI("https://moyi.test/verify")))

    @Test
    fun `the link carries the secret to the configured landing page`() {
        // Named so that gitleaks' stopword allowlist recognises it as a
        // fixture: a random-looking value here was reported as a leaked
        // API key on the first CI run, which is the scanner doing its job.
        val message = composer.compose(event(secret = "example-token-for-tests"))

        message.to shouldBe "ada@example.com"
        message.text shouldContain "https://moyi.test/verify?token=example-token-for-tests"
        message.html!! shouldContain "href=\"https://moyi.test/verify?token=example-token-for-tests\""
        // States.md's copy rule: say what the link does and how long it lasts.
        message.text shouldContain "works once"
        message.text shouldContain "24 hours"
    }

    @Test
    fun `the idempotency key is the token id, so one token cannot send twice`() {
        val tokenId = UUID.fromString("0199a6a0-0000-7000-8000-000000000001")

        composer.compose(event(tokenId = tokenId)).idempotencyKey shouldBe tokenId.toString()
    }

    @Test
    fun `a display name is rendered as text in the HTML, never as markup`() {
        // The HTML body is the one place user input is rendered as markup by
        // somebody else's software.
        val message = composer.compose(event(displayName = "<script>alert(1)</script>Ada"))

        message.html!! shouldNotContain "<script>"
        message.html!! shouldContain "&lt;script&gt;"
        // The plain-text body is text; nothing to escape, nothing lost.
        message.text shouldContain "<script>alert(1)</script>Ada"
    }

    @Test
    fun `a secret that needed encoding would be encoded rather than break the link`() {
        // The generator's alphabet needs none; this pins the behaviour if that ever changes.
        composer.compose(event(secret = "a b&c")).text shouldContain "token=a%20b%26c"
    }

    private fun event(
        secret: String = "s",
        displayName: String = "Ada",
        tokenId: UUID = UUID.randomUUID(),
    ) = VerificationRequested(
        userId = UserId(UUID.randomUUID()),
        email = Email("ada@example.com"),
        displayName = displayName,
        locale = "en",
        purpose = VerificationPurpose.EMAIL_VERIFICATION,
        tokenId = tokenId,
        secret = VerificationSecret(secret),
        expiresAt = Instant.parse("2026-09-24T12:00:00Z"),
    )
}
