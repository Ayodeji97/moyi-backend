package com.moyi.identity.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The grace-window rule (ADR-0021, amended 2026-11-08), each condition on its own. */
internal class RefreshTokenTest {
    private val rotatedAt = Instant.parse("2026-11-08T12:00:00Z")
    private val successor = token(id = UUID.randomUUID())
    private val presented = token(id = UUID.randomUUID(), rotatedAt = rotatedAt, replacedBy = successor.id)

    @Test
    fun `a used token whose unused successor it names may be retried up to and including the window's end`() {
        presented.admitsRetry(successor, rotatedAt, WINDOW) shouldBe true
        presented.admitsRetry(successor, rotatedAt.plus(WINDOW), WINDOW) shouldBe true
        presented.admitsRetry(successor, rotatedAt.plus(WINDOW).plusNanos(1_000), WINDOW) shouldBe false
    }

    @Test
    fun `a used successor, a missing one or one it does not name means no retry`() {
        presented.admitsRetry(successor.copy(rotatedAt = rotatedAt, replacedBy = UUID.randomUUID()), rotatedAt, WINDOW) shouldBe false
        presented.admitsRetry(successor.copy(revokedAt = rotatedAt), rotatedAt, WINDOW) shouldBe false
        presented.admitsRetry(null, rotatedAt, WINDOW) shouldBe false
        presented.admitsRetry(token(id = UUID.randomUUID()), rotatedAt, WINDOW) shouldBe false
    }

    @Test
    fun `a voided, revoked or never-rotated token is never retried`() {
        presented.copy(voidedAt = rotatedAt, replacedBy = null).admitsRetry(successor, rotatedAt, WINDOW) shouldBe false
        // A voided row has no successor (V50's CHECK), so the case above is refused twice over; this one is
        // refused by `voidedAt` alone, which is the condition that stops two holders taking turns.
        presented.copy(voidedAt = rotatedAt).admitsRetry(successor, rotatedAt, WINDOW) shouldBe false
        presented.copy(revokedAt = rotatedAt).admitsRetry(successor, rotatedAt, WINDOW) shouldBe false
        presented.copy(rotatedAt = null).admitsRetry(successor, rotatedAt, WINDOW) shouldBe false
    }

    @Test
    fun `a window of zero is strict rotation, except at the very instant of the rotation`() {
        presented.admitsRetry(successor, rotatedAt.plusNanos(1_000), Duration.ZERO) shouldBe false
    }

    private fun token(
        id: UUID,
        rotatedAt: Instant? = null,
        replacedBy: UUID? = null,
    ) = RefreshToken(
        id = id,
        userId = UserId(USER),
        familyId = FAMILY,
        tokenHash = TokenHash("a".repeat(64)),
        issuedAt = this.rotatedAt.minusSeconds(60),
        expiresAt = this.rotatedAt.plus(RefreshToken.TTL),
        rotatedAt = rotatedAt,
        revokedAt = null,
        replacedBy = replacedBy,
        voidedAt = null,
        deviceId = null,
    )

    private companion object {
        val WINDOW: Duration = Duration.ofSeconds(30)
        val USER: UUID = UUID.randomUUID()
        val FAMILY: UUID = UUID.randomUUID()
    }
}
