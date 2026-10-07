package com.moyi.scheduling.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.RecordingCloser
import com.moyi.scheduling.infra.RecordingConsumer
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The one thing the poller must never do to the rest of this module: keep the
 * close job from firing. A handler can hang (a lock that is never released, for
 * as long as its delivery is allowed), and every day of every couple closes on
 * the close job's trigger.
 *
 * **The timer is on here, and it is the timer that is on trial**: nobody calls
 * a job. The close job is given a trigger every second; a handler of the
 * outbox is held; the close job must still be called.
 *
 * **Twice, because the answer used to depend on a line of configuration.**
 * With `spring.threads.virtual.enabled` Spring Boot schedules on a
 * `SimpleAsyncTaskScheduler` that gives each cron run a thread of its own;
 * without it, on a `ThreadPoolTaskScheduler` with one thread, which a tick
 * that runs on it and does not return keeps from everything else. The
 * application sets the property, for its web server; nothing tied the close
 * job to that choice. Each test says which scheduler it found, so that neither
 * can pass by testing the other's.
 *
 * Each context is built and closed by its test, not cached by Spring's test
 * support: the timer must be stopped before the close job's lock is let go, or
 * a last run would leave it held for whichever test class comes next.
 */
internal class PollerIndependenceTest : PostgresIntegrationTest() {
    @Test
    fun `with virtual threads, a held outbox handler does not keep the close job from firing`() {
        aHeldHandlerDoesNotStopTheCloseJob(virtualThreads = true, scheduler = SimpleAsyncTaskScheduler::class)
    }

    @Test
    fun `without virtual threads, a held outbox handler does not keep the close job from firing`() {
        aHeldHandlerDoesNotStopTheCloseJob(virtualThreads = false, scheduler = ThreadPoolTaskScheduler::class)
    }

    private fun aHeldHandlerDoesNotStopTheCloseJob(
        virtualThreads: Boolean,
        scheduler: KClass<out TaskScheduler>,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedWhileHeld = CountDownLatch(1)
        val context = start(virtualThreads)
        try {
            context.getBean(TaskScheduler::class.java)::class shouldBe scheduler
            val jdbc = context.getBean(JdbcTemplate::class.java)
            jdbc.execute("TRUNCATE TABLE outbox_deliveries")
            context.getBean(RecordingConsumer::class.java).next = {
                entered.countDown()
                check(release.await(HELD_SECONDS, TimeUnit.SECONDS)) { "the test never released the handler" }
            }
            publish(context)
            // Nobody called the job: the timer did.
            entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            // From here on the handler is held. The close job last ran at most a second ago and
            // its lock is kept for thirty; let it lapse, as time would, and see the next trigger call it.
            context.getBean(RecordingCloser::class.java).next = {
                closedWhileHeld.countDown()
                RecordingCloser.NOTHING
            }
            releaseLocks(jdbc)

            closedWhileHeld.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            // And it was held throughout: the close job did not simply wait its turn.
            release.count shouldBe 1
        } finally {
            release.countDown()
            context.close()
            releaseLocks(JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)))
        }
    }

    /** This module's test context with the timer on: the poller ten times a second, the close job every second. */
    private fun start(virtualThreads: Boolean): ConfigurableApplicationContext =
        SpringApplicationBuilder(SchedulingTestApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.threads.virtual.enabled=$virtualThreads",
                "--moyi.scheduling.enabled=true",
                "--moyi.scheduling.outbox.delay=PT0.1S",
                "--moyi.scheduling.close.cron=* * * * * *",
            )

    private fun publish(context: ConfigurableApplicationContext) {
        val publisher = context.getBean(EventPublisher::class.java)
        val at = context.getBean(Clock::class.java).instant()
        TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
            val references = mapOf("bondId" to UUID.randomUUID())
            publisher.publish(OutboxEvent("SchedulingTest", UUID.randomUUID(), RecordingConsumer.EVENT_TYPE, references, at))
        }
    }

    /** Lets every lock lapse, as time passing would (never a delete: see `CloseJobTest.releaseLocks`). */
    private fun releaseLocks(jdbc: JdbcTemplate) {
        jdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second'")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val HELD_SECONDS = 60L
    }
}
