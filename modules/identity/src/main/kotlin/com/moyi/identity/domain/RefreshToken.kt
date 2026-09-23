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
    val deviceInfo: String?,
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
            deviceInfo: String?,
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
                deviceInfo = deviceInfo,
            )
    }
}

internal class RefreshTokenInvalidException : RuntimeException("The refresh token was not accepted")
