package com.moyi.notification.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushRequest
import com.moyi.notification.infra.database.NewPush
import com.moyi.notification.infra.database.NotificationStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * [Notifications] over the ledger and the outbox (spec §2.2, ADR-0044).
 *
 * The row and the `PushRequested` event are written in the caller's
 * transaction, so a push is owed exactly when the fact that earned it
 * committed. The event is published only when the row is new: a second
 * request with the same dedupe key writes nothing and publishes nothing, so a
 * consumer that is handed the same event twice (ADR-0035 decision 6) asks
 * twice and causes one push.
 *
 * **Every request is published, even one already past `staleAfter`**, and the
 * push consumer records it `EXPIRED`. One path to every final status, and the
 * ledger shows what was asked for and why it went nowhere.
 */
@Service
internal class PushRequests(
    private val ledger: NotificationStore,
    private val publisher: EventPublisher,
    private val ids: IdGenerator,
    private val clock: Clock,
) : Notifications {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun request(request: PushRequest): Boolean {
        val now = clock.instant()
        // Time-ordered: the id is internal (it travels in the outbox, never to a phone),
        // and the ledger is read by time.
        val id = ids.timeOrdered()
        val recorded =
            ledger.insertOnce(
                NewPush(
                    id = id,
                    userId = request.userId,
                    bondId = request.bondId,
                    category = request.category,
                    dedupeKey = request.dedupeKey,
                    // Quiet hours will move this (N2); until then a push may go at once.
                    notBefore = now,
                    staleAfter = request.staleAfter,
                    createdAt = now,
                ),
            )
        if (recorded) {
            publisher.publish(OutboxEvent(AGGREGATE, id, PUSH_REQUESTED, mapOf(NOTIFICATION_ID to id), now))
        }
        return recorded
    }

    internal companion object {
        const val AGGREGATE = "Notification"
        const val PUSH_REQUESTED = "PushRequested"
        const val NOTIFICATION_ID = "notificationId"
    }
}
