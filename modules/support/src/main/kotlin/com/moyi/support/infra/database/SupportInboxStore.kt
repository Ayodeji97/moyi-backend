package com.moyi.support.infra.database

import com.moyi.support.api.SupportInbox
import com.moyi.support.api.TicketCase
import com.moyi.support.api.TicketCloseOutcome
import com.moyi.support.api.TicketRow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [SupportInbox] over V45/V55's functions, called as they are by `scripts/moyi support`. `@Component`,
 * not `@Repository`: exception translation would wrap this class's own exceptions (ADR-0063 decision 3).
 */
@Component
internal class SupportInboxStore(
    jdbcTemplate: JdbcTemplate,
) : SupportInbox {
    private val jdbc = JdbcClient.create(jdbcTemplate)

    override fun openCount(): Int =
        jdbc.sql("SELECT count(*) FROM support_tickets WHERE status = 'OPEN'").query(Int::class.javaObjectType).single()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun list(actor: String): List<TicketRow> =
        jdbc
            .sql("SELECT ticket_id, user_id, subject_length, body_length, received_at FROM support_ticket_list(:actor)")
            .param("actor", actor)
            .query { rs, _ ->
                TicketRow(
                    rs.getObject("ticket_id", UUID::class.java),
                    rs.getObject("user_id", UUID::class.java),
                    rs.getInt("subject_length"),
                    rs.getInt("body_length"),
                    rs.getTimestamp("received_at").toInstant(),
                )
            }.list()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun read(
        ticketId: UUID,
        actor: String,
    ): TicketCase? =
        jdbc
            .sql(
                "SELECT ticket_id, status, user_id, reply_to, reply_to_confirmed, received_at, subject, body, reference " +
                    "FROM support_ticket_read(:ticket, :actor)",
            ).param("ticket", ticketId)
            .param("actor", actor)
            .query { rs, _ ->
                TicketCase(
                    ticketId = rs.getObject("ticket_id", UUID::class.java),
                    open = rs.getString("status") == "OPEN",
                    userId = rs.getObject("user_id", UUID::class.java),
                    replyTo = rs.getString("reply_to"),
                    replyToConfirmed = rs.getBoolean("reply_to_confirmed"),
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                    subject = rs.getString("subject"),
                    body = rs.getString("body"),
                    reference = rs.getString("reference"),
                )
            }.optional()
            .orElse(null)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun close(
        ticketId: UUID,
        actor: String,
        note: String,
    ): TicketCloseOutcome {
        val answer =
            jdbc
                .sql("SELECT support_ticket_close(:ticket, :actor, :note)")
                .param("ticket", ticketId)
                .param("actor", actor)
                .param("note", note)
                .query(String::class.java)
                .single()
        return TicketCloseOutcome.entries.firstOrNull { it.name == answer }
            ?: error("support_ticket_close answered something this code does not know: $answer")
    }
}
