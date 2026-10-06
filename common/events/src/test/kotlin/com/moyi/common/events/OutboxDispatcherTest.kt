package com.moyi.common.events

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-Postgres proof of the dispatcher's promises (plan C5a, decisions 4 to 7):
 * a delivery is claimed, handled and acknowledged in one transaction; a failure
 * rolls all of that back, is recorded by class name alone, and steps aside for
 * the deliveries behind it; two dispatchers never hand one delivery to a handler twice.
 *
 * The database is shared with whatever else ran in this JVM, so every test
 * cleans before it starts, uses consumer ids and event types of this class,
 * and asserts on the rows it made. This module's context registers no consumer,
 * so truncating the registration tables loses nothing that would not come back.
 */
@SpringBootTest(classes = [EventsTestApplication::class])
internal class OutboxDispatcherTest(
    @Autowired private val publisher: EventPublisher,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val dataSource: DataSource,
    @Autowired private val manager: PlatformTransactionManager,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val wired: OutboxDispatcher,
) : PostgresIntegrationTest() {
    private val transactions = TransactionTemplate(manager)
    private val now: Instant = Instant.parse("2026-10-06T12:00:00.123456Z")
    private val clock = MutableClock(now)

    private val type = "DispatcherTest.${UUID.randomUUID()}"
    private val otherType = "DispatcherTestOther.${UUID.randomUUID()}"
    private val probeType = "DispatcherTestProbe.${UUID.randomUUID()}"

    private val logger = LoggerFactory.getLogger(JdbcOutboxDispatcher::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_subscriptions, outbox_consumers, outbox_events")
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `a due delivery reaches its consumer once, as it was published, and is acknowledged`() {
        val consumer = TestConsumer()
        val dispatcher = dispatcherFor(consumer)
        val aggregateId = UUID.randomUUID()
        val bondId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("DispatcherTest", aggregateId, type, mapOf("bondId" to bondId, "memberId" to memberId), now))
        }
        val eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID::class.java, aggregateId)!!

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)

        consumer.received shouldContainExactly
            listOf(ReceivedEvent(eventId, "DispatcherTest", aggregateId, type, mapOf("bondId" to bondId, "memberId" to memberId), now))
        val delivery = delivery(eventId)
        delivery.processedAt shouldBe now
        delivery.attempts shouldBe 1
        delivery.lastError.shouldBeNull()

        // Acknowledged means not offered again.
        dispatcher.dispatchDue(now.plusSeconds(60), 10) shouldBe DispatchResult(0, 0, false)
        consumer.received shouldHaveSize 1
    }

    @Test
    fun `the instant handed in is stored to the microsecond, truncated and not rounded`() {
        val failing = TestConsumer(id = FAILING) { throw IllegalStateException(ENTRY_LIKE) }
        val dispatcher = dispatcherFor(TestConsumer(), failing)
        val event = publish()

        // Postgres would round ...123456789 up to ...123457.
        dispatcher.dispatchDue(now.plusNanos(789), 10) shouldBe DispatchResult(1, 1, false)

        delivery(event).processedAt shouldBe now
        delivery(event, FAILING).nextAttemptAt shouldBe now.plusSeconds(2)
    }

    @Test
    fun `what a handler writes commits with the acknowledgement`() {
        // The handler publishes, and publication is MANDATORY: that it does not
        // throw is the proof that the handler runs inside the delivery's transaction.
        val probe = UUID.randomUUID()
        val dispatcher = dispatcherFor(TestConsumer { publishProbe(probe) })
        val event = publish()

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(1, 0, false)

        probes(probe) shouldBe 1
        delivery(event).processedAt shouldBe now
    }

    @Test
    fun `a handler that throws takes its own writes back, and the failure is recorded by class name alone`() {
        val probe = UUID.randomUUID()
        val dispatcher =
            dispatcherFor(
                TestConsumer {
                    publishProbe(probe)
                    throw IllegalStateException(ENTRY_LIKE)
                },
            )
        val event = publish()

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)

        probes(probe) shouldBe 0
        val delivery = delivery(event)
        delivery.processedAt.shouldBeNull()
        delivery.attempts shouldBe 1
        delivery.nextAttemptAt shouldBe now.plusSeconds(2)
        delivery.lastError shouldBe "java.lang.IllegalStateException"

        // The log line says which delivery and what kind of failure, and nothing
        // the exception was carrying: no message, and no throwable to print one from.
        val warning = appender.list.single()
        warning.level shouldBe Level.WARN
        warning.formattedMessage shouldContain CONSUMER
        warning.formattedMessage shouldContain event.toString()
        warning.formattedMessage shouldContain type
        warning.formattedMessage shouldContain "attempts=1"
        warning.formattedMessage shouldContain "java.lang.IllegalStateException"
        warning.formattedMessage shouldNotContain ENTRY_LIKE
        warning.throwableProxy.shouldBeNull()
        warning.argumentArray.forEach { it.toString() shouldNotContain ENTRY_LIKE }
    }

    @Test
    fun `an acknowledgement that fails takes the handler's writes with it`() {
        // The other half of "commit together". Were the acknowledgement written
        // after the handler's transaction, the probe would survive this.
        val probe = UUID.randomUUID()
        val faulty = FaultyJdbc(dataSource, failOn = "SET processed_at")
        val dispatcher = dispatcherFor(TestConsumer { publishProbe(probe) }, through = faulty)
        val event = publish()

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(0, 1, false)

        probes(probe) shouldBe 0
        val delivery = delivery(event)
        delivery.processedAt.shouldBeNull()
        delivery.attempts shouldBe 1
        delivery.lastError shouldBe DataAccessResourceFailureException::class.java.name
    }

    @Test
    fun `a failing delivery steps aside in the same run, is not tried twice, and waits twice as long each time`() {
        val failing = TestConsumer(id = FAILING) { throw IllegalStateException(ENTRY_LIKE) }
        val healthy = TestConsumer()
        val dispatcher = dispatcherFor(failing, healthy)
        // The failing consumer's delivery sorts first: same instant, and it is claimed by (time, event, consumer).
        val first = publish()
        val second = publish(at = now.plusMillis(1))

        dispatcher.dispatchDue(now.plusMillis(1), 10) shouldBe DispatchResult(delivered = 2, failed = 2, more = false)

        failing.received.map { it.id } shouldContainExactly listOf(first, second)
        healthy.received.map { it.id } shouldContainExactly listOf(first, second)

        // Not due again until the backoff has run out: 2 s after the failure.
        dispatcher.dispatchDue(now.plusMillis(1).plusSeconds(2).minusNanos(1000), 10) shouldBe DispatchResult(0, 0, false)
        failing.received shouldHaveSize 2

        val retriedAt = now.plusMillis(1).plusSeconds(2)
        dispatcher.dispatchDue(retriedAt, 10) shouldBe DispatchResult(0, 2, false)
        delivery(first, FAILING).attempts shouldBe 2
        delivery(first, FAILING).nextAttemptAt shouldBe retriedAt.plusSeconds(4)
        delivery(first, FAILING).processedAt.shouldBeNull()
    }

    @Test
    fun `a delivery that is not yet due is left for a later run`() {
        val consumer = TestConsumer()
        val dispatcher = dispatcherFor(consumer)
        val event = publish(at = now.plusSeconds(1))

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(0, 0, false)
        consumer.received.shouldBeEmpty()
        delivery(event).attempts shouldBe 0

        dispatcher.dispatchDue(now.plusSeconds(1), 10) shouldBe DispatchResult(1, 0, false)
    }

    @Test
    fun `a run stops at its budget, oldest first, and says whether work remains`() {
        val consumer = TestConsumer()
        val dispatcher = dispatcherFor(consumer)
        val third = publish(at = now.minusSeconds(1))
        val first = publish(at = now.minusSeconds(3))
        val second = publish(at = now.minusSeconds(2))

        dispatcher.dispatchDue(now, 2) shouldBe DispatchResult(delivered = 2, failed = 0, more = true)
        consumer.received.map { it.id } shouldContainExactly listOf(first, second)
        delivery(third).processedAt.shouldBeNull()

        // The budget is met exactly and nothing is left: `more` is asked of the table, not assumed.
        dispatcher.dispatchDue(now, 1) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
    }

    @Test
    fun `failures count towards the budget`() {
        val failing = TestConsumer { throw IllegalStateException(ENTRY_LIKE) }
        val dispatcher = dispatcherFor(failing)
        repeat(3) { publish() }

        dispatcher.dispatchDue(now, 2) shouldBe DispatchResult(delivered = 0, failed = 2, more = true)
        failing.received shouldHaveSize 2
    }

    @Test
    fun `a delivery owed to a consumer this process does not have is left alone, and is not work that remains`() {
        // Another instance's build owns it. Registered here so the rows exist; not given to the dispatcher.
        val elsewhere = TestConsumer(id = ELSEWHERE)
        registryOf(elsewhere).afterSingletonsInstantiated()
        val mine = TestConsumer()
        val dispatcher = dispatcherFor(mine)
        val event = publish()

        // The budget is met by its own delivery, and the other is not "more"; a second run, with room, still passes over it.
        dispatcher.dispatchDue(now, 1) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 0, failed = 0, more = false)

        elsewhere.received.shouldBeEmpty()
        val foreign = delivery(event, ELSEWHERE)
        foreign.processedAt.shouldBeNull()
        foreign.attempts shouldBe 0
        foreign.lastError.shouldBeNull()
        foreign.nextAttemptAt shouldBe now
    }

    @Test
    fun `a delivery of a type the local consumer does not declare is left alone, and is not work that remains`() {
        // A rolling deploy: a newer build of this consumer subscribed to another
        // type, and its deliveries carry this consumer's id. The bean here was
        // not written for that type; claiming it would acknowledge an event
        // nobody handled. It is the newer instance's, exactly as another consumer's would be.
        val local = TestConsumer(types = setOf(type))
        val dispatcher = dispatcherFor(local)
        // The newer build registers second: registering the local one after it would drop the subscription again.
        val newer = TestConsumer(types = setOf(type, otherType))
        registryOf(newer).afterSingletonsInstantiated()
        val declared = publish()
        val undeclared = publish(ofType = otherType)

        dispatcher.dispatchDue(now, 1) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 0, failed = 0, more = false)

        local.received.map { it.id } shouldContainExactly listOf(declared)
        val notOurs = delivery(undeclared)
        notOurs.processedAt.shouldBeNull()
        notOurs.attempts shouldBe 0
        notOurs.lastError.shouldBeNull()
        newer.received.shouldBeEmpty()
        // Still pending work that somebody must do, so the backlog shows it.
        dispatcher.backlog(now) shouldContainExactly listOf(ConsumerBacklog(CONSUMER, pending = 1, failing = 0, oldestAgeSeconds = 0))
    }

    @Test
    fun `the dispatcher the context wires has no consumers here, so it touches nothing`() {
        dispatcherFor(TestConsumer())
        val event = publish()

        wired.dispatchDue(now, 10) shouldBe DispatchResult(0, 0, false)

        delivery(event).attempts shouldBe 0
    }

    @Test
    fun `a failure that cannot be recorded leaves the delivery due, and the run goes on without trying it again`() {
        val faulty = FaultyJdbc(dataSource, failOn = "last_error = ?")
        val failing = TestConsumer(id = FAILING) { throw IllegalStateException(ENTRY_LIKE) }
        val healthy = TestConsumer()
        val dispatcher = dispatcherFor(failing, healthy, through = faulty)
        val event = publish()

        // The unrecorded failure is still due and still claimable, so work remains.
        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 1, failed = 1, more = true)

        failing.received shouldHaveSize 1
        healthy.received shouldHaveSize 1
        val unrecorded = delivery(event, FAILING)
        unrecorded.processedAt.shouldBeNull()
        unrecorded.attempts shouldBe 0
        unrecorded.nextAttemptAt shouldBe now
        appender.list.forEach {
            it.level shouldBe Level.WARN
            it.formattedMessage shouldNotContain ENTRY_LIKE
            it.formattedMessage shouldNotContain FaultyJdbc.MESSAGE
            it.throwableProxy.shouldBeNull()
        }

        // Nothing was lost: the next run, with the database well again, tries it and records it.
        faulty.failOn = null
        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(0, 1, false)
        delivery(event, FAILING).attempts shouldBe 1
    }

    @Test
    fun `a checked exception and an Error roll back and are recorded like any other failure`() {
        val probe = UUID.randomUUID()
        val checked =
            TestConsumer(id = FAILING) {
                publishProbe(probe)
                throw IOException(ENTRY_LIKE)
            }
        val erroring =
            TestConsumer {
                publishProbe(probe)
                throw NotImplementedError(ENTRY_LIKE)
            }
        val dispatcher = dispatcherFor(checked, erroring)
        val event = publish()

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(0, 2, false)

        probes(probe) shouldBe 0
        // The handler's own exception, not the wrapper a transaction puts round a checked one.
        delivery(event, FAILING).lastError shouldBe "java.io.IOException"
        delivery(event).lastError shouldBe "kotlin.NotImplementedError"
    }

    @Test
    fun `a virtual machine error is recorded and then rethrown, because the process is not well`() {
        val dispatcher = dispatcherFor(TestConsumer { throw StackOverflowError(ENTRY_LIKE) })
        val event = publish()

        shouldThrow<StackOverflowError> { dispatcher.dispatchDue(now, 10) }

        delivery(event).attempts shouldBe 1
        delivery(event).lastError shouldBe "java.lang.StackOverflowError"
    }

    @Test
    fun `a payload that cannot be read fails its own delivery and not the run`() {
        val consumer = TestConsumer()
        val dispatcher = dispatcherFor(consumer)
        val unreadable = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
            VALUES (?, 'DispatcherTest', ?, ?, '{"bondId": "$ENTRY_LIKE"}'::jsonb, ?)
            """.trimIndent(),
            unreadable,
            UUID.randomUUID(),
            type,
            Timestamp.from(now.minusSeconds(1)),
        )
        jdbc.update(
            "INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at) VALUES (?, ?, ?)",
            unreadable,
            CONSUMER,
            Timestamp.from(now.minusSeconds(1)),
        )
        val readable = publish()

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 1, failed = 1, more = false)

        consumer.received.map { it.id } shouldContainExactly listOf(readable)
        delivery(unreadable).attempts shouldBe 1
        delivery(unreadable).lastError!! shouldNotContain ENTRY_LIKE
        appender.list.forEach { it.formattedMessage shouldNotContain ENTRY_LIKE }
    }

    @Test
    fun `a database that cannot be asked for work is the caller's to hear about`() {
        val faulty = FaultyJdbc(dataSource, failOn = "e.payload::text")
        val consumer = TestConsumer()
        val dispatcher = dispatcherFor(consumer, through = faulty)
        val event = publish()

        shouldThrow<DataAccessResourceFailureException> { dispatcher.dispatchDue(now, 10) }

        consumer.received.shouldBeEmpty()
        delivery(event).attempts shouldBe 0
    }

    @Test
    fun `a run inside the caller's transaction is refused, because one delivery could no longer be one transaction`() {
        val consumer = TestConsumer()
        val dispatcher = dispatcherFor(consumer)
        publish()

        shouldThrow<IllegalStateException> {
            transactions.executeWithoutResult { dispatcher.dispatchDue(now, 10) }
        }
        shouldThrow<IllegalArgumentException> { dispatcher.dispatchDue(now, 0) }

        consumer.received.shouldBeEmpty()
    }

    @Test
    fun `the backlog counts what is due and what keeps failing, per consumer, against the instant handed in`() {
        val busy = TestConsumer()
        val idle = TestConsumer(id = FAILING)
        dispatcherFor(busy, idle)
        val oldest = publish(at = now.minusSeconds(90))
        val recent = publish(at = now.minusSeconds(10))
        val notDue = publish(at = now.plusSeconds(30))
        val done = publish(at = now.minusSeconds(500))
        jdbc.update("DELETE FROM outbox_deliveries WHERE consumer_id = ?", FAILING)
        jdbc.update("UPDATE outbox_deliveries SET processed_at = ?, attempts = 9 WHERE event_id = ?", Timestamp.from(now), done)
        // Backing off, so not due; five failures, so failing. Four is not yet.
        jdbc.update("UPDATE outbox_deliveries SET attempts = 5 WHERE event_id = ?", notDue)
        jdbc.update("UPDATE outbox_deliveries SET attempts = 4 WHERE event_id = ?", recent)

        wired.backlog(now) shouldContainExactly
            listOf(
                ConsumerBacklog(CONSUMER, pending = 2, failing = 1, oldestAgeSeconds = 90),
                // A consumer with nothing owed is reported, at zero: a gauge has to be able to come back down.
                ConsumerBacklog(FAILING, pending = 0, failing = 0, oldestAgeSeconds = 0),
            )
        wired.backlog(now.plusSeconds(30).plusNanos(999)) shouldContainExactly
            listOf(
                ConsumerBacklog(CONSUMER, pending = 3, failing = 1, oldestAgeSeconds = 120),
                ConsumerBacklog(FAILING, pending = 0, failing = 0, oldestAgeSeconds = 0),
            )
        delivery(oldest).attempts shouldBe 0
    }

    @Test
    fun `two dispatchers at once hand one delivery to its handler once`() {
        // The first dispatcher is held inside the handler, so it holds the
        // delivery's row lock. The second must skip that row and come back
        // empty-handed: without the lock it would run the handler too, and
        // without SKIP LOCKED it would wait. Either way it does not return.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumer =
            TestConsumer {
                entered.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the handler" }
            }
        val registry = registered(consumer)
        val first = JdbcOutboxDispatcher(registry, jdbc, manager, mapper)
        val second = JdbcOutboxDispatcher(registry, jdbc, manager, mapper)
        val event = publish()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holding = pool.submit<DispatchResult> { first.dispatchDue(now, 10) }
            entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val skipping = pool.submit<DispatchResult> { second.dispatchDue(now, 10) }
            skipping.get(WAIT_SECONDS / 2, TimeUnit.SECONDS) shouldBe DispatchResult(delivered = 0, failed = 0, more = false)
            holding.isDone shouldBe false

            release.countDown()
            holding.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }

        consumer.received.map { it.id } shouldContainExactly listOf(event)
        delivery(event).attempts shouldBe 1
        delivery(event).processedAt shouldBe now
    }

    /** A dispatcher over [consumers], registered, reading and writing [through]. */
    private fun dispatcherFor(
        vararg consumers: EventConsumer,
        through: JdbcTemplate = jdbc,
    ): OutboxDispatcher = JdbcOutboxDispatcher(registered(*consumers), through, manager, mapper)

    private fun registered(vararg consumers: EventConsumer): ConsumerRegistry =
        registryOf(*consumers).also { it.afterSingletonsInstantiated() }

    private fun registryOf(vararg consumers: EventConsumer) = ConsumerRegistry(consumers.toList(), jdbc, manager, clock)

    /** Publishes an event of [ofType] (this test's own unless said), due at [at], and answers its id. */
    private fun publish(
        at: Instant = now,
        ofType: String = type,
    ): UUID {
        val aggregateId = UUID.randomUUID()
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("DispatcherTest", aggregateId, ofType, mapOf("bondId" to UUID.randomUUID()), at))
        }
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID::class.java, aggregateId)!!
    }

    /** What a handler writes: an event nobody subscribes to, found again by its aggregate id. */
    private fun publishProbe(probe: UUID) = publisher.publish(OutboxEvent("DispatcherTestProbe", probe, probeType, emptyMap(), now))

    private fun probes(probe: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Int::class.java, probe)!!

    private fun delivery(
        eventId: UUID,
        consumerId: String = CONSUMER,
    ): Delivery =
        jdbc
            .query(
                "SELECT * FROM outbox_deliveries WHERE event_id = ? AND consumer_id = ?",
                { row, _ ->
                    Delivery(
                        row.getTimestamp("processed_at")?.toInstant(),
                        row.getInt("attempts"),
                        row.getTimestamp("next_attempt_at").toInstant(),
                        row.getString("last_error"),
                    )
                },
                eventId,
                consumerId,
            ).single()

    private data class Delivery(
        val processedAt: Instant?,
        val attempts: Int,
        val nextAttemptAt: Instant,
        val lastError: String?,
    )

    private inner class TestConsumer(
        override val id: String = CONSUMER,
        types: Set<String>? = null,
        private val handler: (ReceivedEvent) -> Unit = {},
    ) : EventConsumer {
        override val eventTypes = types ?: setOf(type)
        override val startFrom = StartFrom.NOW
        val received = CopyOnWriteArrayList<ReceivedEvent>()

        override fun handle(event: ReceivedEvent) {
            received += event
            handler(event)
        }
    }

    /**
     * A [JdbcTemplate] on the same data source (so the same transactions) that
     * refuses any statement containing [failOn]: the database going away between
     * two particular statements, which nothing else here can arrange.
     */
    private class FaultyJdbc(
        dataSource: DataSource,
        @Volatile var failOn: String?,
    ) : JdbcTemplate(dataSource) {
        override fun update(
            sql: String,
            vararg args: Any?,
        ): Int {
            refuse(sql)
            return super.update(sql, *args)
        }

        override fun <T : Any?> query(
            sql: String,
            rowMapper: RowMapper<T>,
            vararg args: Any?,
        ): List<T> {
            refuse(sql)
            return super.query(sql, rowMapper, *args)
        }

        private fun refuse(sql: String) {
            val fragment = failOn ?: return
            if (sql.contains(fragment)) throw DataAccessResourceFailureException(MESSAGE)
        }

        companion object {
            const val MESSAGE = "connection lost while writing 'thank you for the tea this morning'"
        }
    }

    private companion object {
        const val CONSUMER = "test.dispatcher.consumer"
        const val FAILING = "test.dispatcher.failing"
        const val ELSEWHERE = "test.dispatcher.elsewhere"
        const val ENTRY_LIKE = "Thank you for the tea this morning"
        const val WAIT_SECONDS = 10L
    }
}
