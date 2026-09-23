package com.moyi.identity.service

import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.PasswordResetTokenExpiredException
import com.moyi.identity.domain.PasswordResetTokenInvalidException
import com.moyi.identity.domain.SecurityNotice
import com.moyi.identity.domain.TokenHash
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.identity.infra.database.VerificationTokenStore
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
        if (!existing.isLive(clock.instant())) throw PasswordResetTokenExpiredException()

        val passwordHash = hasher.hash(password)
        apply.apply(hash, passwordHash, clock.instant())
    }
}

/** The transactional half of [ResetPassword]. */
@Component
internal class ApplyPasswordReset(
    private val tokens: VerificationTokenStore,
    private val accounts: AccountStore,
    private val refreshTokens: RefreshTokenStore,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    @Suppress("ThrowsCount")
    fun apply(
        tokenHash: TokenHash,
        newPasswordHash: PasswordHash,
        now: Instant,
    ) {
        // The single race winner. A second presentation finds the row consumed.
        if (!tokens.consume(tokenHash, now)) throw PasswordResetTokenExpiredException()
        val token = tokens.findByHash(tokenHash, VerificationPurpose.PASSWORD_RESET) ?: throw PasswordResetTokenInvalidException()
        val user = accounts.findById(token.userId) ?: throw PasswordResetTokenInvalidException()
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
        // T-17: the old address is told, after the commit, that the password
        // changed and every device was signed out.
        events.publishEvent(SecurityNotice.PasswordChanged(user.id, user.email, user.displayName))
    }
}
