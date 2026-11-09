package com.moyi.support.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.support.domain.SupportBody
import com.moyi.support.domain.SupportSubject
import com.moyi.support.domain.SupportTicket
import com.moyi.support.domain.SupportTicketFiled
import com.moyi.support.infra.database.SupportTickets
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What `POST /support/messages` answers: the ticket as its sender is told of it, and whether this was a replay. */
internal data class FiledTicket(
    val ticket: SupportTicket,
    val replayed: Boolean,
)

/**
 * `POST /support/messages` (FR-102, ADR-0057): a signed-in member writes to support. One transaction
 * holds the key (when one was sent), the ticket and the event that, once it commits, sends the
 * acknowledgement ([AcknowledgeSupportTicket]). A ticket never commits without its event, nor an
 * email go out for a ticket that rolled back.
 *
 * **A replay re-reads; it never files a second ticket or sends a second email**, and answers the
 * ticket the key names, if the caller sent it.
 *
 * Nothing of the request is logged here: not the subject, not the body.
 */
@Service
@Suppress("LongParameterList") // A service's collaborators, each injected by Spring.
internal class FileSupportTicket(
    private val tickets: SupportTickets,
    private val execution: IdempotentExecution,
    private val transactions: TransactionTemplate,
    private val events: ApplicationEventPublisher,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /**
     * @param idempotency the request's `Idempotency-Key`, as `IdempotencyInterceptor` prepared it, or null when none was sent
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyInFlightException the key's first request has not finished
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyReusedException the key was first used for a different request
     */
    fun file(
        userId: UUID,
        subject: SupportSubject,
        body: SupportBody,
        idempotency: IdempotentRequest?,
    ): FiledTicket {
        require(idempotency == null || idempotency.userId == userId) {
            "the Idempotency-Key was prepared for a different caller than the one writing"
        }
        // Truncated once, to what `timestamptz` keeps (CLAUDE.md): the replay's createdAt is read back.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val filed =
            transactions.execute {
                if (idempotency == null) {
                    FiledTicket(create(userId, subject, body, now), replayed = false)
                } else {
                    val outcome =
                        execution.once(idempotency) {
                            val ticket = create(userId, subject, body, now)
                            IdempotentResult(ticket, ticket.id, ResultKind.SUPPORT_TICKET, HttpStatus.CREATED.value())
                        }
                    FiledTicket(outcome.value ?: replay(userId, outcome.resultId), outcome.wasReplayed)
                }
            }
        return checkNotNull(filed) { "file's transaction produces a FiledTicket unless it threw" }
    }

    private fun create(
        userId: UUID,
        subject: SupportSubject,
        body: SupportBody,
        now: Instant,
    ): SupportTicket {
        val ticket = SupportTicket(ids.timeOrdered(), userId, now)
        tickets.insert(ticket, subject, body)
        events.publishEvent(SupportTicketFiled(ticket.id, userId))
        return ticket
    }

    /** The ticket a key already filed, if the caller sent it; anything else is a `404` a key this class wrote cannot reach. */
    private fun replay(
        userId: UUID,
        ticketId: UUID,
    ): SupportTicket = tickets.find(ticketId)?.takeIf { it.userId == userId } ?: throw NotFoundException("No such support message")
}
