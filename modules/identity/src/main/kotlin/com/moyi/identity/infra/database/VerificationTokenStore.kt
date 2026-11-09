package com.moyi.identity.infra.database

import com.moyi.identity.domain.TokenHash
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationToken
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The verification-token table, spoken in domain terms.
 *
 * Same job as [AccountStore]: the service deals in [VerificationToken] and
 * [TokenHash] and never imports a mapper or an entity. Deliberately not
 * transactional; the boundary is the service's (doc 18 §4).
 */
@Component
internal class VerificationTokenStore(
    private val tokens: VerificationTokenRepository,
) {
    fun insert(token: VerificationToken) {
        tokens.save(token.toEntity())
    }

    /** The token with this digest, if it was issued for [purpose]. A digest issued for another purpose is not found. */
    fun findByHash(
        hash: TokenHash,
        purpose: VerificationPurpose,
    ): VerificationToken? = tokens.findByTokenHashAndPurpose(hash.value, purpose)?.toDomain()

    /**
     * Spends the token if — and only if — it is still live at [now].
     *
     * @return `true` if this call consumed it. `false` means somebody else
     *   did, or it had expired, or it never existed; the caller that already
     *   read it as live and gets `false` here has lost a race, and must treat
     *   the token as spent.
     */
    fun consume(
        hash: TokenHash,
        now: Instant,
    ): Boolean = tokens.consume(hash.value, now) == 1

    /** Removes the person's other tokens for [purpose] that are still live at [now]. Returns how many there were. */
    fun deleteLive(
        userId: UserId,
        purpose: VerificationPurpose,
        now: Instant,
    ): Int = tokens.deleteLive(userId.value, purpose, now)

    /** [consume] by id: `true` if this call spent the token. */
    fun consume(
        id: UUID,
        now: Instant,
    ): Boolean = tokens.consumeById(id, now) == 1

    /** The row holding this person's code for [purpose], locked until the transaction ends; null if none holds one. */
    fun lockHeldCode(
        userId: UserId,
        purpose: VerificationPurpose,
    ): VerificationToken? = tokens.lockHeldCode(userId.value, purpose)?.toDomain()

    fun recordWrongCode(tokenId: UUID) {
        tokens.recordWrongCode(tokenId)
    }

    /** Takes back every code this person holds for [purpose], under the issue lock. */
    fun retireCodes(
        userId: UserId,
        purpose: VerificationPurpose,
    ): Int {
        tokens.lockCodeIssue(userId.value)
        return tokens.retireCodes(userId.value, purpose)
    }
}
