package com.moyi.identity.service

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationRequested
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.security.VerificationProperties
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class VerificationEmailComposerTest {
    private val composer =
        VerificationEmailComposer(
            VerificationProperties(URI("https://moyi.test/verify"), URI("https://moyi.test/reset-password")),
        )

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
    fun `a password-reset event links to the reset page, not the verify page`() {
        // The first version sent both to the verify page. A reset token POSTed
        // to /verify-email is "not recognised" (its purpose is wrong), so every
        // reset link would have been dead on arrival.
        val message = composer.compose(event(purpose = VerificationPurpose.PASSWORD_RESET, secret = "example-reset-for-tests"))

        message.subject shouldContain "password"
        message.text shouldContain "https://moyi.test/reset-password?token=example-reset-for-tests"
        message.text shouldNotContain "/verify?"
        message.text shouldContain "1 hour"
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

    @Test
    fun `both emails carry the code on a line of its own, with how long it works`() {
        VerificationPurpose.entries.forEach { purpose ->
            val message = composer.compose(event(purpose = purpose, code = "004217"))

            message.text.lines() shouldContain "004217"
            message.text shouldContain "It works for 15 minutes"
            message.html shouldContain "<strong>004217</strong>"
        }
    }

    private fun event(
        purpose: VerificationPurpose = VerificationPurpose.EMAIL_VERIFICATION,
        secret: String = "s",
        displayName: String = "Ada",
        tokenId: UUID = UUID.randomUUID(),
        code: String = "042817",
    ) = VerificationRequested(
        userId = UserId(UUID.randomUUID()),
        email = Email("ada@example.com"),
        displayName = displayName,
        locale = "en",
        purpose = purpose,
        tokenId = tokenId,
        secret = VerificationSecret(secret),
        expiresAt = ISSUED.plus(purpose.ttl),
        code = VerificationCode(code),
        codeExpiresAt = ISSUED.plus(Duration.ofMinutes(15)),
    )

    private companion object {
        val ISSUED: Instant = Instant.parse("2026-09-23T12:00:00Z")
    }
}
