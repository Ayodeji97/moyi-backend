package com.moyi.support.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.support.infra.RecordingAccountMail
import com.moyi.support.infra.SupportTestApplication
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0057 decision 8, owed until now (ADR-0058 decision 10): a deleted account's tickets keep none of
 * its words, nor the reference sent with them (decision 11).
 */
@SpringBootTest(classes = [SupportTestApplication::class])
@AutoConfigureMockMvc
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class EmptyDeletedAccountTicketsTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val clock: Clock,
    @Autowired private val mail: RecordingAccountMail,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        // The acknowledgements the set-up's messages were sent: the context is shared with SupportMessagesTest, which counts them.
        await().atMost(Duration.ofSeconds(5)).until { mail.sent.size >= sent }
        mail.sent.clear()
        jdbc.execute("TRUNCATE TABLE support_ticket_log, support_tickets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `every open ticket of the person is closed and emptied, logged as the deletion's, and nobody else's is touched`() {
        val ada = UUID.randomUUID()
        val bea = UUID.randomUUID()
        repeat(2) { send(ada, "ada-trace-$it") }
        send(bea, "bea-trace")

        deleteAccount(ada)
        deleteAccount(ada)

        jdbc
            .queryForList(
                "SELECT status, subject, body, reference, closed_at IS NOT NULL AS closed FROM support_tickets WHERE user_id = ?",
                ada,
            ).forEach { it shouldBe mapOf("status" to "CLOSED", "subject" to null, "body" to null, "reference" to null, "closed" to true) }
        jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE user_id = ?", Int::class.java, ada) shouldBe 2
        jdbc.queryForMap("SELECT status, reference FROM support_tickets WHERE user_id = ?", bea) shouldBe
            mapOf("status" to "OPEN", "reference" to "bea-trace")
        // One line per ticket, though the event came twice.
        jdbc.queryForObject(
            "SELECT count(*) FROM support_ticket_log WHERE action = 'CLOSED' AND actor = 'account-deletion'",
            Int::class.java,
        ) shouldBe
            2
    }

    private var sent = 0

    private fun send(
        user: UUID,
        reference: String,
    ) {
        sent++
        mvc
            .post("/api/v1/support/messages") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"subject":"Locked out","body":"Words for a person to read.","reference":"$reference"}"""
            }.andReturn()
            .response.status shouldBe 201
    }

    private fun deleteAccount(userId: UUID) {
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("User", userId, "AccountDeleted", mapOf("userId" to userId), clock.instant()))
        }
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 10).failed shouldBe 0
    }
}
