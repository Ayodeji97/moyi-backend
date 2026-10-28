package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.RecordingHeldPushes
import com.moyi.scheduling.infra.RecordingReminders
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
import java.time.Instant
import java.time.ZoneOffset

/**
 * The reminder job and the release of held pushes (spec §4 N2, ADR-0045): the
 * job's part is the window, the timing and the lock. Whose reminder falls is
 * `gratitude`'s and `bond`'s; the stand-ins here record what they were asked.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class ReminderJobTest(
    @Autowired private val job: ReminderJob,
    @Autowired private val release: HeldPushJob,
    @Autowired private val reminders: RecordingReminders,
    @Autowired private val held: RecordingHeldPushes,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second'")
        reminders.reset()
        held.calls.clear()
    }

    @Test
    fun `a run asks about the five minutes that ended on the last boundary, however late it starts`() {
        clock.set(Instant.parse("2026-10-28T20:00:30Z"))
        job.remindOnce()
        clock.set(Instant.parse("2026-10-28T20:04:59Z"))
        job.remindOnce()
        clock.set(Instant.parse("2026-10-28T20:05:00Z"))
        job.remindOnce()

        reminders.windows shouldBe
            listOf(
                window("19:55", "20:00"),
                window("19:55", "20:00"),
                window("20:00", "20:05"),
            )
    }

    @Test
    fun `consecutive runs on the timer cover every minute of the day once, with no gap and no overlap`() {
        val cron = CronExpression.parse(ReminderJob.EVERY_FIVE_MINUTES)
        val start = Instant.parse("2026-10-28T00:00:00Z").atZone(ZoneOffset.UTC)
        val windows =
            generateSequence(cron.next(start)) { cron.next(it) }
                .take(288)
                .map { fired ->
                    val upTo = ReminderJob.windowEndingBefore(fired.toInstant())
                    upTo.minus(ReminderJob.WINDOW) to upTo
                }.toList()

        windows.zipWithNext().forEach { (a, b) -> b.first shouldBe a.second }
        windows.first().first shouldBe Instant.parse("2026-10-27T23:55:00Z")
        windows.last().second shouldBe Instant.parse("2026-10-28T23:55:00Z")
    }

    @Test
    fun `the job is timed every five minutes, just after the minute, under a lock shorter than its interval`() {
        val scheduled = ReminderJob::class.java.getMethod("run").getAnnotation(Scheduled::class.java)
        scheduled.cron shouldBe "\${moyi.scheduling.reminders.cron:${ReminderJob.EVERY_FIVE_MINUTES}}"
        val lock = ReminderJob::class.java.getMethod("run").getAnnotation(SchedulerLock::class.java)
        lock.name shouldBe ReminderJob.LOCK
        lock.lockAtMostFor shouldBe "PT4M"
    }

    @Test
    fun `while another instance holds the lock the reminders are not asked for`() {
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, $UTC_NOW + interval '4 minutes', $UTC_NOW, 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
            ReminderJob.LOCK,
        )

        job.run()

        reminders.windows.shouldBeEmpty()
    }

    @Test
    fun `the release asks for every push whose hold has ended by now`() {
        clock.set(Instant.parse("2026-10-29T06:00:15Z"))

        release.run()

        held.calls shouldBe listOf(Instant.parse("2026-10-29T06:00:15Z") to HeldPushJob.LIMIT)
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
