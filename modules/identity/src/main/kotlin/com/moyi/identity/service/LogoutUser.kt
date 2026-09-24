package com.moyi.identity.service

import com.moyi.common.security.CurrentUser
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/** Revokes refresh credentials without revealing whether a presented token exists. */
@Service
internal class LogoutUser(
    private val refreshTokens: RefreshTokenStore,
    private val accounts: AccountStore,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    fun logout(rawRefreshToken: String) {
        val now = clock.instant()
        transactions.executeWithoutResult {
            val hash = runCatching { VerificationSecret(rawRefreshToken.trim()).hash() }.getOrNull() ?: return@executeWithoutResult
            val token = refreshTokens.findByHash(hash) ?: return@executeWithoutResult
            refreshTokens.lockSessionsOf(token.userId)
            refreshTokens.revokeFamily(token.familyId, now)
        }
    }

    fun logoutAll(currentUser: CurrentUser) {
        val now = clock.instant()
        transactions.executeWithoutResult {
            refreshTokens.lockSessionsOf(UserId(currentUser.id))
            refreshTokens.revokeAllForUser(UserId(currentUser.id), now)
            accounts.revokeAllSessions(UserId(currentUser.id), now)
        }
    }
}
