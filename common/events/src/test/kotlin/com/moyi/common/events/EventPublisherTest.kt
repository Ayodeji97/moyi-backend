package com.moyi.common.events

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
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
    @Autowired private val manager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    private val transactions = TransactionTemplate(manager)

    @BeforeEach
    fun clear() {
        // Before, not after: the database is shared, and what another test left
        // behind is this test's problem at the moment it starts.
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_subscriptions, outbox_consumers, outbox_events")
    }

    @Test
    fun `publication needs a transaction and a rolled back state change leaves no event and no delivery`() {
        val event = event()
        subscribe("test.publisher.rolled-back", event.eventType)
        shouldThrow<IllegalTransactionStateException> { publisher.publish(event) }
        transactions.executeWithoutResult {
            publisher.publish(event)
            // Seen inside the transaction, so that their absence afterwards is the rollback's doing.
            eventsFor(event) shouldBe 1
            deliveriesTo("test.publisher.rolled-back") shouldBe 1
            it.setRollbackOnly()
        }
        eventsFor(event) shouldBe 0
        deliveriesTo("test.publisher.rolled-back") shouldBe 0
    }

    @Test
    fun `committed history keeps identity and microseconds without inventing deliveries`() {
        val event = event()
        transactions.executeWithoutResult { publisher.publish(event) }
        val row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE aggregate_id = ?", event.aggregateId)
        row["aggregate_type"] shouldBe event.aggregateType
        row["aggregate_id"] shouldBe event.aggregateId
        row["event_type"] shouldBe event.eventType
        (row["occurred_at"] as java.sql.Timestamp).toInstant() shouldBe Instant.parse("2026-09-15T10:00:00.123456Z")
        jdbc.queryForObject(
            "SELECT payload ->> 'bondId' FROM outbox_events WHERE aggregate_id = ?",
            String::class.java,
            event.aggregateId,
        ) shouldBe event.references["bondId"].toString()
        jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE event_id = ?", Int::class.java, row["id"]) shouldBe 0
    }

    @Test
    fun `an event is owed to every consumer subscribed to its type, and to no other`() {
        val event = event()
        subscribe("test.publisher.first", event.eventType)
        subscribe("test.publisher.second", event.eventType)
        subscribe("test.publisher.other-type", "SomethingElse.${UUID.randomUUID()}")

        transactions.executeWithoutResult { publisher.publish(event) }

        val id = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID::class.java, event.aggregateId)
        val deliveries = jdbc.queryForList("SELECT * FROM outbox_deliveries WHERE event_id = ? ORDER BY consumer_id", id)
        deliveries.map { it["consumer_id"] } shouldBe listOf("test.publisher.first", "test.publisher.second")
        deliveries.forEach {
            // Due from the moment the event happened, to the microsecond the event row carries.
            (it["next_attempt_at"] as java.sql.Timestamp).toInstant() shouldBe Instant.parse("2026-09-15T10:00:00.123456Z")
            it["processed_at"] shouldBe null
            it["attempts"] shouldBe 0
            it["last_error"] shouldBe null
        }
    }

    @Test
    fun `a publisher in a transaction stricter than READ COMMITTED is refused, and writes nothing`() {
        // Held back by a registering consumer, such a transaction resumes on the
        // snapshot it already had, sees no new subscription, and commits an
        // event nobody is owed (the review of this module proved it). So it may
        // not publish at all.
        val event = event()
        subscribe("test.publisher.strict", event.eventType)
        listOf(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE).forEach { level ->
            val strict = TransactionTemplate(manager).apply { isolationLevel = level }
            strict.executeWithoutResult {
                shouldThrow<IllegalStateException> { publisher.publish(event) }.message shouldBe
                    "An event may be published only in a READ COMMITTED transaction: at a stricter level a publisher " +
                    "held back by a registering consumer would not see its subscription, and the event would never be delivered to it."
                // Asked inside the transaction: the refusal itself wrote nothing; the rollback is not what removed it.
                eventsFor(event) shouldBe 0
                deliveriesTo("test.publisher.strict") shouldBe 0
                // The refusal has also doomed the caller's transaction, as any exception out of a
                // participating @Transactional does. Said here so the template ends it quietly.
                it.isRollbackOnly shouldBe true
                it.setRollbackOnly()
            }
        }
        eventsFor(event) shouldBe 0

        // The level every publisher here runs at, said out loud, and the default: both publish.
        TransactionTemplate(manager)
            .apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
            .executeWithoutResult { publisher.publish(event) }
        transactions.executeWithoutResult { publisher.publish(event) }
        eventsFor(event) shouldBe 2
        deliveriesTo("test.publisher.strict") shouldBe 2
    }

    private fun subscribe(
        consumerId: String,
        eventType: String,
    ) {
        jdbc.update("INSERT INTO outbox_consumers (consumer_id, registered_at) VALUES (?, now())", consumerId)
        jdbc.update("INSERT INTO outbox_subscriptions (consumer_id, event_type, subscribed_at) VALUES (?, ?, now())", consumerId, eventType)
    }

    private fun eventsFor(event: OutboxEvent): Int =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Int::class.java, event.aggregateId)!!

    private fun deliveriesTo(consumerId: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE consumer_id = ?", Int::class.java, consumerId)!!

    private fun event() =
        OutboxEvent(
            "BondDay",
            UUID.randomUUID(),
            "PublisherTest.${UUID.randomUUID()}",
            mapOf("bondId" to UUID.randomUUID()),
            Instant.parse("2026-09-15T10:00:00.123456789Z"),
        )
}

@SpringBootApplication(scanBasePackages = ["com.moyi.common.events", "com.moyi.common.core"])
internal class EventsTestApplication {
    @Bean
    fun mapper(): ObjectMapper = JsonMapper.builder().build()
}
