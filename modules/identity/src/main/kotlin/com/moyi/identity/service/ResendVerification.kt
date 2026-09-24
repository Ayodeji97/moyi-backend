package com.moyi.identity.service

import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimitDecision
import com.moyi.common.security.ratelimit.RateLimitExceededException
import com.moyi.common.security.ratelimit.RateLimiter
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.infra.database.AccountStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Issues a fresh verification token for an address and asks for it to be
 * emailed — or does nothing, indistinguishably (doc 06 §3.1: `202 always`).
 *
 * **Three cases, one outcome.** An unknown address, an address that is
 * already verified, and an address that is pending all return normally; only
 * the last one does anything. The caller's response is empty in every case,
 * so the endpoint cannot be asked "does this person have an account here" —
 * the same rule as ADR-0015 for registration. What this method does *not*
 * equalise is time: the pending path does one `INSERT` more than the others.
 * That is a difference of a millisecond against the network's tens, and the
 * per-IP rate limit (FR-012, ADR-0023, on the controller) is the control that bounds how
 * many samples anyone gets. Recorded in ADR-0018 as a known, accepted gap.
 *
 * **Earlier tokens are left alone.** Chirp invalidates a person's existing
 * tokens on every resend. Here they stay live until their own 24 hours run
 * out, because the common reason for pressing "send again" is that the first
 * email is *late*, not lost — and a late email whose link says "expired" the
 * moment it arrives reads as broken. Each token is 256 bits; several live at
 * once cost nothing. `VerifyEmail` retires the rest when any one is used.
 *
 * **Once a minute per address** — the cooldown `states.md` §1 promises the
 * "send again" button, consumed here because the address is in the body,
 * and for every address alike so the 429 is not an oracle either.
 */
@Service
internal class ResendVerification(
    private val accounts: AccountStore,
    private val verification: RequestVerification,
    private val rateLimiter: RateLimiter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun resend(email: Email) {
        val decision = rateLimiter.tryConsume(RateLimitBucket.AUTH_RESEND_EMAIL, email.value.lowercase())
        if (decision is RateLimitDecision.Rejected) throw RateLimitExceededException(decision)

        val user = accounts.findByEmail(email)
        if (user == null || user.isEmailVerified) {
            // Counted, not named: the *rate* of resends for addresses that
            // need none is a signal worth graphing (a stuck client, an
            // enumeration attempt); whose they were is not ours to log.
            log.info("Verification resend requested for an address that needs none; responding as accepted")
            return
        }

        // Same token, same event, same AFTER_COMMIT binding as registration.
        verification.request(user, VerificationPurpose.EMAIL_VERIFICATION)
    }
}
