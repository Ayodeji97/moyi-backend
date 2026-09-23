package com.moyi.common.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import java.util.UUID

internal class RevocationValidatorTest {
    private val userId = UUID.randomUUID()
    private val revocations = InMemoryTokenRevocations()
    private val validator = RevocationValidator(revocations)

    @Test
    fun `nothing revoked, token accepted`() {
        validator.validate(jwt(iat = T)).hasErrors() shouldBe false
    }

    @Test
    fun `a token from before the revocation is rejected`() {
        revocations.revokeBefore(userId, T)

        validator.validate(jwt(iat = T.minusSeconds(1))).hasErrors() shouldBe true
        validator.validate(jwt(iat = T.minusSeconds(3600))).hasErrors() shouldBe true
    }

    @Test
    fun `a token from the same second as the revocation, or after it, is accepted`() {
        // `iat` is whole seconds. A millisecond bump at T+0.5 s must not kill
        // the token the very next login issued at T+0.9 s, which reads as T.
        revocations.revokeBefore(userId, T.plusMillis(500))

        validator.validate(jwt(iat = T)).hasErrors() shouldBe false
        validator.validate(jwt(iat = T.plusSeconds(1))).hasErrors() shouldBe false
        validator.validate(jwt(iat = T.minusSeconds(1))).hasErrors() shouldBe true
    }

    @Test
    fun `a token for a user who does not exist is rejected`() {
        revocations.remove(userId)

        validator.validate(jwt(iat = T)).hasErrors() shouldBe true
    }

    @Test
    fun `a subject that is not a UUID is rejected rather than thrown on`() {
        validator.validate(jwt(iat = T, subject = "admin")).hasErrors() shouldBe true
    }

    private fun jwt(
        iat: Instant,
        subject: String = userId.toString(),
    ): Jwt =
        Jwt
            .withTokenValue("irrelevant")
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(iat)
            .expiresAt(iat.plusSeconds(900))
            .build()

    private companion object {
        val T: Instant = Instant.parse("2026-09-23T12:00:00Z")
    }
}
