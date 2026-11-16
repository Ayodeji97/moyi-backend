package com.moyi.support.api

import java.time.Instant
import java.util.UUID

/**
 * The support inbox, for the admin console (ADR-0067 decisions 12 and 13), over ADR-0057's three
 * operator functions, unchanged: they stay the one place the rules live, and each writes its own
 * `support_ticket_log` row under the [actor] it is given (`admin:<accountId>`). Admin-only:
 * `ArchitectureTest` holds that nothing but `admin` reaches it.
 *
 * [list], [read] and [close] join the caller's transaction and refuse to run outside one
 * (`MANDATORY`), so that the function's log row and the console's audit row commit together.
 */
interface SupportInbox {
    /** How many tickets are open. Writes nothing: a count names nobody (A2's counts). */
    fun openCount(): Int

    /** The open tickets, oldest first, with how long each message is; never the words. Logs `LISTED`. */
    fun list(actor: String): List<TicketRow>

    /** One ticket with its words, or null when there is none by that id (and then nothing is logged). Logs `READ`. */
    fun read(
        ticketId: UUID,
        actor: String,
    ): TicketCase?

    /** Closes an open ticket and empties its words and reference; logs `CLOSED` with [note]. */
    fun close(
        ticketId: UUID,
        actor: String,
        note: String,
    ): TicketCloseOutcome
}

/** One open ticket as the inbox lists it: who sent it, how long it is, when. No word of it. */
class TicketRow(
    val ticketId: UUID,
    val userId: UUID,
    val subjectLength: Int,
    val bodyLength: Int,
    val receivedAt: Instant,
) {
    override fun toString(): String = "TicketRow($ticketId)"
}

/**
 * One ticket as the person answering it reads it. [replyTo] is the sender's address as identity
 * holds it now (null: the account is gone), [replyToConfirmed] whether it was ever confirmed; a
 * closed ticket's [subject], [body] and [reference] are null. Its `toString` names the ticket and
 * nothing else: the words and the address are what must not reach a log.
 */
@Suppress("LongParameterList") // The ticket as the function returns it, column by column.
class TicketCase(
    val ticketId: UUID,
    val open: Boolean,
    val userId: UUID,
    val replyTo: String?,
    val replyToConfirmed: Boolean,
    val receivedAt: Instant,
    val subject: String?,
    val body: String?,
    val reference: String?,
) {
    override fun toString(): String = "TicketCase($ticketId, open=$open)"
}

enum class TicketCloseOutcome {
    CLOSED,

    /** Already closed: by another operator, `scripts/moyi support`, or an account deletion. Nothing changed. */
    NOT_OPEN,
}
