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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.awaitility.Awaitility.await
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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The poller, not the delivering: that a tick drains what is due, that its
 * meters say what the queue holds, and that nothing a tick meets can stop the
 * next one. `common:events`' own tests hold what a delivery is.
 *
 * The timer is off here, as it is for the close job; each test calls the job,
 * and calls the tick itself ([OutboxJob.dispatchOnce]) so that it is over when
 * the call returns. What the timer calls only hands a tick over; the tests of
 * that say so, and [PollerIndependenceTest] runs the timer for real.
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

        job.dispatchOnce()

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

        job.dispatchOnce()

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

        job.dispatchOnce()
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

        job.dispatchOnce()

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

            OutboxJob(away, clock, registry).dispatchOnce()

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

        OutboxJob(halfAway, clock, registry).dispatchOnce()

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

        OutboxJob(busy, clock, registry).dispatchOnce()

        busy.dispatched shouldContainExactly
            listOf(now to OutboxJob.BUDGET, now.plusSeconds(1) to OutboxJob.BUDGET, now.plusSeconds(2) to OutboxJob.BUDGET)
        registry.get(OutboxJob.DELIVERED).counter().count() shouldBe 3.0 * OutboxJob.BUDGET
        // The meters are read once, when the tick is over.
        busy.backlogsAsked shouldBe 1
    }

    @Test
    fun `a dispatcher whose every pass fills its budget still lets the tick end, and the tick says more is waiting`() {
        // A tick that looped for as long as there was more would never pause, and a
        // queue fed faster than it drains would hold the poller's thread for good.
        val registry = SimpleMeterRegistry()
        val endless = Scripted(dispatch = { DispatchResult(delivered = OutboxJob.BUDGET - 1, failed = 1, more = true) })

        OutboxJob(endless, clock, registry).dispatchOnce()

        endless.dispatched shouldHaveSize OutboxJob.MAX_PASSES
        registry.get(OutboxJob.FAILED).counter().count() shouldBe OutboxJob.MAX_PASSES.toDouble()
        endless.backlogsAsked shouldBe 1
        // A backlog is something to be told about, as the close job's is.
        val said = appender.list.single()
        said.level shouldBe Level.WARN
        said.formattedMessage shouldContain "more is waiting after ${OutboxJob.MAX_PASSES} passes"
        said.formattedMessage shouldContain "delivered ${(OutboxJob.BUDGET - 1) * OutboxJob.MAX_PASSES}"
        said.formattedMessage shouldContain "failed ${OutboxJob.MAX_PASSES}"
    }

    @Test
    fun `a pass that ended short of its budget is not followed by another, though more is waiting`() {
        // What a failure that cannot be recorded looks like from here: the pass
        // set its delivery aside and stopped with nothing else to do, and the
        // delivery is still due. Going round again would run that handler again,
        // ten times a tick, with no backoff, and deliver nothing.
        val registry = SimpleMeterRegistry()
        val unrecordable = Scripted(dispatch = { DispatchResult(delivered = 0, failed = 1, more = true) })

        OutboxJob(unrecordable, clock, registry).dispatchOnce()

        unrecordable.dispatched shouldHaveSize 1
        registry.get(OutboxJob.FAILED).counter().count() shouldBe 1.0
        // Still said: it is waiting, and the next tick is two seconds off.
        appender.list.single().formattedMessage shouldContain "more is waiting after 1 passes"
    }

    @Test
    fun `what a pass delivered is counted before the next pass begins, so a pass that throws loses nothing`() {
        val registry = SimpleMeterRegistry()
        var countedWhenTheSecondPassBegan: Double? = null
        val failsOnTheSecondPass =
            Scripted(dispatch = { pass ->
                if (pass == 2) {
                    countedWhenTheSecondPassBegan = registry.get(OutboxJob.DELIVERED).counter().count()
                    error(ENTRY_LIKE)
                }
                DispatchResult(delivered = OutboxJob.BUDGET - 2, failed = 2, more = true)
            })

        OutboxJob(failsOnTheSecondPass, clock, registry).dispatchOnce()

        failsOnTheSecondPass.dispatched shouldHaveSize 2
        countedWhenTheSecondPassBegan shouldBe (OutboxJob.BUDGET - 2).toDouble()
        registry.get(OutboxJob.DELIVERED).counter().count() shouldBe (OutboxJob.BUDGET - 2).toDouble()
        registry.get(OutboxJob.FAILED).counter().count() shouldBe 2.0
    }

    @Test
    fun `the gauges say what the queue holds after a tick that threw, tick after tick`() {
        // The poller is stuck exactly when its dispatcher throws every time, and
        // that is when the gauges are read by whoever was paged. Set only by a
        // tick that finished, they would show the last quiet moment for ever.
        val registry = SimpleMeterRegistry()
        var dispatch: () -> DispatchResult = { DispatchResult(0, 0, false) }
        var backlog = listOf(ConsumerBacklog("test.scheduling.first", pending = 0, failing = 0, oldestAgeSeconds = 0))
        val job = OutboxJob(Scripted(dispatch = { dispatch() }, backlog = { backlog }), clock, registry)
        job.dispatchOnce()

        dispatch = { error(ENTRY_LIKE) }
        backlog =
            listOf(
                ConsumerBacklog("test.scheduling.first", pending = 5_000, failing = 40, oldestAgeSeconds = 86_400),
                // First seen only once the trouble had begun.
                ConsumerBacklog("test.scheduling.second", pending = 7, failing = 0, oldestAgeSeconds = 60),
            )
        repeat(3) { job.dispatchOnce() }

        gauge(OutboxJob.PENDING, "test.scheduling.first", registry) shouldBe 5_000.0
        gauge(OutboxJob.FAILING, "test.scheduling.first", registry) shouldBe 40.0
        gauge(OutboxJob.AGE, "test.scheduling.first", registry) shouldBe 86_400.0
        gauge(OutboxJob.PENDING, "test.scheduling.second", registry) shouldBe 7.0
        // One line a tick, the tick's own: reading the backlog afterwards added nothing and quoted nothing.
        appender.list shouldHaveSize 3
        appender.list.forEach {
            it.formattedMessage shouldContain "the tick did not finish"
            it.formattedMessage shouldNotContain ENTRY_LIKE
        }
    }

    @Test
    fun `the last-success meter moves with every tick whose backlog was read, and stops when it cannot be`() {
        val registry = SimpleMeterRegistry()
        var backlog: () -> List<ConsumerBacklog> = { listOf(ConsumerBacklog("test.scheduling.first", 3, 0, 30)) }
        var dispatch: () -> DispatchResult = { DispatchResult(0, 0, false) }
        val job = OutboxJob(Scripted(dispatch = { dispatch() }, backlog = { backlog() }), clock, registry)
        // Before any tick it says so: zero is 1970, which no alert on staleness can miss.
        lastSuccess(registry) shouldBe 0.0

        job.dispatchOnce()
        lastSuccess(registry) shouldBe now.epochSecond.toDouble()

        // A tick whose dispatcher threw still read the backlog, so the gauges are true and this says they are.
        clock.advance(Duration.ofSeconds(2))
        dispatch = { error(ENTRY_LIKE) }
        job.dispatchOnce()
        lastSuccess(registry) shouldBe now.plusSeconds(2).epochSecond.toDouble()

        // The backlog cannot be read: the gauges keep what they had, and only this meter shows that they are old.
        clock.advance(Duration.ofSeconds(2))
        dispatch = { DispatchResult(0, 0, false) }
        backlog = { error(ENTRY_LIKE) }
        appender.list.clear()
        job.dispatchOnce()

        lastSuccess(registry) shouldBe now.plusSeconds(2).epochSecond.toDouble()
        gauge(OutboxJob.PENDING, "test.scheduling.first", registry) shouldBe 3.0
        val said = appender.list.single()
        said.level shouldBe Level.WARN
        said.formattedMessage shouldContain "the backlog could not be read"
        said.formattedMessage shouldContain "java.lang.IllegalStateException"
        said.formattedMessage shouldNotContain ENTRY_LIKE
        said.throwableProxy.shouldBeNull()

        // Readable again, and it catches up.
        clock.advance(Duration.ofSeconds(2))
        backlog = { emptyList() }
        job.dispatchOnce()
        lastSuccess(registry) shouldBe now.plusSeconds(6).epochSecond.toDouble()
    }

    @Test
    fun `the timer's call hands the tick to the job's own thread and returns, and skips while that tick is running`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<Thread>()
        val held =
            Scripted(dispatch = { pass ->
                threads += Thread.currentThread()
                if (pass == 1) {
                    entered.countDown()
                    check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the tick" }
                }
                DispatchResult(0, 0, false)
            })
        val job = OutboxJob(held, clock, SimpleMeterRegistry())
        try {
            // Returns although the tick it started cannot: were the tick run here, this line would not be passed.
            job.run()
            entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            threads.single() shouldNotBe Thread.currentThread()
            threads.single().name shouldBe OutboxJob.WORKER_THREAD

            // Ticks that fall due meanwhile are dropped, not queued behind it; and it is said once, not every two seconds.
            repeat(3) { job.run() }
            held.dispatched shouldHaveSize 1
            appender.list.map { it.level to it.formattedMessage } shouldContainExactly
                listOf(Level.WARN to "outbox: the last tick is still running; none is started until it ends")

            release.countDown()
            // The tick is over when its thread says so; until then the timer's calls are still skipped.
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until {
                job.run()
                held.dispatched.size >= 2
            }
            threads.map { it.name }.toSet() shouldContainExactly setOf(OutboxJob.WORKER_THREAD)
        } finally {
            release.countDown()
            job.destroy()
        }
    }

    @Test
    fun `an Error as the tick is handed over is named and not thrown at the scheduler, and the next tick runs`() {
        // What `ThreadPoolExecutor.execute` does when the machine has no thread to give: an Error, not a refusal.
        val handOvers = AtomicInteger()
        val noThreadOnce =
            object : AbstractExecutorService() {
                override fun execute(tick: Runnable) {
                    if (handOvers.incrementAndGet() == 1) throw OutOfMemoryError(ENTRY_LIKE)
                    tick.run()
                }

                override fun shutdown() = Unit

                override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

                override fun isShutdown() = false

                override fun isTerminated() = true

                override fun awaitTermination(
                    timeout: Long,
                    unit: TimeUnit,
                ) = true
            }
        val scripted = Scripted()
        val job = OutboxJob(scripted, clock, SimpleMeterRegistry(), noThreadOnce)

        // Caught here and not left to JUnit, which treats an escaping OutOfMemoryError as the end of the run.
        runCatching { job.run() }.exceptionOrNull().shouldBeNull()

        scripted.dispatched.shouldBeEmpty()
        val warning = appender.list.single()
        warning.level shouldBe Level.WARN
        warning.formattedMessage shouldContain "error=java.lang.OutOfMemoryError"
        warning.formattedMessage shouldNotContain ENTRY_LIKE
        warning.throwableProxy.shouldBeNull()

        // The tick that never began is not "still running": the next one is handed over and runs.
        job.run()
        job.run()
        scripted.dispatched shouldHaveSize 2
        appender.list shouldHaveSize 1
    }

    @Test
    fun `stopping interrupts the tick in flight, waits for it to end, and starts no other`() {
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val held =
            Scripted(dispatch = {
                worker.set(Thread.currentThread())
                entered.countDown()
                // Interruptible, as a handler waiting on the database is; the dispatcher passes the interrupt on as a failure.
                never.await(WAIT_SECONDS, TimeUnit.SECONDS)
                DispatchResult(delivered = OutboxJob.BUDGET, failed = 0, more = true)
            })
        val job = OutboxJob(held, clock, SimpleMeterRegistry())
        job.run()
        entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

        job.destroy()

        // Over by the time `destroy` returns: the connection pool is closed next, and the tick must not still be using it.
        worker.get().isAlive shouldBe false
        held.dispatched shouldHaveSize 1
        appender.list.single().formattedMessage shouldContain "error=java.lang.InterruptedException"

        // The timer may fire once more as the context goes down: nothing runs, and nothing is thrown at the scheduler.
        job.run()
        held.dispatched shouldHaveSize 1
    }

    @Test
    fun `a job told to stop begins no further pass, however much is waiting`() {
        val registry = SimpleMeterRegistry()
        lateinit var job: OutboxJob
        val busy =
            Scripted(dispatch = {
                // Told to stop while this pass was running.
                job.destroy()
                DispatchResult(delivered = OutboxJob.BUDGET, failed = 0, more = true)
            })
        job = OutboxJob(busy, clock, registry)

        job.dispatchOnce()

        busy.dispatched shouldHaveSize 1
        registry.get(OutboxJob.DELIVERED).counter().count() shouldBe OutboxJob.BUDGET.toDouble()
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

        job.dispatchOnce()
        gauge(OutboxJob.PENDING, "test.scheduling.first", registry) shouldBe 4.0
        gauge(OutboxJob.FAILING, "test.scheduling.first", registry) shouldBe 1.0
        gauge(OutboxJob.AGE, "test.scheduling.first", registry) shouldBe 900.0
        gauge(OutboxJob.PENDING, "test.scheduling.second", registry) shouldBe 0.0

        backlog = listOf(ConsumerBacklog("test.scheduling.second", pending = 2, failing = 0, oldestAgeSeconds = 5))
        job.dispatchOnce()
        gauge(OutboxJob.PENDING, "test.scheduling.first", registry) shouldBe 0.0
        gauge(OutboxJob.FAILING, "test.scheduling.first", registry) shouldBe 0.0
        gauge(OutboxJob.AGE, "test.scheduling.first", registry) shouldBe 0.0
        gauge(OutboxJob.PENDING, "test.scheduling.second", registry) shouldBe 2.0
        registry.find(OutboxJob.PENDING).gauges() shouldHaveSize 2
    }

    @Test
    fun `the poller is called two seconds after its last call returned, under no lock and in no transaction of its own`() {
        val run = OutboxJob::class.java.getMethod("run")
        // A delay, not a rate, and counted from the hand-over, which is all the call does: a tick still
        // running two seconds later is not joined by another, because the call that would start one is skipped.
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

    private fun lastSuccess(registry: MeterRegistry): Double = registry.get(OutboxJob.LAST_SUCCESS).gauge().value()

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
        val dispatched = CopyOnWriteArrayList<Pair<Instant, Int>>()
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
        const val WAIT_SECONDS = 10L
    }
}
