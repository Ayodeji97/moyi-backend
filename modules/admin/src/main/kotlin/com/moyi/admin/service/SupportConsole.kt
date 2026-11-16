package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.common.security.ClientAddress
import com.moyi.support.api.SupportInbox
import com.moyi.support.api.TicketCase
import com.moyi.support.api.TicketCloseOutcome
import com.moyi.support.api.TicketRow
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The support inbox (ADR-0057, ADR-0067 decisions 12 and 13), for `SUPPORT` and above. Each look and
 * act is the function's own `support_ticket_log` row and one `audit_log` row, in one transaction.
 *
 * Closing empties the ticket's words, so it is irreversible and, as A2's irreversible acts are,
 * behind the step-up window: refused with [StepUpRequired] before anything is read or written, then
 * the note (`ModerationNote`), then the function. Never in a log line, a metric tag or metadata: the
 * note, a ticket's words, its reference, an address.
 */
@Service
@ConditionalOnAdminConsole
internal class SupportConsole(
    private val inbox: SupportInbox,
    private val audit: AuditTrail,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The home page's count. No log row and no audit row: a count names nobody. */
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional(readOnly = true)
    fun openCount(): Int = inbox.openCount()

    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    fun inbox(
        adminId: UUID,
        from: ClientAddress?,
    ): List<TicketRow> {
        val rows = inbox.list(ModerationActor.of(adminId))
        audit.record(AuditAction.SUPPORT_INBOX_VIEWED, AuditOutcome.SUCCEEDED, adminId, null, null, clientAddress = from)
        count("list", "SUCCEEDED")
        return rows
    }

    /** One ticket, its `READ` logged by the function; null for an unknown id, which writes no `READ`. */
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    fun ticket(
        ticketId: UUID,
        adminId: UUID,
        from: ClientAddress?,
    ): TicketCase? {
        val case = inbox.read(ticketId, ModerationActor.of(adminId))
        audit.record(
            action = AuditAction.SUPPORT_TICKET_READ,
            outcome = if (case != null) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = TICKET,
            targetId = ticketId.toString(),
            metadata = if (case != null) emptyMap() else mapOf(RESULT to NOT_FOUND),
            clientAddress = from,
        )
        count("read", if (case != null) "SUCCEEDED" else NOT_FOUND)
        return case
    }

    @Suppress("LongParameterList") // The ticket and note, the proof's time, and who asked from where.
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    fun close(
        ticketId: UUID,
        note: String,
        secondFactorAt: Instant?,
        adminId: UUID,
        from: ClientAddress?,
    ): TicketCloseOutcome {
        if (!StepUp.isFresh(secondFactorAt, clock.instant())) throw StepUpRequired()
        val checked = ModerationNote.require(note)
        val outcome = inbox.close(ticketId, ModerationActor.of(adminId), checked.text)
        audit.record(
            action = AuditAction.SUPPORT_TICKET_CLOSED,
            outcome = if (outcome == TicketCloseOutcome.CLOSED) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = TICKET,
            targetId = ticketId.toString(),
            justification = checked.text,
            metadata = mapOf(RESULT to outcome.name),
            clientAddress = from,
        )
        count("close", outcome.name)
        log.info("support ticket closed admin={} ticket={} result={}", adminId, ticketId, outcome)
        return outcome
    }

    private fun count(
        action: String,
        result: String,
    ) {
        meters.counter(METRIC, "action", action, RESULT, result).increment()
    }

    private companion object {
        const val TICKET = "SUPPORT_TICKET"
        const val RESULT = "result"
        const val NOT_FOUND = "NOT_FOUND"
        const val METRIC = "moyi.admin.support"
    }
}
