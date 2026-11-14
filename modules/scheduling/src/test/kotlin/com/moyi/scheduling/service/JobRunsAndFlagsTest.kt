package com.moyi.scheduling.service

import com.moyi.common.core.FeatureFlag
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.gratitude.api.EmbeddingRun
import com.moyi.scheduling.infra.RecordingEmbeddingSweep
import com.moyi.scheduling.infra.RecordingHeldPushes
import com.moyi.scheduling.infra.RecordingOnThisDay
import com.moyi.scheduling.infra.RecordingReminders
import com.moyi.scheduling.infra.SchedulingTestApplication
import com.moyi.scheduling.infra.SettableFlags
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * ADR-0067 decisions 2 and 7: the six jobs that did not record their finish in `job_runs` now do,
 * and the three flags each pause one job, which still counts the paused run as a run that finished.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
@Suppress("LongParameterList") // The six jobs, what each asked, the flags, the clock and the table: each named.
internal class JobRunsAndFlagsTest(
    @Autowired private val close: CloseJob,
    @Autowired private val reminders: ReminderJob,
    @Autowired private val onThisDay: OnThisDayJob,
    @Autowired private val held: HeldPushJob,
    @Autowired private val embedding: EmbeddingJob,
    @Autowired private val keys: ReapIdempotencyKeys,
    @Autowired private val recordedReminders: RecordingReminders,
    @Autowired private val recordedOnThisDay: RecordingOnThisDay,
    @Autowired private val recordedHeld: RecordingHeldPushes,
    @Autowired private val sweep: RecordingEmbeddingSweep,
    @Autowired private val flags: SettableFlags,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
) : PostgresIntegrationTest() {
    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.update("DELETE FROM job_runs")
        flags.reset()
        recordedReminders.reset()
        recordedOnThisDay.windows.clear()
        recordedHeld.calls.clear()
        sweep.reset()
    }

    @Test
    fun `each of the six jobs records its finish in job_runs`() {
        val at = Instant.parse("2026-11-14T10:00:00Z")
        clock.set(at)
        val jobs: Map<String, () -> Unit> =
            mapOf(
                CloseJob.LOCK to { close.closeOnce() },
                ReminderJob.LOCK to { reminders.remindOnce() },
                OnThisDayJob.LOCK to { onThisDay.pushOnce() },
                HeldPushJob.LOCK to { held.releaseOnce() },
                EmbeddingJob.LOCK to { embedding.embedOnce() },
                ReapIdempotencyKeys.LOCK to { keys.reapOnce() },
            )

        jobs.forEach { (name, run) ->
            withClue(name) {
                lastRun(name) shouldBe null
                run()
                lastRun(name) shouldBe at
            }
        }
    }

    @Test
    fun `an embedding run in which every call failed records no finish`() {
        sweep.next = EmbeddingRun(embedded = 0, skipped = 0, failed = true)

        embedding.embedOnce()

        lastRun(EmbeddingJob.LOCK) shouldBe null
    }

    @Test
    fun `with DAILY_REMINDERS off nobody is asked about, the run still finishes, and on again they are`() {
        clock.set(Instant.parse("2026-11-14T10:00:30Z"))
        flags.off(FeatureFlag.DAILY_REMINDERS)

        reminders.remindOnce().due shouldBe 0

        recordedReminders.windows.shouldBeEmpty()
        lastRun(ReminderJob.LOCK) shouldBe Instant.parse("2026-11-14T10:00:30Z")
        flags.reset()
        reminders.remindOnce()
        recordedReminders.windows.size shouldBe 1
    }

    @Test
    fun `with ON_THIS_DAY_PUSHES off nobody is asked about, and on again they are`() {
        flags.off(FeatureFlag.ON_THIS_DAY_PUSHES)

        onThisDay.pushOnce().due shouldBe 0

        recordedOnThisDay.windows.shouldBeEmpty()
        lastRun(OnThisDayJob.LOCK) shouldBe clock.instant()
        flags.reset()
        onThisDay.pushOnce()
        recordedOnThisDay.windows.size shouldBe 1
    }

    @Test
    fun `a reminder that fell due while DAILY_REMINDERS was off is never asked for once it is on, and one due after is`() {
        // Off from 09:40; at 10:02:30 switched on again. Without the bound, the 10:05:30 run would ask
        // about (09:35, 10:05] and send, late and at once, every reminder the pause held back.
        clock.set(Instant.parse("2026-11-14T09:40:30Z"))
        flags.off(FeatureFlag.DAILY_REMINDERS)
        reminders.remindOnce()
        flags.onAt(FeatureFlag.DAILY_REMINDERS, Instant.parse("2026-11-14T10:02:30Z"))
        clock.set(Instant.parse("2026-11-14T10:05:30Z"))

        reminders.remindOnce()

        recordedReminders.windows shouldBe
            listOf(Instant.parse("2026-11-14T10:02:30Z") to Instant.parse("2026-11-14T10:05:00Z"))
        // Half an hour on, the bound has passed out of the window: the usual six windows again.
        clock.set(Instant.parse("2026-11-14T10:40:30Z"))
        reminders.remindOnce()
        recordedReminders.windows.last() shouldBe
            (Instant.parse("2026-11-14T10:10:00Z") to Instant.parse("2026-11-14T10:40:00Z"))
    }

    @Test
    fun `an on-this-day push that fell due while its flag was off is never asked for once it is on`() {
        clock.set(Instant.parse("2026-11-14T09:20:30Z"))
        flags.off(FeatureFlag.ON_THIS_DAY_PUSHES)
        onThisDay.pushOnce()
        flags.onAt(FeatureFlag.ON_THIS_DAY_PUSHES, Instant.parse("2026-11-14T10:01:00Z"))
        // Its window is an hour, not the reminder's half hour: unbounded, the 10:15:45 run would ask
        // about (09:15, 10:15].
        clock.set(Instant.parse("2026-11-14T10:15:45Z"))

        onThisDay.pushOnce()

        recordedOnThisDay.windows.single() shouldBe
            (Instant.parse("2026-11-14T10:01:00Z") to Instant.parse("2026-11-14T10:15:00Z"))
    }

    @Test
    fun `the window starts at the switch only inside the look-back, and never after its own end`() {
        val upTo = Instant.parse("2026-11-14T10:05:00Z")
        val lookback = java.time.Duration.ofMinutes(30)

        windowStart(upTo, lookback, null) shouldBe Instant.parse("2026-11-14T09:35:00Z")
        windowStart(upTo, lookback, Instant.parse("2026-11-14T09:00:00Z")) shouldBe Instant.parse("2026-11-14T09:35:00Z")
        windowStart(upTo, lookback, Instant.parse("2026-11-14T09:50:00Z")) shouldBe Instant.parse("2026-11-14T09:50:00Z")
        windowStart(upTo, lookback, Instant.parse("2026-11-14T10:07:00Z")) shouldBe upTo
    }

    @Test
    fun `with AI_EMBEDDINGS off the sweep is not asked, and on again it is`() {
        flags.off(FeatureFlag.AI_EMBEDDINGS)

        embedding.embedOnce().embedded shouldBe 0

        sweep.budgets.shouldBeEmpty()
        lastRun(EmbeddingJob.LOCK) shouldBe clock.instant()
        flags.reset()
        embedding.embedOnce()
        sweep.budgets.size shouldBe 1
    }

    @Test
    fun `one flag off pauses only its own job`() {
        flags.off(FeatureFlag.DAILY_REMINDERS)

        onThisDay.pushOnce()
        embedding.embedOnce()

        recordedOnThisDay.windows.size shouldBe 1
        sweep.budgets.size shouldBe 1
    }

    private fun lastRun(name: String): Instant? =
        jdbc
            .queryForList(
                "SELECT last_success_at FROM job_runs WHERE name = ?",
                java.sql.Timestamp::class.java,
                name,
            ).firstOrNull()
            ?.toInstant()
}
