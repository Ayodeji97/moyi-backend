package com.moyi.notification.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.notification.api.HeldPushes
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import com.moyi.notification.domain.BudgetDecision
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.domain.PushBudget
import com.moyi.notification.domain.PushSettlement
import com.moyi.notification.domain.QuietHours
import com.moyi.notification.domain.SendResult
import com.moyi.notification.domain.Settlement
import com.moyi.notification.infra.database.NotificationStore
import com.moyi.notification.infra.database.PushTokenStore
import com.moyi.notification.infra.database.StoredPush
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * The `notification.push` consumer: sends what [PushRequests] recorded
 * (spec §2.2, ADR-0044).
 *
 * **In the delivery's transaction, with the provider called inside it.** The
 * call holds a pooled connection and the delivery's row lock for one HTTP
 * round trip per phone, bounded by the client's timeouts (2 s to connect, 5 s
 * to read). Chosen, at this scale, over a second queue: the outbox already is
 * one, with backoff, a dead-letter gauge and a time limit (ADR-0035).
 *
 * **At least once, not exactly once.** If the commit fails after FCM took the
 * message, the delivery is retried and the phone is sent it again. The unique
 * dedupe key makes a second *request* impossible, not a second *send*, and
 * doc 07:272's "structurally impossible" is too strong (ADR-0044).
 *
 * **What it does, in order:**
 * 1. A push no longer in the ledger, or no longer `REQUESTED`, is nothing to
 *    do: it is the same event delivered twice, or a row the retention removed.
 * 2. Past `stale_after` → `EXPIRED`, never sent: the outbox retries for ever,
 *    and the push carries its own deadline instead.
 * 3. The member's own settings, as they stood when the push was asked for
 *    (ADR-0045). Each holds back this member's copy and no one else's (H-M5
 *    as Q9 reads it):
 *    - the category turned off → `SUPPRESSED`;
 *    - inside quiet hours, or before `not_before` → `HELD` until the hours
 *      end, and the delivery is let go. [HeldPushes] hands it back then, and
 *      it comes through here again: still owed, it is sent; stale by then,
 *      it is `EXPIRED` at step 2. A reveal is held, never dropped (Q7);
 *    - FR-082's budget, decided under the member's budget lock, which the
 *      transaction holds until the push is settled → `DROPPED_BUDGET`, or
 *      sent in the reserved slot or the remainder.
 * 4. Every phone of the member is sent it. A token the provider calls invalid
 *    is forgotten (FR-083), unless its device has registered a new one since.
 * 5. [PushSettlement] adds the answers up: `SENT` with the provider's id,
 *    marked `reserved` if it took the reveal-class slot; retried (thrown, so
 *    the outbox backs off, FR-086, and the slot is given back with the
 *    rollback); or `FAILED_PERMANENT` with a word of ours that says why.
 *
 * Starts from `NOW` (ADR-0035 Owed, Phase 4): a push requested before this
 * consumer existed cannot exist, and a backfill would scan the outbox by type.
 */
