package com.moyi.notification.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.domain.PushSettlement
import com.moyi.notification.domain.SendResult
import com.moyi.notification.domain.Settlement
import com.moyi.notification.infra.database.NotificationStore
import com.moyi.notification.infra.database.PushTokenStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

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
 * 3. **The N2 seam.** The member's category toggle (→ `SUPPRESSED`), quiet
 *    hours (→ held until `not_before`) and FR-082's budget (→ `DROPPED_BUDGET`,
 *    or the reserved slot) are decided here, once N2 builds them. Until then
 *    every push is treated as wanted, in hours and within budget: nothing
 *    requests one before N3, and N3 lands after N2.
 * 4. Every phone of the member is sent it. A token the provider calls invalid
 *    is forgotten (FR-083), unless its device has registered a new one since.
 * 5. [PushSettlement] adds the answers up: `SENT` with the provider's id;
 *    retried (thrown, so the outbox backs off, FR-086); or `FAILED_PERMANENT`
 *    with a word of ours that says why.
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

        if (PushSettlement.isExpired(push.staleAfter, clock.instant())) {
            ledger.settle(push.id, NotificationStatus.EXPIRED, clock.instant())
            return
        }

        // N2 decides here: the category toggle, quiet hours and the budget. See the class's note.

        val answers =
            tokens.heldBy(push.userId).map { held ->
                val answer = sender.send(PushMessage(held.token, push.category, push.bondId))
                if (answer is PushDelivery.TokenInvalid) tokens.deleteIfStillHeld(held)
                answer
            }
        when (val settlement = PushSettlement.settle(answers.map(::resultOf))) {
            Settlement.Sent -> {
                val accepted = answers.filterIsInstance<PushDelivery.Accepted>().first()
                ledger.settle(push.id, NotificationStatus.SENT, clock.instant(), providerMessageId = accepted.providerMessageId)
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
