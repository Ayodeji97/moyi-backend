package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.api.CloseRun
import com.moyi.scheduling.api.JobConsole
import com.moyi.scheduling.infra.RecordingCloser
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The jobs page's port (ADR-0067 decisions 1 to 5): the registry is every locked job, the page reads
 * ShedLock's row and `job_runs`, and the close job run by hand runs under the timer's own lock, after
 * the caller's `beforeRun`, or not at all.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class JobConsoleTest(
    @Autowired private val console: JobConsole,
    @Autowired private val closer: RecordingCloser,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val context: ApplicationContext,
) : PostgresIntegrationTest() {
    /**
     * Locks are released, never deleted: ShedLock remembers which names it has inserted and only
     * updates their rows after that, so a deleted row is a lock it can never take again in this context.
     */
    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second', locked_at = $UTC_NOW - interval '1 hour'")
        jdbc.update("DELETE FROM job_runs")
        closer.reset()
    }

    @Test
    fun `the registry lists every job that runs under a ShedLock lock, and nothing else`() {
        val annotated =
            context.beanDefinitionNames
                .map { AopUtils.getTargetClass(context.getBean(it)) }
                .filter { it.packageName.startsWith("com.moyi.scheduling") }
                .flatMap { type -> type.declaredMethods.mapNotNull { it.getAnnotation(SchedulerLock::class.java)?.name } }

        annotated.size shouldBe 11
        console.jobs().map { it.name } shouldContainExactlyInAnyOrder annotated
    }

    @Test
    fun `a job never run shows nothing, and the schedule is the configured one`() {
        val rows = jdbc.queryForList("SELECT * FROM shedlock WHERE name = ?", MediaDemotionJob.LOCK)
        jdbc.update("DELETE FROM shedlock WHERE name = ?", MediaDemotionJob.LOCK)
        try {
            val demotion = console.jobs().single { it.name == MediaDemotionJob.LOCK }

            demotion.lastStartedAt shouldBe null
            demotion.lockedUntil shouldBe null
            demotion.lastFinishedAt shouldBe null
            demotion.running shouldBe false
            demotion.schedule shouldBe MediaDemotionJob.NIGHTLY
            console.timerOn() shouldBe false
        } finally {
            rows.forEach {
                jdbc.update(
                    "INSERT INTO shedlock VALUES (?, ?, ?, ?)",
                    it["name"],
                    it["lock_until"],
                    it["locked_at"],
                    it["locked_by"],
                )
            }
        }
    }

    @Test
    fun `a lock held and started after the last finish is running, and one released or older than the finish is not`() {
        lock(ReminderJob.LOCK, startedAgo = "2 minutes", untilFromNow = "2 minutes")
        finished(ReminderJob.LOCK, ago = "10 minutes")
        lock(HeldPushJob.LOCK, startedAgo = "2 minutes", untilFromNow = "-1 second")
        lock(EmbeddingJob.LOCK, startedAgo = "2 minutes", untilFromNow = "2 minutes")
        finished(EmbeddingJob.LOCK, ago = "1 minute")

        val jobs = console.jobs().associateBy { it.name }

        jobs.getValue(ReminderJob.LOCK).running shouldBe true
        jobs.getValue(HeldPushJob.LOCK).running shouldBe false
        jobs.getValue(EmbeddingJob.LOCK).running shouldBe false
    }

    @Test
    fun `ShedLock's zoneless UTC row is read as the instant it means, whatever the session's zone`() {
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, '2026-11-14 10:14:00', '2026-11-14 10:00:00', 'x') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_at = EXCLUDED.locked_at",
            CloseJob.LOCK,
        )
        jdbc.update("INSERT INTO job_runs (name, last_success_at) VALUES (?, '2026-11-14T09:45:00Z')", CloseJob.LOCK)

        val close = console.jobs().single { it.name == CloseJob.LOCK }

        close.lastStartedAt shouldBe Instant.parse("2026-11-14T10:00:00Z")
        close.lockedUntil shouldBe Instant.parse("2026-11-14T10:14:00Z")
        close.lastFinishedAt shouldBe Instant.parse("2026-11-14T09:45:00Z")
    }

    @Test
    fun `a run by hand calls beforeRun under the lock, then the closer once, and answers its counts`() {
        clock.set(Instant.parse("2026-11-14T10:05:00Z"))
        val order = CopyOnWriteArrayList<String>()
        closer.next = {
            order += "closer"
            RecordingCloser.NOTHING.copy(created = 1, closed = 2, revealed = 3, evaluated = 4, failed = 5, backlog = true)
        }

        val run =
            console.runCloseNow {
                order += "before:" + heldBy()
            }

        order shouldBe listOf("before:held", "closer")
        val ran = run.shouldBeInstanceOf<CloseRun.Ran>()
        listOf(ran.created, ran.closed, ran.revealed, ran.evaluated, ran.failed) shouldBe listOf(1, 2, 3, 4, 5)
        ran.backlog shouldBe true
        closer.calls shouldBe listOf(Instant.parse("2026-11-14T10:05:00Z") to CloseJob.BUDGET)
        console.jobs().single { it.name == CloseJob.LOCK }.lastFinishedAt shouldBe Instant.parse("2026-11-14T10:05:00Z")
        // Released at once: lockAtLeastFor is zero, so the timer's next quarter is not kept waiting.
        heldBy() shouldBe "free"
    }

    @Test
    fun `while the timer or another instance holds the lock, nothing is called and the answer is busy`() {
        lock(CloseJob.LOCK, startedAgo = "1 minute", untilFromNow = "13 minutes")
        var before = 0

        console.runCloseNow { before++ } shouldBe CloseRun.Busy

        before shouldBe 0
        closer.calls shouldBe emptyList()
    }

    @Test
    fun `a beforeRun that throws stops the run and frees the lock`() {
        shouldThrow<IllegalStateException> { console.runCloseNow { error("the audit row could not be written") } }

        closer.calls shouldBe emptyList()
        heldBy() shouldBe "free"
        console.runCloseNow { }.shouldBeInstanceOf<CloseRun.Ran>()
    }

    private fun heldBy(): String =
        jdbc
            .queryForList(
                "SELECT lock_until > $UTC_NOW AS held FROM shedlock WHERE name = ?",
                Boolean::class.java,
                CloseJob.LOCK,
            ).firstOrNull()
            .let { if (it == true) "held" else "free" }

    private fun lock(
        name: String,
        startedAgo: String,
        untilFromNow: String,
    ) {
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, $UTC_NOW + interval '$untilFromNow', $UTC_NOW - interval '$startedAgo', 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_at = EXCLUDED.locked_at, " +
                "locked_by = EXCLUDED.locked_by",
            name,
        )
    }

    private fun finished(
        name: String,
        ago: String,
    ) {
        jdbc.update("INSERT INTO job_runs (name, last_success_at) VALUES (?, now() - interval '$ago')", name)
    }

    private companion object {
        const val UTC_NOW = "timezone('utc', now())"
    }
}
