package com.moyi.admin.service

import com.moyi.admin.domain.AdminStatus
import com.moyi.admin.infra.AdminAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Whether a signed-in admin session may go on (spec §6): the account is still `ACTIVE`, its
 * credentials have not changed since the session captured them, and the absolute lifetime
 * has not run out. Asked on every admin request, so disabling or re-inviting an account ends
 * its live sessions on their next request. A value the session lacks is a session that may
 * not go on.
 */
@Service
@ConditionalOnAdminConsole
internal class LiveAdminSessions(
    private val accounts: AdminAccountRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun isLive(
        adminId: UUID,
        credentialsChangedAt: Instant?,
        absoluteDeadline: Instant?,
    ): Boolean {
        val withinLifetime = absoluteDeadline != null && !clock.instant().isAfter(absoluteDeadline)
        if (!withinLifetime || credentialsChangedAt == null) return false
        val account = accounts.findById(adminId).orElse(null)
        return account?.status == AdminStatus.ACTIVE && account.credentialsChangedAt == credentialsChangedAt
    }
}
