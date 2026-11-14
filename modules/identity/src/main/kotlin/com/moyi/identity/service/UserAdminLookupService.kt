package com.moyi.identity.service

import com.moyi.identity.api.UserAdminLookup
import com.moyi.identity.api.UserAdminView
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.AccountSuspensionStore
import com.moyi.identity.infra.database.DeletionRequestStore
import com.moyi.identity.infra.database.RefreshTokenStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The [UserAdminLookup] port. Live sessions are counted with the query behind
 * `GET /auth/sessions` ([ListSessions]): the live tokens are the live families. A suspension's
 * start and a pending deletion's due instant are read from their own stores (A3).
 */
@Service
internal class UserAdminLookupService(
    private val accounts: AccountStore,
    private val tokens: RefreshTokenStore,
    private val suspensions: AccountSuspensionStore,
    private val requests: DeletionRequestStore,
    private val clock: Clock,
) : UserAdminLookup {
    @Transactional(readOnly = true)
    override fun byId(id: UUID): UserAdminView? = accounts.findById(UserId(id))?.toView()

    @Transactional(readOnly = true)
    override fun byEmail(email: String): UserAdminView? {
        val address = runCatching { Email(email.trim()) }.getOrNull() ?: return null
        return accounts.findByEmail(address)?.toView()
    }

    private fun User.toView(): UserAdminView {
        val now = clock.instant()
        return UserAdminView(
            id = id.value,
            email = email.value,
            displayName = displayName,
            locale = locale,
            createdAt = createdAt,
            emailVerified = isEmailVerified,
            lockedUntil = accounts.findCredentials(id)?.lockedUntil?.takeIf { it.isAfter(now) },
            liveSessions = tokens.findLiveOf(id, now).size,
            tokensInvalidBefore = tokensInvalidBefore,
            status = status.toAccountStatus(),
            suspendedSince = suspensions.openSpanSince(id),
            deletionDueAt = requests.findPending(id)?.cancellableUntil,
        )
    }
}
