package com.moyi.bond.infra

import com.moyi.notification.api.CancelReason
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `notification`'s port for a context that does not scan `notification`: the migration tests'
 * nested configurations, which stop before the outbox exists and so can hold no ledger. `EndBond`
 * needs the port to start (ADR-0069); nothing in those tests asks for a push or ends a bond, so
 * every answer here is "nothing". Not a component: only a configuration that names it gets it.
 */
internal class NoNotifications : Notifications {
    override fun request(request: PushRequest): Boolean = false

    override fun cancel(
        dedupeKeys: Collection<String>,
        reason: CancelReason,
    ): Int = 0

    override fun cancelBond(bondId: UUID): Int = 0

    override fun hasLiveOrSent(
        userId: UUID,
        bondId: UUID,
        category: PushCategory,
        since: Instant,
    ): Boolean = false

    override fun revealClassSettled(
        userId: UUID,
        bondDays: Map<UUID, LocalDate>,
    ): Set<UUID> = emptySet()
}
