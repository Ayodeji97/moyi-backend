package com.moyi.identity.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One opaque refresh credential in a rotation family. The secret is never stored. */
internal data class RefreshToken(
    val id: UUID,
    val userId: UserId,
    val familyId: UUID,
    val tokenHash: TokenHash,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rotatedAt: Instant?,
    val revokedAt: Instant?,
    val replacedBy: UUID?,
    /**
     * Set when a retry of this token's predecessor inside the grace window
     * replaced it before it was ever used (ADR-0021, amended 2026-11-08).
     * `rotatedAt` is set to the same instant, so a voided token is never live
     * and presenting it is a reuse; this is what keeps it from being retried
     * in its turn.
     */
    val voidedAt: Instant?,
    /** The [Device] the family started on, or `null` for a sign-in that did not describe itself. */
    val deviceId: UUID?,
) {
    fun isLive(now: Instant): Boolean = rotatedAt == null && revokedAt == null && expiresAt.isAfter(now)

    /**
     * The grace window (ADR-0021, amended 2026-11-08 for issue #136): may this
     * token, already rotated, be presented again and answered with a new pair
     * instead of being treated as a reuse?
     *
     * Only when every one of these holds: it was rotated by being used, not
     * voided; it is not revoked; its direct successor is the one given and is
     * still live, so nobody has used it yet; and no more than [window] has
     * passed since the rotation, counted from the *first* rotation so that a
     * retry does not extend it. A used successor means two parties hold the
     * family, which is a reuse whatever the time.
     */
    fun admitsRetry(
        successor: RefreshToken?,
        now: Instant,
        window: Duration,
    ): Boolean {
        val rotated = rotatedAt ?: return false
        return voidedAt == null &&
            revokedAt == null &&
            successor != null &&
            successor.id == replacedBy &&
            successor.isLive(now) &&
            !now.isAfter(rotated.plus(window))
    }

    companion object {
        val TTL: Duration = Duration.ofDays(30)

        @Suppress("LongParameterList")
        fun issue(
            id: UUID,
            userId: UserId,
            familyId: UUID,
            secret: VerificationSecret,
            now: Instant,
            deviceId: UUID?,
        ): RefreshToken =
            RefreshToken(
                id = id,
                userId = userId,
                familyId = familyId,
                tokenHash = secret.hash(),
                issuedAt = now,
                expiresAt = now.plus(TTL),
                rotatedAt = null,
                revokedAt = null,
                replacedBy = null,
                voidedAt = null,
                deviceId = deviceId,
            )
    }
}

/** Unknown, expired, or in a family already revoked. The client signs in again. */
internal class RefreshTokenInvalidException : RuntimeException("The refresh token was not accepted")

/**
 * An already-rotated token was presented (doc 09 §3). Either the thief or the
 * victim is holding a stale copy, and the system cannot tell which — so the
 * whole family is revoked, the person is emailed, and the client is told to
 * wipe what it holds (`TOKEN_REUSE_DETECTED`, doc 13).
 */
internal class RefreshTokenReusedException : RuntimeException("The refresh token was already rotated; its family has been revoked")
