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
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
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
 * Real-Postgres proof of the dispatcher's promises (ADR-0035 decisions 4 to 7):
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
// LongParameterList: what Spring hands the test; each is used, and there is nothing to bundle them into.
// LargeClass: every promise of one class, over helpers they share; split, each half would need the other's.
@Suppress("LongParameterList", "LargeClass")
internal class OutboxDispatcherTest(
    @Autowired private val publisher: EventPublisher,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val dataSource: DataSource,
    @Autowired private val manager: PlatformTransactionManager,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val wired: OutboxDispatcher,
    @Autowired private val bound: DeliveryProperties,
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
        // Claimed by (time, event, consumer), and the healthy consumer's id sorts first: so the order is
        // first/healthy, first/failing, second/healthy, second/failing. The first event's failure is
        // followed by both deliveries of the second, and that is the stepping aside.
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
    fun `the backlog counts what is due and what keeps failing, per consumer, and its age is the oldest unhandled event's`() {
        val busy = TestConsumer()
        val idle = TestConsumer(id = FAILING)
        dispatcherFor(busy, idle)
        val oldest = publish(at = now.minusSeconds(90))
        val recent = publish(at = now.minusSeconds(10))
        val notDue = publish(at = now.plusSeconds(30))
        val done = publish(at = now.minusSeconds(500))
        jdbc.update("DELETE FROM outbox_deliveries WHERE consumer_id = ?", FAILING)
        jdbc.update("UPDATE outbox_deliveries SET processed_at = ?, attempts = 9 WHERE event_id = ?", Timestamp.from(now), done)
        // The oldest has failed seven times and is waiting out its backoff: not
        // due, so not pending, but it is still the event that has gone longest
        // unhandled. Measured from when it next falls due, its age would never
        // pass the backoff's cap, and an alert on age could never fire for it.
        jdbc.update(
            "UPDATE outbox_deliveries SET attempts = 7, next_attempt_at = ? WHERE event_id = ?",
            Timestamp.from(now.plusSeconds(600)),
            oldest,
        )
        // Five failures is failing; four is not yet.
        jdbc.update("UPDATE outbox_deliveries SET attempts = 5 WHERE event_id = ?", notDue)
        jdbc.update("UPDATE outbox_deliveries SET attempts = 4 WHERE event_id = ?", recent)

        wired.backlog(now) shouldContainExactly
            listOf(
                ConsumerBacklog(CONSUMER, pending = 1, failing = 2, oldestAgeSeconds = 90),
                // A consumer with nothing owed is reported, at zero: a gauge has to be able to come back down.
                ConsumerBacklog(FAILING, pending = 0, failing = 0, oldestAgeSeconds = 0),
            )
        wired.backlog(now.plusSeconds(30).plusNanos(999)) shouldContainExactly
            listOf(
                ConsumerBacklog(CONSUMER, pending = 2, failing = 2, oldestAgeSeconds = 120),
                ConsumerBacklog(FAILING, pending = 0, failing = 0, oldestAgeSeconds = 0),
            )

        // Once the old ones are handled, the age is that of what is left: an
        // event that has not happened yet by this clock is no age at all, not a negative one.
        jdbc.update("UPDATE outbox_deliveries SET processed_at = ? WHERE event_id IN (?, ?)", Timestamp.from(now), oldest, recent)
        wired.backlog(now).first() shouldBe ConsumerBacklog(CONSUMER, pending = 0, failing = 1, oldestAgeSeconds = 0)
    }

    @Test
    fun `nothing a handler's exception says reaches any log, ours at TRACE or Spring's at DEBUG, even when the rollback fails too`() {
        // Everything this JVM logs while the dispatcher runs, with the transaction
        // and JDBC machinery at DEBUG: Spring's own TransactionTemplate logs an
        // "application exception" whole, message and causes, at DEBUG on every
        // rollback and at ERROR when the rollback itself throws. So the handler's
        // exception must never be Spring's to hold.
        //
        // And with everything of ours at TRACE: a `log.debug(failure.message)`
        // in the dispatcher is silent at the default level and would be heard
        // the day somebody turned `com.moyi` up to find a fault. What Spring
        // says at TRACE is not claimed here; it is not the dispatcher's to keep.
        val everything = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val ours = LoggerFactory.getLogger("com.moyi") as Logger
        val springs = listOf("org.springframework.transaction", "org.springframework.jdbc").map { LoggerFactory.getLogger(it) as Logger }
        val chatty = springs + ours
        val levels = chatty.map { it.level }
        root.addAppender(everything)
        springs.forEach { it.level = Level.DEBUG }
        ours.level = Level.TRACE
        val (first, second) =
            try {
                val quoting = TestConsumer { throw IllegalStateException(ENTRY_LIKE, RuntimeException(NESTED_ENTRY_LIKE)) }
                val registry = registered(quoting)
                val first = publish()
                dispatcherOver(registry).dispatchDue(now, 10) shouldBe DispatchResult(0, 1, false)

                // The connection drops mid-handler: the likeliest reason a handler fails at all.
                val second = publish(at = now.plusMillis(1))
                val later = now.plusMillis(1)
                dispatcherOver(registry, manager = RollbackThatFails(manager)).dispatchDue(later, 10) shouldBe
                    DispatchResult(0, 1, false)

                first to second
            } finally {
                chatty.zip(levels).forEach { (logger, level) -> logger.level = level }
                root.detachAppender(everything)
            }

        // The leak first, so that a failure here names the line that carried it.
        everything.list.filter { said(it).contains(ENTRY_LIKE) || said(it).contains(NESTED_ENTRY_LIKE) }.map {
            "${it.level} ${it.loggerName}: ${it.formattedMessage.take(60)}"
        } shouldBe emptyList()
        // And not vacuous: the machinery was heard at DEBUG, and it spoke of both rollbacks.
        everything.list.count { it.level == Level.DEBUG && it.formattedMessage == "Transactional code has requested rollback" } shouldBe 2

        // And the failure is recorded all the same, as the handler's own, both times.
        delivery(first).lastError shouldBe "java.lang.IllegalStateException"
        delivery(second).lastError shouldBe "java.lang.IllegalStateException"
        delivery(second).attempts shouldBe 1
    }

    @Test
    fun `a constraint deferred to the commit is made to answer inside the delivery, and what it says of the row reaches no log`() {
        // A constraint the database checks only when the transaction commits: by then the handler has
        // returned and the acknowledgement is written, and the refusal would be raised inside the
        // transaction manager, which logs it whole at DEBUG. It words the refusal as Postgres words a
        // violated constraint, with the row.
        jdbc.execute("CREATE TABLE $DEFERRED_TABLE (words text NOT NULL)")
        jdbc.execute(
            """
            CREATE FUNCTION $DEFERRED_REFUSAL() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'Failing row contains (%)', NEW.words USING ERRCODE = 'check_violation';
            END ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE CONSTRAINT TRIGGER $DEFERRED_REFUSAL AFTER INSERT ON $DEFERRED_TABLE DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION $DEFERRED_REFUSAL()
            """.trimIndent(),
        )
        val everything = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val level = root.level
        try {
            val probe = UUID.randomUUID()
            var rowsSeenByTheHandler = 0
            val consumer =
                TestConsumer {
                    publishProbe(probe)
                    jdbc.update("INSERT INTO $DEFERRED_TABLE (words) VALUES (?)", ENTRY_LIKE)
                    // Deferred: the insert is taken, and the handler ends without having heard a word against it.
                    rowsSeenByTheHandler = jdbc.queryForObject("SELECT count(*) FROM $DEFERRED_TABLE", Int::class.java)!!
                }
            val dispatcher = dispatcherFor(consumer)
            val event = publish()
            root.addAppender(everything)
            root.level = Level.DEBUG

            dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)

            root.level = level
            consumer.received shouldHaveSize 1
            rowsSeenByTheHandler shouldBe 1
            // Everything the handler wrote went with the delivery.
            jdbc.queryForObject("SELECT count(*) FROM $DEFERRED_TABLE", Int::class.java) shouldBe 0
            probes(probe) shouldBe 0
            // A failure of the delivery like any other: by class name, counted, due again.
            val delivery = delivery(event)
            delivery.processedAt.shouldBeNull()
            delivery.attempts shouldBe 1
            delivery.nextAttemptAt shouldBe now.plusSeconds(2)
            delivery.lastError shouldBe DataIntegrityViolationException::class.java.name

            // The leak first, so that a failure here names the line that carried it.
            everything.list.filter { said(it).contains(ENTRY_LIKE) || said(it).contains("Failing row") }.map {
                "${it.level} ${it.loggerName}: ${it.formattedMessage.take(60)}"
            } shouldBe emptyList()
            // And not vacuous: the transaction manager was heard at DEBUG, undoing this delivery.
            everything.list.count { it.level == Level.DEBUG && it.formattedMessage.startsWith("Rolling back JDBC transaction") } shouldBe 1
        } finally {
            root.level = level
            root.detachAppender(everything)
            jdbc.execute("DROP TABLE IF EXISTS $DEFERRED_TABLE")
            jdbc.execute("DROP FUNCTION IF EXISTS $DEFERRED_REFUSAL()")
        }
    }

    @Test
    fun `a failure is not recorded over an acknowledgement that landed first, and is not called a failure`() {
        // Between a handler's rollback and the record of its failure the delivery
        // is unlocked and due. Here another instance takes it in that gap and
        // succeeds. The record must then leave the row exactly as acknowledged,
        // and the first run must not report a failure that will be retried.
        var calls = 0
        val consumer = TestConsumer { if (calls++ == 0) throw IllegalStateException(ENTRY_LIKE) }
        val registry = registered(consumer)
        val other = dispatcherOver(registry)
        var inTheGap: DispatchResult? = null
        val gap = InTheGap(dataSource, before = "last_error = ?") { inTheGap = other.dispatchDue(now.plusSeconds(1), 10) }
        val event = publish()

        val result = dispatcherOver(registry, through = gap).dispatchDue(now, 10)

        // The row first: this is what the guard on the record protects.
        inTheGap shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        consumer.received shouldHaveSize 2
        val acknowledged = delivery(event)
        acknowledged.processedAt shouldBe now.plusSeconds(1)
        acknowledged.attempts shouldBe 1
        acknowledged.lastError.shouldBeNull()
        acknowledged.nextAttemptAt shouldBe now
        // Then what the run says of it: a record that changed nothing is not a failure to be retried.
        result shouldBe DispatchResult(delivered = 0, failed = 0, more = false)
        appender.list.filter { it.level == Level.WARN }.shouldBeEmpty()
        val said = appender.list.single()
        said.level shouldBe Level.INFO
        said.formattedMessage shouldContain event.toString()
        said.formattedMessage shouldContain "java.lang.IllegalStateException"
        said.formattedMessage shouldNotContain "retried"
        said.formattedMessage shouldNotContain ENTRY_LIKE
        said.throwableProxy.shouldBeNull()
    }

    @Test
    fun `two instances failing one delivery count both failures, and the later record cannot shorten the wait`() {
        // Each instance read `attempts = 0` when it claimed. Were the wait worked
        // out from what each had read, both would write the first step, and the
        // one that wrote last would decide when the delivery is next due.
        val consumer = TestConsumer { throw IllegalStateException(ENTRY_LIKE) }
        val registry = registered(consumer)
        val other = dispatcherOver(registry)

        // The other instance fails it half a second later and records first. This one's record is then
        // the second failure: the second step, four seconds, from this one's own `now`.
        val soon = publish()
        val closeBehind = InTheGap(dataSource, before = "last_error = ?") { other.dispatchDue(now.plusMillis(500), 10) }
        dispatcherOver(registry, through = closeBehind).dispatchDue(now, 10) shouldBe DispatchResult(0, 1, false)
        delivery(soon).attempts shouldBe 2
        delivery(soon).nextAttemptAt shouldBe now.plusSeconds(4)
        // Each line reports the count its own record left in the row.
        appender.list
            .map { it.formattedMessage }
            .filter { it.contains(soon.toString()) }
            .map { it.substringAfter("attempts=").take(1) } shouldBe listOf("1", "2")

        // The other instance's clock is a minute ahead, so what it wrote is later than anything this
        // one would write. Writing last must not bring the delivery forward.
        jdbc.execute("TRUNCATE TABLE outbox_deliveries")
        val late = publish()
        val farAhead = InTheGap(dataSource, before = "last_error = ?") { other.dispatchDue(now.plusSeconds(60), 10) }
        dispatcherOver(registry, through = farAhead).dispatchDue(now, 10) shouldBe DispatchResult(0, 1, false)
        delivery(late).attempts shouldBe 2
        delivery(late).nextAttemptAt shouldBe now.plusSeconds(62)
    }

    @Test
    fun `a handler still at work when the delivery's time is up fails the delivery, and what it wrote goes with it`() {
        // The handler writes, then outlasts the delivery's one second away from
        // the database, where nothing can stop it. The next statement of that
        // transaction, the acknowledgement, is refused: a delivery that ran out
        // of time is a failed delivery, recorded and backed off like any other.
        val probe = UUID.randomUUID()
        val never = CountDownLatch(1)
        val slow =
            TestConsumer {
                publishProbe(probe)
                // The wait is the thing on trial: there is no event to synchronise on but the deadline passing.
                never.await(OUTLASTING_ONE_SECOND_MS, TimeUnit.MILLISECONDS) shouldBe false
            }
        val dispatcher = dispatcherOver(registered(slow), within = ONE_SECOND)
        val event = publish()

        dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)

        probes(probe) shouldBe 0
        val delivery = delivery(event)
        delivery.processedAt.shouldBeNull()
        delivery.attempts shouldBe 1
        delivery.nextAttemptAt shouldBe now.plusSeconds(2)
        delivery.lastError shouldBe TransactionTimedOutException::class.java.name
        appender.list.single().formattedMessage shouldContain "will be retried"
    }

    @Test
    fun `a handler waiting for a lock is cut off by the database when the delivery's time is up, whoever made the statement`() {
        // Another session holds a row and will not let go. The handler asks for
        // it through the connection itself and not through JdbcTemplate, so the
        // statement carries no timeout of Spring's: only the bound the dispatcher
        // gave the transaction on the server can end the wait. Without it this
        // delivery, and the run, and the poller's thread, wait for as long as
        // the other session does.
        val heldEvent = publish(ofType = otherType)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting =
            TestConsumer {
                DataSourceUtils.getConnection(dataSource).createStatement().use { statement ->
                    statement.execute("SELECT 1 FROM outbox_events WHERE id = '$heldEvent' FOR UPDATE")
                }
            }
        val dispatcher = dispatcherOver(registered(waiting), within = ONE_SECOND)
        val event = publish()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder =
                pool.submit {
                    transactions.executeWithoutResult {
                        jdbc.queryForList("SELECT 1 FROM outbox_events WHERE id = ? FOR UPDATE", heldEvent)
                        holding.countDown()
                        check(release.await(2 * WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the row" }
                    }
                }
            holding.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val dispatching = pool.submit<DispatchResult> { dispatcher.dispatchDue(now, 10) }

            // Well inside the time the row is held for: the wait was ended, not outlasted.
            dispatching.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)
            holder.isDone shouldBe false
        } finally {
            release.countDown()
            pool.shutdownNow()
        }

        waiting.received shouldHaveSize 1
        val delivery = delivery(event)
        delivery.processedAt.shouldBeNull()
        delivery.attempts shouldBe 1
        delivery.nextAttemptAt shouldBe now.plusSeconds(2)
        // The driver's own exception, by name: the handler made the statement, so nothing of Spring's translated it.
        delivery.lastError shouldBe "org.postgresql.util.PSQLException"
    }

    @Test
    fun `the bound on a delivery's statements is its transaction's, and is gone from the session after it`() {
        // One connection throughout, so that "after" is asked of the very session the delivery ran on.
        dataSource.connection.use { connection ->
            val single = SingleConnectionDataSource(connection, true)
            val singleJdbc = JdbcTemplate(single)
            val sessionDefault = singleJdbc.queryForObject("SHOW statement_timeout", String::class.java)
            var during: String? = null
            val asking = TestConsumer { during = singleJdbc.queryForObject("SHOW statement_timeout", String::class.java) }
            val properties = DeliveryProperties(deliveryTimeout = Duration.ofMillis(7_500))
            val onThatSession = DataSourceTransactionManager(single)
            val dispatcher = dispatcherOver(registered(asking), through = singleJdbc, manager = onThatSession, within = properties)
            publish()

            dispatcher.dispatchDue(now, 10) shouldBe DispatchResult(1, 0, false)

            during shouldBe "7500ms"
            singleJdbc.queryForObject("SHOW statement_timeout", String::class.java) shouldBe sessionDefault
        }
    }

    @Test
    fun `a delivery has a minute unless a property says otherwise, and never less than the second a transaction can count`() {
        // The context sets nothing, so this is the default every context gets.
        bound.deliveryTimeout shouldBe Duration.ofSeconds(60)

        ApplicationContextRunner()
            .withUserConfiguration(EventsConfiguration::class.java)
            .withPropertyValues("moyi.outbox.delivery-timeout=5s")
            .run { it.getBean(DeliveryProperties::class.java).deliveryTimeout shouldBe Duration.ofSeconds(5) }

        // Zero would mean no limit, to Spring and to Postgres alike.
        shouldThrow<IllegalArgumentException> { DeliveryProperties(deliveryTimeout = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { DeliveryProperties(deliveryTimeout = Duration.ofMillis(999)) }
        // A transaction's deadline is counted in whole seconds: part of one is a whole one, never none.
        DeliveryProperties(deliveryTimeout = Duration.ofMillis(1_001)).transactionSeconds shouldBe 2
        DeliveryProperties(deliveryTimeout = Duration.ofSeconds(60)).transactionSeconds shouldBe 60
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
        val first = dispatcherOver(registry)
        val second = dispatcherOver(registry)
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
    ): OutboxDispatcher = dispatcherOver(registered(*consumers), through = through)

    /** The dispatcher itself over [registry], with whichever of its collaborators a test replaces. */
    private fun dispatcherOver(
        registry: ConsumerRegistry,
        through: JdbcTemplate = jdbc,
        manager: PlatformTransactionManager = this.manager,
        within: DeliveryProperties = DeliveryProperties(),
    ) = JdbcOutboxDispatcher(registry, through, manager, mapper, within)

    private fun registered(vararg consumers: EventConsumer): ConsumerRegistry =
        registryOf(*consumers).also { it.afterSingletonsInstantiated() }

    private fun registryOf(vararg consumers: EventConsumer) =
        ConsumerRegistry(consumers.toList(), jdbc, manager, clock, RegistrationProperties())

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

    /** Everything one log event would print: its message, and its throwable's class, message and causes. */
    private fun said(event: ILoggingEvent): String =
        event.formattedMessage + generateSequence(event.throwableProxy) { it.cause }.joinToString { " ${it.className}: ${it.message}" }

    /**
     * A transaction manager on which undoing a transaction succeeds and then
     * reports that it did not: the connection lost as the rollback was sent.
     * A template rolls back either by `rollback` or, for a transaction marked
     * rollback-only, by `commit`; both are covered.
     */
    private class RollbackThatFails(
        private val real: PlatformTransactionManager,
    ) : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = real.getTransaction(definition)

        override fun commit(status: TransactionStatus) {
            val undoing = status.isRollbackOnly
            real.commit(status)
            if (undoing) throw TransactionSystemException("connection reset during rollback")
        }

        override fun rollback(status: TransactionStatus) {
            real.rollback(status)
            throw TransactionSystemException("connection reset during rollback")
        }
    }

    /**
     * A [JdbcTemplate] on the same data source that runs [action] once, just
     * before the first statement containing [before]: something else getting
     * in between two particular statements, with no sleep and no luck.
     */
    private class InTheGap(
        dataSource: DataSource,
        private val before: String,
        private val action: () -> Unit,
    ) : JdbcTemplate(dataSource) {
        private var done = false

        override fun update(
            sql: String,
            vararg args: Any?,
        ): Int {
            intervene(sql)
            return super.update(sql, *args)
        }

        override fun <T : Any?> query(
            sql: String,
            rowMapper: RowMapper<T>,
            vararg args: Any?,
        ): List<T> {
            intervene(sql)
            return super.query(sql, rowMapper, *args)
        }

        private fun intervene(sql: String) {
            if (done || !sql.contains(before)) return
            done = true
            action()
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
        const val NESTED_ENTRY_LIKE = "and for walking home with me"
        const val WAIT_SECONDS = 10L
        const val DEFERRED_TABLE = "dispatcher_test_deferred"
        const val DEFERRED_REFUSAL = "dispatcher_test_deferred_refusal"
        const val OUTLASTING_ONE_SECOND_MS = 1_300L
        val ONE_SECOND = DeliveryProperties(deliveryTimeout = Duration.ofSeconds(1))
    }
}
