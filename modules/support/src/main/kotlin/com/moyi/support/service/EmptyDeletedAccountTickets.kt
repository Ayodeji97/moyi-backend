package com.moyi.support.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.identity.api.AccountDeletions
import com.moyi.support.infra.database.SupportTickets
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * `support`'s share of an account deletion (ADR-0057 decision 8, ADR-0058 decision 10): every
 * ticket the person sent that is still open is closed and emptied, as an operator's close
 * empties it, and the log says the account's deletion did it. `user_id` is kept: it names the
 * anonymised account and nobody else. Delivered twice, the second finds nothing open.
 */
@Component
internal class EmptyDeletedAccountTickets(
    private val tickets: SupportTickets,
    private val clock: Clock,
) : EventConsumer {
    /** Never to be changed: delivery state is kept under it. */
    override val id = "support.account-deletion"
    override val eventTypes = setOf(AccountDeletions.EVENT)
    override val startFrom = StartFrom.BEGINNING

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val userId = checkNotNull(event.references[AccountDeletions.USER_ID]) { "An ${AccountDeletions.EVENT} event carries no user." }
        tickets.closeAllOf(userId, clock.instant().truncatedTo(ChronoUnit.MICROS), ACTOR, NOTE)
    }

    private companion object {
        /** Who closed it, in `support_ticket_log.actor`: no operator, the deletion. */
        const val ACTOR = "account-deletion"
        const val NOTE = "Closed and emptied: the sender's account was deleted at their request (FR-008)."
    }
}
