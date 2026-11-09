package com.moyi.support.infra.database

import com.moyi.support.domain.SupportBody
import com.moyi.support.domain.SupportSubject
import com.moyi.support.domain.SupportTicket
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `support_tickets` (V45): what a member wrote to support (FR-102, ADR-0057).
 *
 * Plain SQL on [JdbcTemplate], as `entry_reports` is (ADR-0053): a ticket is ids, a time and somebody's
 * words, and nothing here needs Hibernate. **The subject and body are written and never read here**:
 * the application has no path that reads them back, so no response, log line or exception it makes can
 * carry them. The operator reads them through `support_ticket_read`, which records that they did.
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
    ) {
        jdbc.update(
            "INSERT INTO support_tickets (id, user_id, subject, body, status, created_at) VALUES (?, ?, ?, ?, 'OPEN', ?)",
            ticket.id,
            ticket.userId,
            subject.value,
            body.value,
            Timestamp.from(ticket.createdAt),
        )
    }

    /**
     * Every open ticket [userId] sent is closed and emptied, with one `CLOSED` line each in the
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
                "UPDATE support_tickets SET status = 'CLOSED', subject = NULL, body = NULL, closed_at = ? " +
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
