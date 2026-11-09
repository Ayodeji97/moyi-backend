package com.moyi.support.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.support.api.SupportExports
import com.moyi.support.infra.SupportTestApplication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

/** support's share of a data export (ADR-0059): the person's own tickets, an emptied one without words, nobody else's. */
@SpringBootTest(classes = [SupportTestApplication::class])
internal class SupportExportsTest(
    @Autowired private val exports: SupportExports,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE support_ticket_log, support_tickets")
    }

    @Test
    fun `the person's tickets, oldest first, an emptied one without its words, and nobody else's`() {
        val ada = UUID.randomUUID()
        ticket(ada, "First", "Ada's first words", "2026-11-01T09:00:00Z", open = false)
        ticket(ada, "Second", "Ada's second words", "2026-11-02T09:00:00Z", open = true)
        ticket(UUID.randomUUID(), "Bea's", "Bea's words", "2026-11-01T08:00:00Z", open = true)

        val tickets = exports.ticketsOf(ada)

        tickets.map { it.subject to it.status } shouldBe listOf(null to "CLOSED", "Second" to "OPEN")
        tickets.last().body shouldBe "Ada's second words"
        tickets.toString() shouldNotContain "words"
        exports.ticketsOf(UUID.randomUUID()).shouldBeEmpty()
    }

    private fun ticket(
        user: UUID,
        subject: String,
        body: String,
        at: String,
        open: Boolean,
    ) {
        jdbc.update(
            "INSERT INTO support_tickets (id, user_id, subject, body, status, created_at, closed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
            UUID.randomUUID(),
            user,
            subject.takeIf { open },
            body.takeIf { open },
            if (open) "OPEN" else "CLOSED",
            at,
            if (open) null else at,
        )
    }
}
