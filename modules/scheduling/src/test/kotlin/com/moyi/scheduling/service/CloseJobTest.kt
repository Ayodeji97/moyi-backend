package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.RecordingCloser
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
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
import java.util.UUID

/**
 * The job, not the closing: that it runs when it should, that only one
 * instance does, and that its two meters say what they are for. The closer is
 * a stand-in that records what it was asked.
 *
 * The timer is off here; each test calls the job. [CloseJob.run] is called
 * through Spring's proxy, so the lock is really taken, against a real table.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class CloseJobTest(
    @Autowired private val job: CloseJob,
    @Autowired private val reaper: ReapIdempotencyKeys,
    @Autowired private val closer: RecordingCloser,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        releaseLocks()
        jdbc.execute("TRUNCATE TABLE idempotency_keys")
        closer.reset()
    }

    @Test
    fun `a run asks the closer what has ended by the clock, and both meters move`() {
        val settledBefore = settled()
        clock.set(Instant.parse("2026-09-15T23:00:00Z"))
        closer.next = { RecordingCloser.NOTHING.copy(created = 2, closed = 3, revealed = 1, bondsChanged = setOf(UUID.randomUUID())) }

        job.run()

        closer.calls shouldBe listOf(Instant.parse("2026-09-15T23:00:00Z") to CloseJob.BUDGET)
        settled() - settledBefore shouldBe 6.0
        lastSuccess() shouldBe Instant.parse("2026-09-15T23:00:00Z").epochSecond.toDouble()
    }

    @Test
    fun `a run that finds nothing still says it ran, and counts nothing`() {
        val settledBefore = settled()
        clock.set(Instant.parse("2026-09-16T03:15:00Z"))

        job.run()

        closer.calls.size shouldBe 1
        settled() - settledBefore shouldBe 0.0
        // Doc 11: set on EVERY run, or a quiet night looks like a stopped job.
        lastSuccess() shouldBe Instant.parse("2026-09-16T03:15:00Z").epochSecond.toDouble()
    }

    @Test
    fun `while another instance holds the lock the job does not run`() {
        val before = lastSuccess()
        lockHeld(CloseJob.LOCK, until = "$UTC_NOW + interval '10 minutes'")

        job.run()

        closer.calls shouldBe emptyList()
        lastSuccess() shouldBe before
    }

    @Test
    fun `a lock another instance left behind when it died is taken once it has lapsed`() {
        lockHeld(CloseJob.LOCK, until = "$UTC_NOW - interval '1 second'")

        job.run()

        closer.calls.size shouldBe 1
    }

    @Test
    fun `a run that fails does not claim to have succeeded`() {
        clock.set(Instant.parse("2026-09-15T23:00:00Z"))
        job.run()
        releaseLocks()
        clock.set(Instant.parse("2026-09-15T23:15:00Z"))
        closer.next = { error("the database went away") }

        shouldThrow<IllegalStateException> { job.run() }

        lastSuccess() shouldBe Instant.parse("2026-09-15T23:00:00Z").epochSecond.toDouble()
    }

    @Test
    fun `days that could not be settled are counted apart from days that were`() {
        val failedBefore = failed()
        val settledBefore = settled()
        closer.next = { RecordingCloser.NOTHING.copy(closed = 4, failed = 2) }

        job.run()

        failed() - failedBefore shouldBe 2.0
        settled() - settledBefore shouldBe 4.0
    }

    @Test
    fun `the job is timed a minute past every quarter-hour, which is what a forty-five minute offset needs`() {
        val scheduled = CloseJob::class.java.getMethod("run").getAnnotation(Scheduled::class.java)
        // The annotation reads a property and defaults to the constant; nothing sets the property outside the smoke run.
        scheduled.cron shouldBe "\${moyi.scheduling.close.cron:${CloseJob.EVERY_FIFTEEN_MINUTES}}"
        val cron = CronExpression.parse(CloseJob.EVERY_FIFTEEN_MINUTES)
        val start = Instant.parse("2026-09-15T18:01:00Z").atZone(ZoneOffset.UTC)

        val next = generateSequence(cron.next(start)) { cron.next(it) }.take(5).map { it.toInstant() }.toList()

        // 18:15Z is midnight in Kathmandu (+5:45). A minute past each quarter:
        // the closer leaves a day alone for its first minute over, and fired
        // on the quarter itself every day would wait fifteen for the next run.
        next shouldBe
            listOf("18:16", "18:31", "18:46", "19:01", "19:16").map { Instant.parse("2026-09-15T$it:00Z") }
        val lock = CloseJob::class.java.getMethod("run").getAnnotation(SchedulerLock::class.java)
        lock.name shouldBe CloseJob.LOCK
        // Shorter than the interval: a dead instance's lock cannot outlast the next run.
        lock.lockAtMostFor shouldBe "PT14M"
    }

    @Test
    fun `the reaper removes keys past their day, under a lock of its own`() {
        val created = Instant.parse("2026-09-14T10:00:00Z")
        insertKey("expired", created, created.plusSeconds(86_400))
        insertKey("live", created.plusSeconds(7_200), created.plusSeconds(93_600))
        clock.set(created.plusSeconds(86_400))
        lockHeld(CloseJob.LOCK, until = "$UTC_NOW + interval '10 minutes'")

        reaper.run()

        jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys", String::class.java) shouldBe listOf("live")
    }

    /** The row another instance would have written. An upsert: the name's row outlives the test that made it. */
    private fun lockHeld(
        name: String,
        until: String,
    ) {
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, $until, $UTC_NOW - interval '14 minutes', 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
            name,
        )
    }

    /**
     * Lets every lock lapse, as time passing would. Not a TRUNCATE: ShedLock
     * remembers which names it has made a row for and only updates after
     * that, so a row deleted from under it can never be locked again by this
     * context — which looks, in a test, exactly like a job that will not run.
     */
    private fun releaseLocks() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second'")
    }

    @Test
    fun `while another instance holds the reaper's lock nothing is reaped`() {
        val created = Instant.parse("2026-09-14T10:00:00Z")
        insertKey("expired", created, created.plusSeconds(86_400))
        clock.set(created.plusSeconds(90_000))
        lockHeld(ReapIdempotencyKeys.LOCK, until = "$UTC_NOW + interval '10 minutes'")

        reaper.run()

        jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys", String::class.java) shouldBe listOf("expired")
    }

    /**
     * ShedLock compares the lock against a zoneless UTC reading of the
     * database's clock. In a `timestamptz` column that reading is taken for
     * local time, and a lapsed lock looks as far in the future as the
     * session's offset: on a machine at UTC+1 the job did not run for an
     * hour. The lock tests above write their rows the way ShedLock reads
     * them, so they cannot see this; the column's type is what holds it.
     */
    @Test
    fun `the lock's instants are stored without a zone, as ShedLock's database clock writes them`() {
        jdbc.queryForList(
            "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = 'shedlock' AND column_name IN ('lock_until', 'locked_at')",
            String::class.java,
        ) shouldBe listOf("timestamp without time zone", "timestamp without time zone")
    }

    private fun insertKey(
        key: String,
        createdAt: Instant,
        expiresAt: Instant,
    ) {
        jdbc.update(
            "INSERT INTO idempotency_keys (id, user_id, method, path, idempotency_key, request_hash, created_at, expires_at) " +
                "VALUES (?, ?, 'POST', '/probe', ?, 'hash', ?, ?)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            key,
            java.sql.Timestamp.from(createdAt),
            java.sql.Timestamp.from(expiresAt),
        )
    }

    private companion object {
        /** The database's clock as ShedLock reads it: UTC, with no zone attached (see V16). */
        const val UTC_NOW = "timezone('utc', now())"
    }

    private fun settled(): Double = meters.get(CloseJob.SETTLED).counter().count()

    private fun failed(): Double = meters.get(CloseJob.FAILED).counter().count()

    private fun lastSuccess(): Double = meters.get(CloseJob.LAST_SUCCESS).gauge().value()
}
