package com.moyi.support.infra.database

import com.moyi.support.api.ExportedTicket
import com.moyi.support.domain.SupportBody
import com.moyi.support.domain.SupportReference
import com.moyi.support.domain.SupportSubject
import com.moyi.support.domain.SupportTicket
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `support_tickets` (V45, V55): what a member wrote to support (FR-102, ADR-0057).
 *
 * Plain SQL on [JdbcTemplate], as `entry_reports` is (ADR-0053): a ticket is ids, a time and somebody's
 * words, and nothing here needs Hibernate. **The subject, body and reference (V55, ADR-0057 decision 11)
 * are read back only for the sender's own export** ([allOf], ADR-0059): no response, log line or
 * exception the application makes can carry them. The operator reads them through
 * `support_ticket_read`, which records that they did.
 */
@Component
internal class SupportTickets(
    private val jdbc: JdbcTemplate,
) {
    /** A new, open ticket, in the caller's transaction. */
    fun insert(
        ticket: SupportTicket,
        subject: SupportSubject,
        body: SupportBody,
        reference: SupportReference?,
    ) {
        jdbc.update(
            "INSERT INTO support_tickets (id, user_id, subject, body, reference, status, created_at) VALUES (?, ?, ?, ?, ?, 'OPEN', ?)",
            ticket.id,
            ticket.userId,
            subject.value,
            body.value,
            reference?.value,
            Timestamp.from(ticket.createdAt),
        )
    }

    /**
     * Every open ticket [userId] sent is closed and emptied (its words and its reference), with one `CLOSED` line each in the
     * log under [actor] (ADR-0057 decision 8, ADR-0058 decision 10): the sender's words go with
     * their account. The ticket stays, with its id, its time and the sender's id, which names an
     * anonymised account. A closed ticket already holds no words (V45). Returns how many it closed.
     */
    fun closeAllOf(
        userId: UUID,
        at: Instant,
        actor: String,
        note: String,
    ): Int {
        val closed =
            jdbc.queryForList(
                "UPDATE support_tickets SET status = 'CLOSED', subject = NULL, body = NULL, reference = NULL, closed_at = ? " +
                    "WHERE user_id = ? AND status = 'OPEN' RETURNING id",
                UUID::class.java,
                Timestamp.from(at),
                userId,
            )
        closed.forEach { ticketId ->
            jdbc.update(
                "INSERT INTO support_ticket_log (id, ticket_id, action, actor, note, at) VALUES (?, ?, 'CLOSED', ?, ?, ?)",
                UUID.randomUUID(),
                ticketId,
                actor,
                note,
                Timestamp.from(at),
            )
        }
        return closed.size
    }

    /** Every ticket [userId] sent, oldest first, with whatever words it still holds (ADR-0059). */
    fun allOf(userId: UUID): List<ExportedTicket> =
        jdbc.query(
            "SELECT id, subject, body, reference, status, created_at, closed_at FROM support_tickets " +
                "WHERE user_id = ? ORDER BY created_at, id",
            { rs, _ ->
                ExportedTicket(
                    id = rs.getObject("id", UUID::class.java),
                    subject = rs.getString("subject"),
                    body = rs.getString("body"),
                    reference = rs.getString("reference"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                )
            },
            userId,
        )

    /** The ticket [id] names, whoever sent it, or null: the caller checks it is theirs. Never its words. */
    fun find(id: UUID): SupportTicket? =
        jdbc.query("SELECT id, user_id, created_at FROM support_tickets WHERE id = ?", ROW, id).singleOrNull()

    private companion object {
        val ROW =
            RowMapper { rs, _ ->
                SupportTicket(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
    }
}
