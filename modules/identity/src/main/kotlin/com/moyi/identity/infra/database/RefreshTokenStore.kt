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

    fun findById(id: UUID): RefreshToken? = tokens.findById(id)?.toDomain()

    /**
     * Takes the per-user sessions lock for the rest of the current transaction.
     * **Call this before reading the state you are about to act on**: a token
     * read before the lock may have been rotated or revoked by the transaction
     * that held it. See `RefreshTokenRepository.lockSessions`.
     */
    fun lockSessionsOf(userId: UserId) {
        tokens.lockSessions(userId.value)
    }

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

    /** @return whether a live family with that id belonged to the user and is now revoked. */
    fun revokeFamilyOf(
        userId: UserId,
        familyId: UUID,
        now: Instant,
    ): Boolean = tokens.revokeFamilyOf(userId.value, familyId, now) > 0

    /** The one live token of each of the user's live families. */
    fun findLiveOf(
        userId: UserId,
        now: Instant,
    ): List<RefreshToken> = tokens.findLiveByUserId(userId.value, now).map { it.toDomain() }

    fun familyStartsOf(userId: UserId): Map<UUID, Instant> = tokens.familyStartsOf(userId.value).associate { it.familyId to it.createdAt }
}
