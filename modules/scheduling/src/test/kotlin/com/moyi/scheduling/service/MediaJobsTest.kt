package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.media.api.LifecycleRun
import com.moyi.scheduling.infra.RecordingMediaLifecycle
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant

/**
 * The two media lifecycle jobs (spec §4 D5, ADR-0050): when they run, under
 * which lock, how they batch, and what they count. What the reaper and the
 * demotion do to assets is `media`'s (`MediaLifecycleTest`); the stand-in
 * records what it was asked, on the clock the test moves.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class MediaJobsTest(
    @Autowired private val reaper: MediaReaperJob,
    @Autowired private val demotion: MediaDemotionJob,
    @Autowired private val media: RecordingMediaLifecycle,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val jdbc: JdbcTemplate,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second'")
        media.reset()
    }

    @Test
    fun `the reaper asks media once, on the test's clock, when one batch finds less than its limit`() {
        clock.set(NOW)
        media.answers += LifecycleRun(changed = 3, more = false)

        reaper.reapOnce() shouldBe 3

        media.reaps shouldBe listOf(NOW to BATCH)
        meters.find(MediaReaperJob.LAST_SUCCESS).gauge()!!.value() shouldBe NOW.epochSecond.toDouble()
    }

    @Test
    fun `a reaper batch that comes back full is followed by another, until one comes back short`() {
        clock.set(NOW)
        media.answers += listOf(LifecycleRun(BATCH, more = true), LifecycleRun(BATCH, more = true), LifecycleRun(7, more = false))
        val before = meters.find(MediaReaperJob.REAPED).counter()!!.count()

        reaper.reapOnce() shouldBe 2 * BATCH + 7

        media.reaps.size shouldBe 3
        meters.find(MediaReaperJob.REAPED).counter()!!.count() shouldBe before + 2 * BATCH + 7
    }

    @Test
    fun `a run stops at its budget however many batches are still full, and leaves the rest to the next`() {
        clock.set(NOW)
        repeat(RUN_BUDGET / BATCH + 5) { media.answers += LifecycleRun(BATCH, more = true) }

        demotion.demoteOnce() shouldBe RUN_BUDGET

        media.demotions.size shouldBe RUN_BUDGET / BATCH
    }

    @Test
    fun `the reaper is hourly and the demotion nightly in UTC, each under a lock of its own shorter than its interval`() {
        val reap = MediaReaperJob::class.java.getMethod("run")
        reap.getAnnotation(Scheduled::class.java).cron shouldBe "\${moyi.scheduling.media-reaper.cron:${MediaReaperJob.HOURLY}}"
        MediaReaperJob.HOURLY shouldBe "0 23 * * * *"
        reap.getAnnotation(SchedulerLock::class.java).name shouldBe MediaReaperJob.LOCK
        reap.getAnnotation(SchedulerLock::class.java).lockAtMostFor shouldBe "PT50M"

        val demote = MediaDemotionJob::class.java.getMethod("run")
        val scheduled = demote.getAnnotation(Scheduled::class.java)
        scheduled.cron shouldBe "\${moyi.scheduling.media-demotion.cron:${MediaDemotionJob.NIGHTLY}}"
        scheduled.zone shouldBe "UTC"
        MediaDemotionJob.NIGHTLY shouldBe "0 40 3 * * *"
        demote.getAnnotation(SchedulerLock::class.java).name shouldBe MediaDemotionJob.LOCK
        (MediaReaperJob.LOCK == MediaDemotionJob.LOCK) shouldBe false
    }

    @Test
    fun `while another instance holds a job's lock, that job asks media nothing`() {
        for (lock in listOf(MediaReaperJob.LOCK, MediaDemotionJob.LOCK)) {
            jdbc.update(
                "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                    "VALUES (?, $UTC_NOW + interval '50 minutes', $UTC_NOW, 'another-instance') " +
                    "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
                lock,
            )
        }

        reaper.run()
        demotion.run()

        media.reaps.shouldBeEmpty()
        media.demotions.shouldBeEmpty()
    }

    @Test
    fun `with no lock held, each job's timed entry point runs once and takes its lock`() {
        clock.set(NOW)

        reaper.run()
        demotion.run()

        media.reaps.size shouldBe 1
        media.demotions.size shouldBe 1
        jdbc.queryForObject(
            "SELECT count(*) FROM shedlock WHERE name IN (?, ?) AND lock_until > $UTC_NOW",
            Int::class.java,
            MediaReaperJob.LOCK,
            MediaDemotionJob.LOCK,
        ) shouldBe 2
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-29T03:40:00Z")

        /** The database's clock as ShedLock reads it (see `CloseJobTest`). */
        const val UTC_NOW = "timezone('utc', now())"
    }
}
