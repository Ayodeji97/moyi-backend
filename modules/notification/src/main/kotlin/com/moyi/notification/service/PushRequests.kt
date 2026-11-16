package com.moyi.notification.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.Nudge
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.infra.database.CancelledPush
import com.moyi.notification.infra.database.NewPush
import com.moyi.notification.infra.database.NotificationStore
import com.moyi.notification.infra.database.QuietWindow
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
 * A nudge this call records calls off what it supersedes that is still owed, in
 * the same transaction, and takes over the least of their budget claims
 * (ADR-0068 decision 7).
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
    private val meters: MeterRegistry,
) : Notifications {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun request(request: PushRequest): Boolean {
        val now = clock.instant()
        // Time-ordered: the id is internal (it travels in the outbox, never to a phone),
        // and the ledger is read by time.
        val id = ids.timeOrdered()
        val nudge = request.nudge
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
                    bondDate = request.bondDate,
                    nudge = nudge?.index,
                    leaveFreeAcrossBonds = nudge?.leaveFreeAcrossBonds ?: 0,
                ),
            )
        // Only a push this call recorded supersedes: a re-ask (the look-back asks up to six
        // times) calls off nothing, not even a push asked for after the first ask.
        if (recorded && nudge != null) supersede(id, nudge)
        if (recorded) {
            publisher.publish(OutboxEvent(AGGREGATE, id, PUSH_REQUESTED, mapOf(NOTIFICATION_ID to id), now))
        }
        return recorded
    }

    /**
     * Calls off what [nudge] supersedes that is still owed, and lowers the new row [id]'s
     * leave-free to the least of its own and of every push it cancelled (spec C8, C16, ADR-0068
     * decision 7). Superseding a hard nudge 1 held by quiet hours makes the newer one hard, so a
     * night's only reminder is not turned into a push the cap may drop; one statement cancels and
     * returns, so a chain carries the claim down. A push already sent is not returned, so it gives
     * nothing; a push the consumer is sending is waited for. Runs only after the insert recorded
     * the row, in the caller's transaction, before the row's event is published.
     */
    private fun supersede(
        id: UUID,
        nudge: Nudge,
    ) {
        if (nudge.supersedes.isEmpty()) return
        val cancelled = ledger.cancelOwed(nudge.supersedes)
        cancelled.forEach { count(it, SUPERSEDED) }
        val inherited = cancelled.minOfOrNull { it.leaveFreeAcrossBonds } ?: return
        if (inherited < nudge.leaveFreeAcrossBonds) ledger.lowerLeaveFree(id, inherited)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun cancel(dedupeKeys: Collection<String>): Int {
        val cancelled = ledger.cancelOwed(dedupeKeys)
        cancelled.forEach { count(it, WRITTEN) }
        return cancelled.size
    }

    /** `notification.push.cancelled{category, nudge, reason}`: what was called off, never whose. */
    private fun count(
        push: CancelledPush,
        reason: String,
    ) {
        meters.counter(CANCELLED, "category", push.category.name, "nudge", push.nudge?.toString() ?: NO_NUDGE, "reason", reason).increment()
    }

    @Transactional(readOnly = true)
    override fun hasLiveOrSent(
        userId: UUID,
        bondId: UUID,
        category: PushCategory,
        since: Instant,
    ): Boolean = ledger.existsSince(userId, bondId, category, LIVE_OR_SENT, since)

    @Transactional(readOnly = true)
    override fun revealClassSettled(
        userId: UUID,
        bondDays: Map<UUID, LocalDate>,
    ): Set<UUID> {
        if (bondDays.isEmpty()) return emptySet()
        return ledger
            .datedSince(userId, bondDays.keys, REVEAL_CLASS, SETTLED, clock.instant().minus(REVEAL_LOOK_BACK))
            .filter { (bondId, date) -> bondDays[bondId] == date }
            .mapTo(mutableSetOf()) { it.bondId }
    }

    /**
     * [LIVE_OR_SENT] and [SETTLED] together cover every status but `CANCELLED`, which is in
     * neither: a push called off reached nobody and never will. They are not quite a partition:
     * `SENT` is in both, as it reached its member and nothing more will happen to it.
     */
    internal companion object {
        /** What a push that reached, or may yet reach, its member is: sent, or still owed. */
        val LIVE_OR_SENT = setOf(NotificationStatus.SENT, NotificationStatus.REQUESTED, NotificationStatus.HELD)

        /** What a push that is done with is, other than called off: sent, or gone nowhere for good. */
        val SETTLED =
            setOf(
                NotificationStatus.SENT,
                NotificationStatus.SUPPRESSED,
                NotificationStatus.DROPPED_BUDGET,
                NotificationStatus.EXPIRED,
                NotificationStatus.FAILED_PERMANENT,
            )

        private val REVEAL_CLASS = PushCategory.entries.filter { it.isRevealClass }.toSet()

        /** How far back a current Bond-day's reveal can have been asked for (spec C17). */
        private val REVEAL_LOOK_BACK = Duration.ofHours(72)

        const val CANCELLED = "notification.push.cancelled"
        const val NO_NUDGE = "none"

        /** Called off by `cancel`: what its writer no longer owes. */
        const val WRITTEN = "written"

        /** Called off by a newer nudge of the same Bond-day. */
        const val SUPERSEDED = "superseded"

        const val AGGREGATE = "Notification"
        const val PUSH_REQUESTED = "PushRequested"
        const val NOTIFICATION_ID = "notificationId"
    }
}
