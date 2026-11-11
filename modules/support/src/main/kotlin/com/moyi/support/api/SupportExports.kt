package com.moyi.support.api

import java.time.Instant
import java.util.UUID

/**
 * The support messages a person sent, for their own data export (FR-009, ADR-0059): their words,
 * as they wrote them, while the ticket still holds them. A ticket an operator closed, or one closed
 * by an account deletion, has been emptied (ADR-0057 decision 8) and is carried with no words.
 * The operator's log of a ticket is not the person's and is not carried.
 */
interface SupportExports {
    /** Every ticket [userId] sent, oldest first. */
    fun ticketsOf(userId: UUID): List<ExportedTicket>
}

/** One ticket. [toString] never prints the subject, body or reference. */
@Suppress("LongParameterList") // One field per column the person is owed.
class ExportedTicket(
    val id: UUID,
    val subject: String?,
    val body: String?,
    /** The error reference the person sent with it (ADR-0057 decision 11), while the ticket keeps it; emptied with the words. */
    val reference: String?,
    /** `OPEN` or `CLOSED`. */
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
) {
    override fun toString(): String = "ExportedTicket(id=$id, status=$status)"
}
