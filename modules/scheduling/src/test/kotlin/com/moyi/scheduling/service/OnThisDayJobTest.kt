package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.RecordingOnThisDay
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The on-this-day job (FR-092, ADR-0046): the window, the timing and the
 * lock. Who opted in and who has a memory are `gratitude`'s and `bond`'s; the
 * stand-in records what it was asked.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class OnThisDayJobTest(
    @Autowired private val job: OnThisDayJob,
    @Autowired private val pushes: RecordingOnThisDay,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second'")
        pushes.windows.clear()
    }

    @Test
    fun `a run asks about the hour that ended on the last quarter-hour, however late it starts`() {
        clock.set(Instant.parse("2026-10-28T11:00:45Z"))
        job.pushOnce()
        clock.set(Instant.parse("2026-10-28T11:14:59Z"))
        job.pushOnce()
        clock.set(Instant.parse("2026-10-28T11:15:00Z"))
        job.pushOnce()

        pushes.windows shouldBe
            listOf(
                window("10:00", "11:00"),
                window("10:00", "11:00"),
                window("10:15", "11:15"),
            )
    }

    @Test
    fun `consecutive runs on the timer cover every minute of the day`() {
        val cron = CronExpression.parse(OnThisDayJob.EVERY_QUARTER_HOUR)
        val start = Instant.parse("2026-10-28T00:00:00Z").atZone(ZoneOffset.UTC)
        val windows =
            generateSequence(cron.next(start)) { cron.next(it) }
                .take(96)
                .map { fired ->
                    val upTo = OnThisDayJob.windowEndingBefore(fired.toInstant())
                    upTo.minus(OnThisDayJob.LOOKBACK) to upTo
                }.toList()

        windows.forEach { (after, upTo) -> Duration.between(after, upTo) shouldBe Duration.ofHours(1) }
        windows.zipWithNext().forEach { (a, b) -> b.first.isAfter(a.second) shouldBe false }
    }

    @Test
    fun `the job is timed every quarter-hour under a lock shorter than its interval`() {
        val run = OnThisDayJob::class.java.getMethod("run")
        run.getAnnotation(Scheduled::class.java).cron shouldBe "\${moyi.scheduling.on-this-day.cron:${OnThisDayJob.EVERY_QUARTER_HOUR}}"
        val lock = run.getAnnotation(SchedulerLock::class.java)
        lock.name shouldBe OnThisDayJob.LOCK
        lock.lockAtMostFor shouldBe "PT14M"
    }

    @Test
    fun `while another instance holds the lock nobody is asked about`() {
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, $UTC_NOW + interval '14 minutes', $UTC_NOW, 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
            OnThisDayJob.LOCK,
        )

        job.run()

        pushes.windows.shouldBeEmpty()
    }

    private fun window(
        after: String,
        upTo: String,
    ) = Instant.parse("2026-10-28T$after:00Z") to Instant.parse("2026-10-28T$upTo:00Z")

    private companion object {
        /** The database's clock as ShedLock reads it (see `CloseJobTest`). */
        const val UTC_NOW = "timezone('utc', now())"
    }
}
