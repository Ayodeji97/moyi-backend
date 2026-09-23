package com.moyi.identity.service

import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.PasswordResetTokenExpiredException
import com.moyi.identity.domain.PasswordResetTokenInvalidException
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.identity.infra.database.VerificationTokenStore
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Service
internal class ResetPassword(
    private val tokens: VerificationTokenStore,
    private val accounts: AccountStore,
    private val refreshTokens: RefreshTokenStore,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    @Suppress("ThrowsCount")
    fun reset(
        secret: VerificationSecret,
        password: Password,
    ) {
        val hash = secret.hash()
        val existing =
            tokens.findByHash(hash, VerificationPurpose.PASSWORD_RESET)
                ?: throw PasswordResetTokenInvalidException()
        val checkedAt = clock.instant()
        if (!existing.isLive(checkedAt)) throw PasswordResetTokenExpiredException()

        // Argon2id is deliberately outside the database transaction. The
        // conditional consume below remains the single race winner.
        val passwordHash = hasher.hash(password)
        val now = clock.instant()
        transactions.executeWithoutResult {
            if (!tokens.consume(hash, now)) throw PasswordResetTokenExpiredException()
            val user = accounts.findById(existing.userId) ?: throw PasswordResetTokenInvalidException()
            val credentials = accounts.findCredentials(user.id) ?: throw PasswordResetTokenInvalidException()
            accounts.updateCredentials(
                credentials.copy(
                    passwordHash = passwordHash,
                    passwordUpdatedAt = now,
                    failedAttempts = 0,
                    lockedUntil = null,
                ),
            )
            refreshTokens.revokeAllForUser(user.id, now)
            accounts.revokeAllSessions(user.id, now)
        }
    }
}