@Component
internal class SendPush(
    private val ledger: NotificationStore,
    private val tokens: PushTokenStore,
    private val sender: PushSender,
    private val clock: Clock,
) : EventConsumer {
    /** The key the delivery state is kept under: never to be changed. */
    override val id = "notification.push"
    override val eventTypes = setOf(PushRequests.PUSH_REQUESTED)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val notificationId =
            checkNotNull(event.references[PushRequests.NOTIFICATION_ID]) { "A PushRequested event carries no notificationId." }
        val push = ledger.lock(notificationId)?.takeIf { it.status == NotificationStatus.REQUESTED } ?: return
        val now = clock.instant()
        when (val decision = decide(push, now)) {
            is Decision.Settle -> ledger.settle(push.id, decision.status, now)
            is Decision.Hold -> ledger.hold(push.id, decision.until)
            is Decision.Send -> send(push, decision.slot)
        }
    }

    /**
     * Steps 2 and 3 of the class's note, in that order: the deadline first, so
     * that a push held past it is expired when it comes back, then the
     * member's own settings. The budget last, because it is the one step that
     * takes a lock and spends something.
     */
    private fun decide(
        push: StoredPush,
        now: Instant,
    ): Decision {
        val goesAt by lazy { notBefore(push, now) }
        return when {
            PushSettlement.isExpired(push.staleAfter, now) -> {
                Decision.Settle(NotificationStatus.EXPIRED)
            }

            !push.wanted -> {
                Decision.Settle(NotificationStatus.SUPPRESSED)
            }

            goesAt.isAfter(now) -> {
                Decision.Hold(goesAt)
            }

            else -> {
                when (val slot = budgetSlot(push, now)) {
                    BudgetDecision.DROPPED -> Decision.Settle(NotificationStatus.DROPPED_BUDGET)
                    else -> Decision.Send(slot)
                }
            }
        }
    }

    private fun send(
        push: StoredPush,
        slot: BudgetDecision,
    ) {
        val answers =
            tokens.heldBy(push.userId).map { held ->
                val answer = sender.send(PushMessage(held.token, push.category, push.bondId))
                if (answer is PushDelivery.TokenInvalid) tokens.deleteIfStillHeld(held)
                answer
            }
        when (val settlement = PushSettlement.settle(answers.map(::resultOf))) {
            Settlement.Sent -> {
                val accepted = answers.filterIsInstance<PushDelivery.Accepted>().first()
                ledger.settle(
                    push.id,
                    NotificationStatus.SENT,
                    clock.instant(),
                    providerMessageId = accepted.providerMessageId,
                    reserved = slot == BudgetDecision.RESERVED,
                )
            }

            // Rolls back this delivery, the forgotten tokens with it: the retry will find and forget them again.
            Settlement.Retry -> {
                throw PushProviderUnavailableException()
            }

            is Settlement.Failed -> {
                ledger.settle(push.id, NotificationStatus.FAILED_PERMANENT, clock.instant(), errorClass = settlement.errorClass)
            }
        }
    }

    /**
     * When this push may go: its own `not_before`, or the end of the quiet
     * hours [now] falls in, whichever is later. Asked at the moment of
     * sending, not of asking, so a push retried into the night is held too.
     */
    private fun notBefore(
        push: StoredPush,
        now: Instant,
    ): Instant {
        val quiet = push.quietHours ?: return maxOf(push.notBefore, now)
        return maxOf(push.notBefore, QuietHours.holdUntil(now, push.zone, quiet.start, quiet.end))
    }

    /**
     * FR-082, under the member's budget lock: held from here until this
     * delivery commits or rolls back, so a second push for the same member
     * counts this one's slot as spent, or sees it given back.
     */
    private fun budgetSlot(
        push: StoredPush,
        now: Instant,
    ): BudgetDecision {
        ledger.lockBudgetOf(push.userId)
        val day = PushBudget.dayOf(now, push.zone)
        val inBond = ledger.sentInBond(push.userId, push.bondId, day.start, day.end)
        return PushBudget.decide(
            sentInBondToday = inBond.sent,
            reservedSpentToday = inBond.reservedSpent,
            sentAcrossBonds = ledger.sentSince(push.userId, now.minus(PushBudget.ACROSS_BONDS_WINDOW)),
            revealClass = push.category.isRevealClass,
        )
    }

    /** What the consumer does with a push before any phone is tried. */
    private sealed interface Decision {
        /** Into a final status, unsent: expired, suppressed or over budget. */
        data class Settle(
            val status: NotificationStatus,
        ) : Decision

        /** `HELD` until [until], when quiet hours end. */
        data class Hold(
            val until: Instant,
        ) : Decision

        /** To every phone, in the slot the budget gave it. */
        data class Send(
            val slot: BudgetDecision,
        ) : Decision
    }

    private fun resultOf(answer: PushDelivery): SendResult =
        when (answer) {
            is PushDelivery.Accepted -> SendResult.ACCEPTED
            is PushDelivery.TokenInvalid -> SendResult.TOKEN_INVALID
            is PushDelivery.Rejected -> SendResult.REJECTED
            is PushDelivery.Unavailable -> SendResult.UNAVAILABLE
        }
}

/**
 * No phone took the push and at least one may yet. Thrown so that the outbox
 * rolls the delivery back and tries it again after its backoff; recorded by
 * its class name, which is all the dispatcher keeps of any exception.
 */
internal class PushProviderUnavailableException : RuntimeException("The push provider could not take the push now; it will be retried.")
