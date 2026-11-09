package com.moyi.identity.service

import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimitDecision
import com.moyi.common.security.ratelimit.RateLimitExceededException
import com.moyi.common.security.ratelimit.RateLimiter
import com.moyi.identity.domain.CodeHash
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationCodeHasher
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationToken
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.VerificationTokenStore
import com.moyi.identity.infra.security.VerificationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One attempt at a typed code (#135): find the row holding this person's code, lock it,
 * ask the domain whether the code is the one, and count it if not.
 *
 * Not `@Transactional`, like [RequestVerification]: it joins the caller's transaction,
 * because the row lock has to be held across whatever the caller does with an accepted
 * code, and the caller has to commit a wrong attempt's count **before** it throws (a
 * rollback would un-count it, and an attacker would have unlimited tries).
 *
 * **Every path does the same work.** An unknown address, an account holding no code, a
 * dead code and a wrong one each run one lookup of the address, one locking `SELECT`, one HMAC, one constant-time
 * comparison and one `UPDATE` (against an id that names no row when there is nothing to
 * count); only the accepted code differs, by what it is accepted for. That is the
 * timing half of the one answer, the dummy hash login runs (ADR-0020 decision 2) in the
 * form this check has.
 */
@Component
internal class VerificationCodeCheck(
    private val tokens: VerificationTokenStore,
    private val hasher: VerificationCodeHasher,
    private val rateLimiter: RateLimiter,
    private val accounts: AccountStore,
    private val transactions: TransactionTemplate,
) {
    /**
     * FR-012 in #135's shape (ADR-0023, amended 2026-11-08): ten codes a day per address,
     * keyed on the address as typed and counted for every address alike, so the 429 says
     * nothing about whether one is registered. First, before any read: a refused attempt
     * costs nothing and is not counted against the code.
     *
     * **Fails closed** ([RateLimitBucket.failsClosed], owner's ruling 2026-11-09): when the
     * limiter cannot reach Redis, the code is refused rather than checked without the daily
     * bound. Still before any read, so the refusal is the same for every address and the code
     * is neither spent nor counted. Only this path: the link is never limited here.
     *
     * @throws RateLimitExceededException ten codes today already
     * @throws CodeCheckUnavailableException the daily count cannot be read
     */
    fun limit(email: Email) {
        val bucket = RateLimitBucket.AUTH_CODE_EMAIL
        when (val decision = rateLimiter.tryConsume(bucket, email.value.lowercase())) {
            is RateLimitDecision.Rejected -> throw RateLimitExceededException(decision)
            RateLimitDecision.Unavailable -> if (bucket.failsClosed) throw CodeCheckUnavailableException()
            is RateLimitDecision.Allowed, RateLimitDecision.Disabled -> Unit
        }
    }

    /**
     * Joins the caller's transaction, so the row stays locked for whatever the caller does
     * with an accepted code.
     *
     * @return the account and its token, the token still locked; null when the code is not
     *   accepted, after counting it against the code held, if one is
     */
    fun check(
        email: Email,
        purpose: VerificationPurpose,
        code: VerificationCode,
        now: Instant,
    ): AcceptedCode? {
        val user = accounts.findByEmail(email)
        val held = tokens.lockHeldCode(user?.id ?: NOBODY, purpose)
        val presented = hasher.hash(held?.id ?: NO_TOKEN, purpose, code)
        if (user != null && VerificationToken.acceptsCode(held, presented, now)) return AcceptedCode(user, held!!)
        tokens.recordWrongCode(held?.id ?: NO_TOKEN)
        return null
    }

    /** [check] in a transaction of its own, committed before this returns: a refusal's count included. */
    fun checkAlone(
        email: Email,
        purpose: VerificationPurpose,
        code: VerificationCode,
        now: Instant,
    ): AcceptedCode? = transactions.execute { check(email, purpose, code, now) }

    private companion object {
        /** Ids no row has: ids here are time-ordered (`IdGenerator.timeOrdered`), and the nil UUID is not one. */
        val NOBODY = UserId(UUID(0L, 0L))
        val NO_TOKEN = UUID(0L, 0L)
    }
}

/**
 * The typed-code limit could not be read, so the code was not checked (ADR-0023, amended
 * 2026-11-09). Carries nothing about the address or the code: the answer is the same for all.
 */
internal class CodeCheckUnavailableException : RuntimeException("The typed-code limit cannot be read; codes are refused until it can")

/** A code [VerificationCodeCheck] accepted: whose, and the row it was on. */
internal data class AcceptedCode(
    val user: User,
    val token: VerificationToken,
)

/**
 * Mints a code for a row about to be inserted (#135): the six digits for the email, their
 * keyed hash for the row, and how long they may be typed. Beside [VerificationCodeCheck]
 * because the two are the code's whole life, issued and presented.
 */
@Component
internal class VerificationCodeIssuer(
    private val generator: TokenGenerator,
    private val hasher: VerificationCodeHasher,
    private val properties: VerificationProperties,
) {
    fun issue(
        tokenId: UUID,
        purpose: VerificationPurpose,
    ): IssuedCode {
        val code = generator.verificationCode()
        return IssuedCode(code, hasher.hash(tokenId, purpose, code), properties.codeTtl)
    }
}

/** What [VerificationCodeIssuer] made: [code] goes in the email, [hash] in the row. */
internal data class IssuedCode(
    val code: VerificationCode,
    val hash: CodeHash,
    val ttl: Duration,
)
