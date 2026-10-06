package com.moyi.scheduling.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.events.ConsumerBacklog
import com.moyi.common.events.DispatchResult
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.RecordingConsumer
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The poller, not the delivering: that a tick drains what is due, that its
 * meters say what the queue holds, and that nothing a tick meets can stop the
 * next one. `common:events`' own tests hold what a delivery is.
 *
 * The timer is off here, as it is for the close job; each test calls the job.
 * The first tests go through the bean Spring made, against the real outbox and
 * a consumer this context registered at start-up; the rest hand the job a
 * dispatcher that answers what the test tells it to, for the things a real one
 * cannot be made to do on demand.
 *
 * The database is shared with whatever else ran in this JVM, and a run
 * delivers everything due in it: so every delivery is removed before each
 * test, and the registration tables are never touched, because this context's
 * consumer was registered once and would not come back.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class OutboxJobTest(
    @Autowired private val job: OutboxJob,
    @Autowired private val consumer: RecordingConsumer,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val context: ApplicationContext,
    @Autowired manager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    private val transactions = TransactionTemplate(manager)
    private val now: Instant = Instant.parse("2026-10-06T12:00:00Z")

    private val logger = LoggerFactory.getLogger(OutboxJob::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries")
        consumer.reset()
        clock.set(now)
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `a tick hands a due event to its consumer and counts it`() {
        val delivered = count(OutboxJob.DELIVERED)
        val failed = count(OutboxJob.FAILED)
        val event = publish()

        job.run()

        consumer.received.map { it.aggregateId } shouldContainExactly listOf(event)
        count(OutboxJob.DELIVERED) - delivered shouldBe 1.0
        count(OutboxJob.FAILED) - failed shouldBe 0.0
    }

    @Test
    fun `a delivery whose handler throws is counted apart, and the tick ends quietly`() {
        val delivered = count(OutboxJob.DELIVERED)
        val failed = count(OutboxJob.FAILED)
        consumer.next = { throw IllegalStateException(ENTRY_LIKE) }
        publish()

        job.run()

        consumer.received shouldHaveSize 1
        count(OutboxJob.DELIVERED) - delivered shouldBe 0.0
        count(OutboxJob.FAILED) - failed shouldBe 1.0
        appender.list.forEach { it.formattedMessage shouldNotContain ENTRY_LIKE }
    }

    @Test
    fun `the pending and age gauges say what waits before a tick, and nothing after it`() {
        publish(at = now.minusSeconds(120))
        publish(at = now.minusSeconds(30))

        job.refreshMeters()
        gauge(OutboxJob.PENDING) shouldBe 2.0
        gauge(OutboxJob.AGE) shouldBe 120.0

        job.run()
        gauge(OutboxJob.PENDING) shouldBe 0.0
        gauge(OutboxJob.AGE) shouldBe 0.0

        // Registered once for the consumer, however many ticks there have been.
        listOf(OutboxJob.PENDING, OutboxJob.AGE, OutboxJob.FAILING).forEach { name ->
            meters.find(name).tag(OutboxJob.CONSUMER_TAG, RecordingConsumer.ID).gauges() shouldHaveSize 1
        }
    }

    @Test
    fun `the failing gauge counts a delivery that has failed five times, which is waiting and so not pending`() {
        val event = publish(at = now.minusSeconds(3_600))
        jdbc.update(
            "UPDATE outbox_deliveries SET attempts = 5, next_attempt_at = ?, last_error = 'java.lang.IllegalStateException' " +
                "WHERE consumer_id = ?",
            Timestamp.from(now.plusSeconds(64)),
            RecordingConsumer.ID,
        )

        job.run()

        consumer.received.shouldBeEmpty()
        gauge(OutboxJob.FAILING) shouldBe 1.0
        gauge(OutboxJob.PENDING) shouldBe 0.0
        // Its event is an hour old, and the age says so although nothing is due (doc 11's alert reads this).
        gauge(OutboxJob.AGE) shouldBe 3_600.0
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Int::class.java, event) shouldBe 1
    }

    @Test
    fun `a dispatcher that throws does not reach the scheduler, and is logged by class name alone`() {
        val registry = SimpleMeterRegistry()
        val thrown =
            listOf(
                IllegalStateException(ENTRY_LIKE, RuntimeException(ENTRY_LIKE)),
                // What the dispatcher rethrows once it has recorded the delivery's failure.
                StackOverflowError(ENTRY_LIKE),
            )
        thrown.forEach { failure ->
            val away = Scripted(dispatch = { throw failure })

            OutboxJob(away, clock, registry).run()

            away.dispatched shouldHaveSize 1
        }

        appender.list.map { it.level } shouldContainExactly listOf(Level.WARN, Level.WARN)
        appender.list[0].formattedMessage shouldContain "java.lang.IllegalStateException"
        appender.list[1].formattedMessage shouldContain "java.lang.StackOverflowError"
        appender.list.forEach { line ->
            line.formattedMessage shouldNotContain ENTRY_LIKE
            // No throwable handed to the logger: its stack trace would print the message, and the cause's.
            line.throwableProxy.shouldBeNull()
            line.argumentArray.forEach { it.toString() shouldNotContain ENTRY_LIKE }
        }
        registry.get(OutboxJob.DELIVERED).counter().count() shouldBe 0.0
    }

    @Test
    fun `a backlog that cannot be read does not reach the scheduler either, and what was delivered stays counted`() {
        val registry = SimpleMeterRegistry()
        val halfAway = Scripted(dispatch = { DispatchResult(delivered = 3, failed = 1, more = false) }, backlog = { error(ENTRY_LIKE) })

        OutboxJob(halfAway, clock, registry).run()

        registry.get(OutboxJob.DELIVERED).counter().count() shouldBe 3.0
        registry.get(OutboxJob.FAILED).counter().count() shouldBe 1.0
        appender.list.filter { it.level == Level.WARN } shouldHaveSize 1
        appender.list.forEach {
            it.formattedMessage shouldNotContain ENTRY_LIKE
            it.throwableProxy.shouldBeNull()
        }
    }

    @Test
    fun `while more is waiting a tick goes round again at once, with a fresh reading of the clock each time`() {
        val registry = SimpleMeterRegistry()
        val busy =
            Scripted(dispatch = { pass ->
                clock.advance(Duration.ofSeconds(1))
                DispatchResult(delivered = OutboxJob.BUDGET, failed = 0, more = pass < 3)
            })

        OutboxJob(busy, clock, registry).run()

        busy.dispatched shouldContainExactly
            listOf(now to OutboxJob.BUDGET, now.plusSeconds(1) to OutboxJob.BUDGET, now.plusSeconds(2) to OutboxJob.BUDGET)
        registry.get(OutboxJob.DELIVERED).counter().count() shouldBe 3.0 * OutboxJob.BUDGET
        // The meters are read once, when the tick is over.
        busy.backlogsAsked shouldBe 1
    }

    @Test
    fun `a dispatcher that always says more is waiting still lets the tick end`() {
        // It can: `more` stays true for as long as a failure cannot be recorded,
        // and a tick that never ended would be a thread spinning on one delivery.
        val registry = SimpleMeterRegistry()
        val endless = Scripted(dispatch = { DispatchResult(delivered = 0, failed = 1, more = true) })

        OutboxJob(endless, clock, registry).run()

        endless.dispatched shouldHaveSize OutboxJob.MAX_PASSES
        registry.get(OutboxJob.FAILED).counter().count() shouldBe OutboxJob.MAX_PASSES.toDouble()
        endless.backlogsAsked shouldBe 1
    }

    @Test
    fun `each consumer has gauges of its own, and one the backlog no longer reports reads nothing`() {
        val registry = SimpleMeterRegistry()
        var backlog =
            listOf(
                ConsumerBacklog("test.scheduling.first", pending = 4, failing = 1, oldestAgeSeconds = 900),
                ConsumerBacklog("test.scheduling.second", pending = 0, failing = 0, oldestAgeSeconds = 0),
            )
        val job = OutboxJob(Scripted(backlog = { backlog }), clock, registry)

        job.run()
        gauge(OutboxJob.PENDING, "test.scheduling.first", registry) shouldBe 4.0
        gauge(OutboxJob.FAILING, "test.scheduling.first", registry) shouldBe 1.0
        gauge(OutboxJob.AGE, "test.scheduling.first", registry) shouldBe 900.0
        gauge(OutboxJob.PENDING, "test.scheduling.second", registry) shouldBe 0.0

        backlog = listOf(ConsumerBacklog("test.scheduling.second", pending = 2, failing = 0, oldestAgeSeconds = 5))
        job.run()
        gauge(OutboxJob.PENDING, "test.scheduling.first", registry) shouldBe 0.0
        gauge(OutboxJob.FAILING, "test.scheduling.first", registry) shouldBe 0.0
        gauge(OutboxJob.AGE, "test.scheduling.first", registry) shouldBe 0.0
        gauge(OutboxJob.PENDING, "test.scheduling.second", registry) shouldBe 2.0
        registry.find(OutboxJob.PENDING).gauges() shouldHaveSize 2
    }

    @Test
    fun `the poller runs two seconds after it last finished, under no lock and in no transaction of its own`() {
        val run = OutboxJob::class.java.getMethod("run")
        // A delay, not a rate: a tick that takes long is not followed by a burst of the ticks it overran.
        run.getAnnotation(Scheduled::class.java).fixedDelayString shouldBe "\${moyi.scheduling.outbox.delay:PT2S}"
        // Decision 7: SKIP LOCKED is the concurrency control, and two instances polling is the design.
        run.getAnnotation(SchedulerLock::class.java).shouldBeNull()
        // The dispatcher opens a transaction per delivery and refuses to run inside another.
        OutboxJob::class.java.getAnnotation(Transactional::class.java).shouldBeNull()
        OutboxJob::class.java.declaredMethods.forEach { it.getAnnotation(Transactional::class.java).shouldBeNull() }
        OutboxJob.BUDGET shouldBe 200
    }

    @Test
    fun `nothing is on a timer in this context, the poller included`() {
        // `moyi.scheduling.enabled=false`, the same switch that keeps the close job from firing in a test.
        context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor::class.java).toList().shouldBeEmpty()
    }

    /** Publishes an event of this context's consumer's type, due at [at], and answers its aggregate id. */
    private fun publish(at: Instant = now): UUID {
        val aggregateId = UUID.randomUUID()
        transactions.executeWithoutResult {
            val references = mapOf("bondId" to UUID.randomUUID())
            publisher.publish(OutboxEvent("SchedulingTest", aggregateId, RecordingConsumer.EVENT_TYPE, references, at))
        }
        return aggregateId
    }

    private fun count(name: String): Double = meters.get(name).counter().count()

    private fun gauge(
        name: String,
        consumerId: String = RecordingConsumer.ID,
        registry: MeterRegistry = meters,
    ): Double =
        registry
            .get(name)
            .tag(OutboxJob.CONSUMER_TAG, consumerId)
            .gauge()
            .value()

    /** A dispatcher that answers what the test says, and remembers what it was asked. */
    private class Scripted(
        private val dispatch: (pass: Int) -> DispatchResult = { DispatchResult(0, 0, false) },
        private val backlog: () -> List<ConsumerBacklog> = { emptyList() },
    ) : OutboxDispatcher {
        val dispatched = mutableListOf<Pair<Instant, Int>>()
        var backlogsAsked = 0

        override fun dispatchDue(
            now: Instant,
            budget: Int,
        ): DispatchResult {
            dispatched += now to budget
            return dispatch(dispatched.size)
        }

        override fun backlog(now: Instant): List<ConsumerBacklog> {
            backlogsAsked++
            return backlog()
        }
    }

    private companion object {
        const val ENTRY_LIKE = "Thank you for the tea this morning"
    }
}
