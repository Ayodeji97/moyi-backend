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
    /** The [Device] the family started on, or `null` for a sign-in that did not describe itself. */
    val deviceId: UUID?,
) {
    fun isLive(now: Instant): Boolean = rotatedAt == null && revokedAt == null && expiresAt.isAfter(now)

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
