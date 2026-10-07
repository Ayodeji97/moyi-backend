package com.moyi.common.events

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
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
 * where a statement sees what committed while the one before it waited;
 * [JdbcEventPublisher] refuses to publish at any other. The mode also conflicts
 * with itself, so two instances starting together register one after the other.
 *
 * **Why the wait for that lock is bounded.** While registration waits, every
 * new publisher waits behind it, so one session that published and never
 * committed would hang this start-up and stop the instances already serving.
 * After [RegistrationProperties.lockTimeout] registration gives up instead: the
 * exception leaves [afterSingletonsInstantiated], the context does not start,
 * and no instance ever runs with a consumer it has not registered.
 *
 * **Why a starting build never removes a subscription.** Registration adds
 * the types a consumer declares and are not stored. A type that is stored and
 * not declared here is left alone, and said in one line. It may belong to a
 * newer build of the same consumer that is running beside this one: a
 * rollback, or a crash and restart half-way through a deploy, starts the older
 * build second. The dispatcher's claim rule exists for that case (it claims
 * only the types its own build declares, [OutboxDispatcher]), and a delete here
 * would undo it: every publisher, on the newer build too, would fan that type
 * out to nobody from then on, with no delivery row to show in a gauge and no
 * line in a log. As first built, registration did delete. A start-up cannot
 * know that no other build declares the type, so retiring one is a migration
 * (ADR-0035, Owed), as retiring a consumer is. Until that migration runs,
 * deliveries of the type go on being written and nobody claims them: they show
 * in the pending and age gauges, which is the reminder.
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
    private val properties: RegistrationProperties,
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
    private val log = LoggerFactory.getLogger(javaClass)

    /** The consumer registered in this process under [id]; none when another instance's build owns it. */
    fun consumer(id: String): EventConsumer? = byId[id]

    /** Every consumer of this process: the dispatcher claims deliveries for these, of the types they declare, and leaves the rest. */
    fun consumers(): Collection<EventConsumer> = byId.values

    /**
     * One transaction per consumer, so the lock is held for one consumer's
     * backfill and no longer. The line is logged once that transaction has
     * committed, so it never reports a registration that was rolled back.
     */
    override fun afterSingletonsInstantiated() {
        byId.values.forEach { consumer ->
            val registration =
                try {
                    transactions.execute { register(consumer) }
                } catch (refused: DataAccessException) {
                    throw if (refused.isLockTimeout()) notRegistered(consumer, refused) else refused
                }
            // Ids, type names and a count: an event carries nothing else that could be said here.
            log.info(
                "Event consumer registered: consumer={} subscribed={} backfilled={}",
                consumer.id,
                registration.subscribed,
                registration.backfilled,
            )
            if (registration.undeclared.isNotEmpty()) {
                log.info(
                    "Event consumer has subscriptions this build does not declare, and they are kept: consumer={} undeclared={}",
                    consumer.id,
                    registration.undeclared,
                )
            }
        }
    }

    /**
     * Adds to the stored subscriptions of [consumer] whatever it declares and
     * they lack, and answers what was new and what was found undeclared. It
     * removes nothing. **The caller owns the transaction**:
     * the lock taken here is held until that transaction ends, and is the
     * whole of the guarantee. (Postgres refuses `LOCK TABLE` outside a
     * transaction, so calling this without one fails rather than registering
     * unguarded.)
     *
     * The bound on the wait is set with `set_config(…, true)`, which is
     * `SET LOCAL` with a parameter: it lasts for this transaction and is gone
     * from the session when it ends, however it ends, so a pooled connection
     * goes back as it came.
     *
     * Separate from [afterSingletonsInstantiated] so a test can hold the
     * transaction open and watch a publisher wait.
     */
    fun register(consumer: EventConsumer): Registration {
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String::class.java, "${properties.lockTimeout.toMillis()}ms")
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
        val declared = consumer.eventTypes
        val added = (declared - stored).sorted()
        val backfilled = added.sumOf { eventType -> subscribe(consumer, eventType, now) }
        // What is stored and not declared here is left alone: see the class's note.
        return Registration(subscribed = added, backfilled = backfilled, undeclared = (stored - declared).sorted())
    }

    /**
     * What one registration found: the event types newly [subscribed], the
     * deliveries [backfilled] for events already published, and the types
     * stored for this consumer that this build does not declare
     * ([undeclared]), which it left as they were.
     */
    data class Registration(
        val subscribed: List<String>,
        val backfilled: Int,
        val undeclared: List<String>,
    )

    /**
     * The backfill runs only here, with the subscription's insert, so a
     * restart cannot repeat it and [StartFrom] is asked exactly once per type.
     * Backfilled deliveries are due at once. Answers how many were written.
     */
    private fun subscribe(
        consumer: EventConsumer,
        eventType: String,
        now: Timestamp,
    ): Int {
        jdbc.update(
            "INSERT INTO outbox_subscriptions (consumer_id, event_type, subscribed_at) VALUES (?, ?, ?)",
            consumer.id,
            eventType,
            now,
        )
        if (consumer.startFrom != StartFrom.BEGINNING) return 0
        return jdbc.update(
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

    /**
     * The start-up failure, in words an operator can act on. The message
     * carries the consumer's id and the configured bound; the driver's own
     * exception rides along as the cause.
     *
     * It says what is known and no more. `SHARE ROW EXCLUSIVE` waits for a
     * publisher that has not committed, but also for another instance's
     * registration (the mode conflicts with itself) and for maintenance on the
     * table; Postgres reports only that the lock was not granted, so the
     * message lists who might hold it and does not pick one.
     */
    private fun notRegistered(
        consumer: EventConsumer,
        refused: DataAccessException,
    ) = IllegalStateException(
        "Event consumer '${consumer.id}' was not registered: the lock on outbox_events was not granted within " +
            "${properties.lockTimeout}. Some other session holds a lock on that table that conflicts with it: " +
            "a transaction that has published an event and not committed, another instance registering, " +
            "or maintenance on the table. The application will not start with a consumer it has not registered.",
        refused,
    )

    private fun DataAccessException.isLockTimeout(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == LOCK_NOT_AVAILABLE }

    private companion object {
        /** Postgres' SQLSTATE for a lock that `lock_timeout` gave up on. */
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
