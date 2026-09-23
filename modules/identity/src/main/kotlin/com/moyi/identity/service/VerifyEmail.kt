package com.moyi.identity.service

import com.moyi.identity.domain.TokenHash
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.domain.VerificationToken
import com.moyi.identity.domain.VerificationTokenExpiredException
import com.moyi.identity.domain.VerificationTokenInvalidException
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.VerificationTokenStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Spends a verification token and marks the address confirmed (FR-002).
 *
 * `@Transactional` on the method, unlike `RegisterUser`'s explicit template,
 * because nothing here has to happen outside the boundary and nothing here
 * has to survive a failure inside it: every exception rolls the whole thing
 * back, which is the behaviour wanted. The two exceptions the web layer
 * translates are thrown from inside, so a 422 or a 410 leaves no row changed.
 *
 * **Read, then compare-and-set.** The token is read first so the *reason* it
 * cannot be used can be told apart — unknown versus spent — and then
 * [VerificationTokenStore.consume] flips it with a conditional `UPDATE` that
 * re-checks liveness in the database. The read alone would let two
 * concurrent presentations of one token both succeed; the `UPDATE` alone
 * could not distinguish "never existed" from "already used". Both, in that
 * order, and the answer to the PR template's "runs twice concurrently?" is
 * that exactly one caller gets `true` from `consume`.
 */
@Service
internal class VerifyEmail(
    private val tokens: VerificationTokenStore,
    private val accounts: AccountStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws VerificationTokenInvalidException the token matches nothing
     * @throws VerificationTokenExpiredException the token is past its 24 hours or was already used — including by a
     *   concurrent request that won the race
     */
    @Transactional
    fun verify(presented: VerificationSecret) {
        val now = clock.instant()
        val hash = presented.hash()
        val token = liveTokenFor(hash, now)

        // The point of no return, and the only statement that decides who won.
        if (!tokens.consume(hash, now)) throw VerificationTokenExpiredException()

        // The FK cascade makes a token without a user impossible; the branch
        // exists so the impossible is a 422 rather than a null dereference.
        val user = accounts.findById(token.userId) ?: throw VerificationTokenInvalidException()
        accounts.update(user.verifyEmail(now))

        // Any other link still in this person's inbox is now moot. Deleted
        // rather than left to expire, so a stale one presented later is "not
        // recognised" rather than a second, pointless verification.
        val retired = tokens.deleteLive(user.id, VerificationPurpose.EMAIL_VERIFICATION)

        // A user id, not an address (doc 11 NFR-044). The id is what a trace
        // needs and is meaningless outside this database.
        log.info("Email verified for user {}; {} other live token(s) retired", user.id.value, retired)
    }

    /** The read half: which of the two failures this is, if it is one. The write half is `consume`. */
    private fun liveTokenFor(
        hash: TokenHash,
        now: Instant,
    ): VerificationToken {
        val token = tokens.findByHash(hash) ?: throw VerificationTokenInvalidException()
        if (!token.isLive(now)) throw VerificationTokenExpiredException()
        return token
    }
}
