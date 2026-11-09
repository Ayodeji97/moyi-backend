package com.moyi.support.domain

import java.time.Instant
import java.util.UUID

/**
 * A support message as its sender is told of it (FR-102, ADR-0057): its id and when it arrived.
 * Nothing of what it says (that is the operator's, through `support_ticket_read`) and nothing of
 * what became of it.
 */
internal data class SupportTicket(
    val id: UUID,
    val userId: UUID,
    val createdAt: Instant,
)

/**
 * Published in the filing transaction and heard after it commits: the acknowledgement email goes
 * then, and only then (ADR-0057 decision 5). Ids only.
 */
internal data class SupportTicketFiled(
    val ticketId: UUID,
    val userId: UUID,
)
