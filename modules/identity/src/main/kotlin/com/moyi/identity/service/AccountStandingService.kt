package com.moyi.identity.service

import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.AccountStatus
import com.moyi.identity.api.SuspensionSpan
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.infra.database.AccountSuspensionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The [AccountStanding] port: `account_suspensions` read, with no lock (spec §8: a caller
 * holding a bond lock never waits on identity). `readOnly`, as [AccountDirectory]'s reads.
 */
@Service
internal class AccountStandingService(
    private val suspensions: AccountSuspensionStore,
) : AccountStanding {
    @Transactional(readOnly = true)
    override fun suspendedAmong(userIds: Collection<UUID>): Set<UUID> =
        if (userIds.isEmpty()) emptySet() else suspensions.openAmong(userIds)

    @Transactional(readOnly = true)
    override fun suspensionsOf(userIds: Collection<UUID>): List<SuspensionSpan> =
        if (userIds.isEmpty()) emptyList() else suspensions.spansOf(userIds)
}

/** Identity's own status as the api names it: one to one, and a new status will not compile until it is named. */
internal fun UserStatus.toAccountStatus(): AccountStatus =
    when (this) {
        UserStatus.PENDING_VERIFICATION -> AccountStatus.PENDING_VERIFICATION
        UserStatus.ACTIVE -> AccountStatus.ACTIVE
        UserStatus.SUSPENDED -> AccountStatus.SUSPENDED
        UserStatus.PENDING_DELETION -> AccountStatus.PENDING_DELETION
        UserStatus.DELETED -> AccountStatus.DELETED
    }
