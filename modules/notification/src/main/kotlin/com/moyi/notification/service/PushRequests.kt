package com.moyi.notification.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.infra.database.NewPush
import com.moyi.notification.infra.database.NotificationStore
import com.moyi.notification.infra.database.QuietWindow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

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
                    // May go at once; quiet hours are the consumer's to judge, when it comes to send.
                    notBefore = now,
                    staleAfter = request.staleAfter,
                    createdAt = now,
                    zone = request.recipient.zone,
                    quietHours = request.recipient.quietHours?.let { QuietWindow(it.start, it.end) },
                    // Kept as the answer, not the toggles: the toggle that governs a category is
                    // NotificationPreferences' rule, asked once, here.
                    wanted = request.recipient.preferences.allows(request.category),
                ),
            )
        if (recorded) {
            publisher.publish(OutboxEvent(AGGREGATE, id, PUSH_REQUESTED, mapOf(NOTIFICATION_ID to id), now))
        }
        return recorded
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun cancel(dedupeKey: String): Boolean = ledger.cancel(dedupeKey)

    @Transactional(readOnly = true)
    override fun hasLiveOrSent(
        userId: UUID,
        bondId: UUID,
        category: PushCategory,
        since: Instant,
    ): Boolean = ledger.existsSince(userId, bondId, category, LIVE_OR_SENT, since)

    internal companion object {
        /** What a push that reached, or may yet reach, its member is: sent, or still owed. */
        val LIVE_OR_SENT = setOf(NotificationStatus.SENT, NotificationStatus.REQUESTED, NotificationStatus.HELD)

        const val AGGREGATE = "Notification"
        const val PUSH_REQUESTED = "PushRequested"
        const val NOTIFICATION_ID = "notificationId"
    }
}
