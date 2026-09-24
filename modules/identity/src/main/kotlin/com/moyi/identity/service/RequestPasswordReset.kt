package com.moyi.identity.service

import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimitDecision
import com.moyi.common.security.ratelimit.RateLimitExceededException
import com.moyi.common.security.ratelimit.RateLimiter
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.infra.database.AccountStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class RequestPasswordReset(
    private val accounts: AccountStore,
    private val verification: RequestVerification,
    private val rateLimiter: RateLimiter,
) {
    /**
     * `202` always (doc 06 §3.1, FR-004): an unknown address, a suspended
     * account and a pending one all return normally; only an account that
     * could sign in (FR-002 includes the unverified — a person who forgot the
     * password before verifying is otherwise locked out of their own address)
     * gets a token and an email. Same non-disclosure rule as `ResendVerification`.
     *
     * FR-012: three an hour per address, counted for every address so the
     * 429 says nothing about whether one is registered. Consumed before the
     * lookup, and outside the write: a rejected request writes nothing.
     */
    @Transactional
    fun request(email: Email) {
        val decision = rateLimiter.tryConsume(RateLimitBucket.AUTH_RESET_EMAIL, email.value.lowercase())
        if (decision is RateLimitDecision.Rejected) throw RateLimitExceededException(decision)

        val user = accounts.findByEmail(email)
        if (user != null && user.canAuthenticate) {
            verification.request(user, VerificationPurpose.PASSWORD_RESET)
        }
    }
}
