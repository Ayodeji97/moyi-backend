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
@Suppress("TooManyFunctions") // One per statement of RefreshTokenRepository, in domain terms.
internal class RefreshTokenStore(
    private val tokens: RefreshTokenRepository,
) {
    fun insert(token: RefreshToken) {
        tokens.save(token.toEntity())
    }

    fun findByHash(hash: TokenHash): RefreshToken? = tokens.findByTokenHash(hash.value)?.toDomain()

    /** The token a hash names: its id and owner, enough to take the sessions lock before reading it. */
    fun findRefByHash(hash: TokenHash): RefreshTokenRef? = tokens.findRefByTokenHash(hash.value)

    /** The row, read fresh and locked for the rest of the transaction. Call it after [lockSessionsOf]. */
    fun lockById(id: UUID): RefreshToken? = tokens.findByIdForUpdate(id)?.toDomain()

    /** @return whether the unused token is now voided (ADR-0021, amended 2026-11-08). */
    fun void(
        id: UUID,
        now: Instant,
    ): Boolean = tokens.void(id, now) == 1

    /** @return whether the rotated token now names [replacementId] instead of [voidedId] as its successor. */
    fun repoint(
        id: UUID,
        voidedId: UUID,
        replacementId: UUID,
    ): Boolean = tokens.repoint(id, voidedId, replacementId) == 1

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
