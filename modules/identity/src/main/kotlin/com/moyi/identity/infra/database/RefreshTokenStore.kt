package com.moyi.identity.infra.database

import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.TokenHash
import com.moyi.identity.domain.UserId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The refresh-token table in domain terms, like [VerificationTokenStore]. The
 * conditional statements — [rotate] especially — are where the concurrency
 * guarantees live; see `RefreshTokenRepository`.
 */
@Component
internal class RefreshTokenStore(
    private val tokens: RefreshTokenRepository,
) {
    fun insert(token: RefreshToken) {
        tokens.save(token.toEntity())
    }

    fun findByHash(hash: TokenHash): RefreshToken? = tokens.findByTokenHash(hash.value)?.toDomain()

    fun rotate(
        id: UUID,
        replacementId: UUID,
        now: Instant,
    ): Boolean = tokens.rotate(id, replacementId, now) == 1

    /** Revokes every token in the family that is not already revoked. @return how many that was. */
    fun revokeFamily(
        familyId: UUID,
        now: Instant,
    ): Int = tokens.revokeFamily(familyId, now)

    fun revokeAllForUser(
        userId: UserId,
        now: Instant,
    ) {
        tokens.revokeAllForUser(userId.value, now)
    }
}
