package com.moyi.common.events

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Every [EventConsumer] in this process, and the step that makes the database
 * agree with them before anything is served.
 *
 * **Why `SmartInitializingSingleton` and not `ApplicationReadyEvent`.** That
 * event fires after the port is open, so a request could publish an event
 * between the server starting and the consumer subscribing. This callback runs
 * once every singleton exists (Flyway's among them, so the tables are there)
 * and before the web server accepts a connection.
 *
 * **Why registration takes a table lock.** A publisher writes its deliveries
 * from the subscriptions it can see; registration backfills from the events it
 * can see. Unlocked, a publisher that has inserted but not committed is
 * invisible to the backfill and has already missed the subscription, and its
 * event is lost to the new consumer for ever, silently. `SHARE ROW EXCLUSIVE`
 * on `outbox_events` conflicts with the `ROW EXCLUSIVE` every insert takes, so
 * registration waits for each transaction that has already published, and
 * holds back each one about to. Those it waited for are committed and so in
 * the backfill; those it held back read the subscriptions only after it has
 * committed. That second half rests on publishers running at `READ COMMITTED`,
 * where a statement sees what committed while the one before it waited; every
 * publisher here does. The mode also conflicts with itself, so two instances
 * starting together register one after the other.
 *
 * A context with no consumers touches nothing, so this bean costs the contexts
 * that only publish (or have no outbox tables at all) no statement.
 */
@Component
internal class ConsumerRegistry(
    consumers: List<EventConsumer>,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : SmartInitializingSingleton {
    private val byId: Map<String, EventConsumer> =
        consumers
            .groupBy { it.id }
            .mapValues { (id, sameId) ->
                // Two beans under one id would share one row of delivery state:
                // each event would reach one of them, never both, and nothing
                // would say which.
                check(sameId.size == 1) { "Two event consumers share the id '$id'; delivery state is keyed by it, so it must be unique." }
                sameId.single()
            }
    private val transactions = TransactionTemplate(transactionManager)

    /** The consumer registered in this process under [id]; none when another instance's build owns it. */
    fun consumer(id: String): EventConsumer? = byId[id]

    /** One transaction per consumer, so the lock is held for one consumer's backfill and no longer. */
    override fun afterSingletonsInstantiated() {
        byId.values.forEach { consumer -> transactions.executeWithoutResult { register(consumer) } }
    }

    /**
     * Brings the stored subscriptions of [consumer] into line with what it
     * declares. **The caller owns the transaction**: the lock taken first is
     * held until that transaction ends, and is the whole of the guarantee.
     * (Postgres refuses `LOCK TABLE` outside a transaction, so calling this
     * without one fails rather than registering unguarded.)
     *
     * Separate from [afterSingletonsInstantiated] so a test can hold the
     * transaction open and watch a publisher wait.
     */
    fun register(consumer: EventConsumer) {
        jdbc.execute("LOCK TABLE outbox_events IN SHARE ROW EXCLUSIVE MODE")
        // Truncated, because Postgres rounds: these are compared with what comes back.
        val now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS))
        jdbc.update(
            "INSERT INTO outbox_consumers (consumer_id, registered_at) VALUES (?, ?) ON CONFLICT (consumer_id) DO NOTHING",
            consumer.id,
            now,
        )

        val stored =
            jdbc
                .queryForList("SELECT event_type FROM outbox_subscriptions WHERE consumer_id = ?", String::class.java, consumer.id)
                .filterNotNull()
                .toSet()
        (consumer.eventTypes - stored).forEach { eventType -> subscribe(consumer, eventType, now) }
        // What it no longer declares stops being fanned out to it. Deliveries
        // already owed are kept: they were promised when their events were published.
        (stored - consumer.eventTypes).forEach { eventType ->
            jdbc.update("DELETE FROM outbox_subscriptions WHERE consumer_id = ? AND event_type = ?", consumer.id, eventType)
        }
    }

    /**
     * The backfill runs only here, with the subscription's insert, so a
     * restart cannot repeat it and [StartFrom] is asked exactly once per type.
     * Backfilled deliveries are due at once.
     */
    private fun subscribe(
        consumer: EventConsumer,
        eventType: String,
        now: Timestamp,
    ) {
        jdbc.update(
            "INSERT INTO outbox_subscriptions (consumer_id, event_type, subscribed_at) VALUES (?, ?, ?)",
            consumer.id,
            eventType,
            now,
        )
        if (consumer.startFrom == StartFrom.BEGINNING) {
            jdbc.update(
                """
                INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at)
                SELECT id, ?, ? FROM outbox_events WHERE event_type = ?
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                consumer.id,
                now,
                eventType,
            )
        }
    }
}
