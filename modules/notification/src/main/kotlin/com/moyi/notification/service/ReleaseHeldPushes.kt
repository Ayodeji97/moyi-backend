package com.moyi.notification.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.notification.api.HeldPushes
import com.moyi.notification.infra.database.NotificationStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [HeldPushes] over the ledger and the outbox (ADR-0045).
 *
 * A released push is published again as `PushRequested`, the event N1's
 * consumer already handles, so a held push and a fresh one take the same road
 * to a phone and every decision on it is made in one place, [SendPush]. The
 * status change and the events are one transaction: a release that fails
 * leaves the pushes held, to be released by the next run.
 */
@Service
internal class ReleaseHeldPushes(
    private val ledger: NotificationStore,
    private val publisher: EventPublisher,
) : HeldPushes {
    @Transactional
    override fun releaseDue(
        now: Instant,
        limit: Int,
    ): Int {
        require(limit > 0) { "A release needs a limit of at least one push." }
        val released = ledger.releaseDue(now, limit)
        released.forEach { id ->
            publisher.publish(
                OutboxEvent(PushRequests.AGGREGATE, id, PushRequests.PUSH_REQUESTED, mapOf(PushRequests.NOTIFICATION_ID to id), now),
            )
        }
        return released.size
    }
}
