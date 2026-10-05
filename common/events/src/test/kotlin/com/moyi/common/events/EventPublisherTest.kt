package com.moyi.common.events

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Real-Postgres proof that publication participates in, and cannot outlive, the writer's transaction. */
@SpringBootTest(classes = [EventsTestApplication::class])
internal class EventPublisherTest(
    @Autowired private val publisher: EventPublisher,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired manager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    private val transactions = TransactionTemplate(manager)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
    }

    @Test
    fun `publication needs a transaction and a rolled back state change leaves no event`() {
        val event = event()
        shouldThrow<IllegalTransactionStateException> { publisher.publish(event) }
        transactions.executeWithoutResult {
            publisher.publish(event)
            it.setRollbackOnly()
        }
        jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java) shouldBe 0
    }

    @Test
    fun `committed history keeps identity and microseconds without inventing deliveries`() {
        val event = event()
        transactions.executeWithoutResult { publisher.publish(event) }
        val row = jdbc.queryForMap("SELECT * FROM outbox_events")
        row["aggregate_type"] shouldBe event.aggregateType
        row["aggregate_id"] shouldBe event.aggregateId
        row["event_type"] shouldBe event.eventType
        jdbc.queryForObject("SELECT occurred_at FROM outbox_events", java.sql.Timestamp::class.java)!!.toInstant() shouldBe
            Instant.parse("2026-09-15T10:00:00.123456Z")
        jdbc.queryForObject("SELECT payload ->> 'bondId' FROM outbox_events", String::class.java) shouldBe
            event.references["bondId"].toString()
        jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries", Int::class.java) shouldBe 0
    }

    private fun event() =
        OutboxEvent(
            "BondDay",
            UUID.randomUUID(),
            "DayRevealed",
            mapOf("bondId" to UUID.randomUUID()),
            Instant.parse("2026-09-15T10:00:00.123456789Z"),
        )
}

@SpringBootApplication(scanBasePackages = ["com.moyi.common.events", "com.moyi.common.core"])
internal class EventsTestApplication {
    @Bean
    fun mapper(): ObjectMapper = JsonMapper.builder().build()
}
