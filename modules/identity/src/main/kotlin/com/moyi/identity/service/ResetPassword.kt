package com.moyi.identity.service

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.PasswordResetCodeRejectedException
import com.moyi.identity.domain.PasswordResetTokenExpiredException
import com.moyi.identity.domain.PasswordResetTokenInvalidException
import com.moyi.identity.domain.SecurityNotice
import com.moyi.identity.domain.User
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.domain.VerificationToken
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.identity.infra.database.VerificationTokenStore
import com.moyi.notification.api.PushTokens
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * FR-004 / T-17: a single-use, one-hour token replaces the password and signs
 * every device out — refresh families revoked and `tokens_invalid_before`
 * bumped, so outstanding access tokens die too (doc 06 §3.1). The old address
 * is emailed afterwards.
 *
 * Two halves, deliberately in two classes. This one reads the token to say
 * *which* failure it is, then computes the Argon2id hash of the new password
 * **outside** any transaction, as `RegisterUser` does. [ApplyPasswordReset]
 * is the transaction: the conditional consume that decides a race between
 * two presentations of one link, the credential update, the revocations and
 * the notice.
 */
@Service
internal class ResetPassword(
    private val tokens: VerificationTokenStore,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val apply: ApplyPasswordReset,
    private val codes: VerificationCodeCheck,
) {
    /**
     * @throws PasswordResetTokenInvalidException the token matches no reset token
     * @throws PasswordResetTokenExpiredException past its hour, or already used — including by a concurrent request
     */
    fun reset(
        secret: VerificationSecret,
        password: Password,
    ) {
        val hash = secret.hash()
        val existing = tokens.findByHash(hash, VerificationPurpose.PASSWORD_RESET) ?: throw PasswordResetTokenInvalidException()
        // Hashed only for a live link; a dead one, or one spent by a concurrent request, is the one 410.
        val applied = existing.isLive(clock.instant()) && apply.apply(existing, hasher.hash(password), clock.instant())
        if (!applied) throw PasswordResetTokenExpiredException()
    }

    /**
     * The same reset by the six digits from the email and the address (#135). Three steps,
     * for the reason the link path has two: the code is checked (and a wrong one counted and
     * committed) in a short transaction, the Argon2id hash is computed outside any, and
     * [ApplyPasswordReset] spends the row. A refused new password never gets this far (the
     * edge refuses it first), so it neither spends nor counts against the code.
     *
     * Between the check and the spend the row is not locked: a link presented in that gap
     * spends the row first, and this answers the code path's one refusal.
     *
     * @throws PasswordResetCodeRejectedException no account, no code, a wrong, expired, burned or spent one
     */
    fun reset(
        email: Email,
        code: VerificationCode,
        password: Password,
    ) {
        val accepted = accept(email, code)

        val passwordHash = hasher.hash(password)
        if (!apply.apply(accepted.token, passwordHash, clock.instant())) throw PasswordResetCodeRejectedException()
    }

    /**
     * Whether a typed reset code is right, before the new password is typed (#169, ADR-0022
     * amended 2026-11-10). A check, not a grant: a right code is left exactly as it was, live
     * and uncounted, and [reset] still needs the address, the code and the password. It is
     * [accept] and nothing else, so a wrong check is a wrong try on the reset's own counter
     * (the row's five) and its own daily ten, and every refusal is the reset's one answer
     * after the same work.
     *
     * @throws PasswordResetCodeRejectedException no account, no code, a wrong, expired, burned or spent one
     */
    fun check(
        email: Email,
        code: VerificationCode,
    ) {
        accept(email, code)
    }

    /**
     * The typed code's whole gate, shared by [check] and [reset] so the two cannot count
     * apart: the daily limit first, then the code checked, and a wrong one counted and
     * committed, in a transaction of its own.
     */
    private fun accept(
        email: Email,
        code: VerificationCode,
    ): AcceptedCode {
        codes.limit(email)
        return codes.checkAlone(email, VerificationPurpose.PASSWORD_RESET, code, clock.instant())
            ?: throw PasswordResetCodeRejectedException()
    }
}

/** The transactional half of [ResetPassword]. */
@Component
internal class ApplyPasswordReset(
    private val tokens: VerificationTokenStore,
    private val accounts: AccountStore,
    private val refreshTokens: RefreshTokenStore,
    private val events: ApplicationEventPublisher,
    private val pushTokens: PushTokens,
) {
    /**
     * @return `false` when [token] was spent first, by a concurrent presentation of its link
     *   or its code, or its account can no longer sign in (suspended); the password is unchanged,
     *   and the caller answers with its own path's refusal
     */
    @Transactional
    fun apply(
        token: VerificationToken,
        newPasswordHash: PasswordHash,
        now: Instant,
    ): Boolean {
        // The single race winner. A second presentation finds the row consumed.
        if (!tokens.consume(token.id, now)) return false
        // The sessions lock before anything of the account is read or written: the order sign-in,
        // logout-all and suspension take (spec A3 §8), so what is read below is read after any of
        // them that held it has committed.
        refreshTokens.lockSessionsOf(token.userId)
        val user = accounts.findById(token.userId) ?: throw PasswordResetTokenInvalidException()
        // A link or code issued before a suspension (admin console A3): re-read under the lock, so
        // a suspension that committed after the link was sent is seen here. The refusal is the
        // caller's own for a spent link or code, the same bytes. Not the same work: a link that was
        // used or ran out is refused before the password hash, this one a hash later, so it is
        // slower by one hash, as a lost race is. That tells the link's holder nothing a 410 on an
        // unused link does not already: who can present it knows it was never used. The row stays
        // consumed, so the answer itself does not tell a suspended account from a spent link.
        if (user.canAuthenticate) replacePassword(user, newPasswordHash, now)
        return user.canAuthenticate
    }

    /** The reset itself, for an account that may still sign in: the hash, every session, the notice. */
    private fun replacePassword(
        user: User,
        newPasswordHash: PasswordHash,
        now: Instant,
    ) {
        val credentials = accounts.findCredentials(user.id) ?: throw PasswordResetTokenInvalidException()

        accounts.updateCredentials(
            credentials.copy(
                passwordHash = newPasswordHash,
                passwordUpdatedAt = now,
                failedAttempts = 0,
                lockedUntil = null,
            ),
        )
        refreshTokens.revokeAllForUser(user.id, now)
        accounts.revokeAllSessions(user.id, now)
        // Every device was signed out, so none is told anything more (ADR-0044).
        pushTokens.forgetAllOf(user.id.value)
        // T-17: the old address is told, after the commit, that the password
        // changed and every device was signed out.
        events.publishEvent(SecurityNotice.PasswordChanged(user.id, user.email, user.displayName, changedAt = now))
        // A code from a later reset email is moot now that the password has changed (#135).
        tokens.retireCodes(user.id, VerificationPurpose.PASSWORD_RESET)
    }
}
