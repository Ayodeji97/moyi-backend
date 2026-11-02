package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.gratitude.api.EmbeddingRun
import com.moyi.scheduling.infra.RecordingEmbeddingSweep
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
 * The embedding job (ADR-0054): the budget, the timing, the lock and the last-success gauge.
 * Which entries are embedded is `gratitude`'s; the stand-in records what it was asked.
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class EmbeddingJobTest(
    @Autowired private val job: EmbeddingJob,
    @Autowired private val sweep: RecordingEmbeddingSweep,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val meters: MeterRegistry,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second'")
        sweep.reset()
    }

    @Test
    fun `a run asks the sweep for two hundred entries and stamps its success`() {
        clock.set(Instant.parse("2026-11-02T10:00:00Z"))
        sweep.next = EmbeddingRun(embedded = 3, skipped = 1, failed = false)

        job.embedOnce() shouldBe EmbeddingRun(embedded = 3, skipped = 1, failed = false)

        sweep.budgets shouldBe listOf(EmbeddingJob.BUDGET)
        EmbeddingJob.BUDGET shouldBe 200
        lastSuccess() shouldBe Instant.parse("2026-11-02T10:00:00Z").epochSecond.toDouble()
    }

    @Test
    fun `a run the provider failed is not a success`() {
        clock.set(Instant.parse("2026-11-02T10:00:00Z"))
        job.embedOnce()
        clock.set(Instant.parse("2026-11-02T10:01:00Z"))
        sweep.next = EmbeddingRun(embedded = 0, skipped = 0, failed = true)

        job.embedOnce()

        lastSuccess() shouldBe Instant.parse("2026-11-02T10:00:00Z").epochSecond.toDouble()
    }

    @Test
    fun `the job is timed every minute under a lock`() {
        val run = EmbeddingJob::class.java.getMethod("run")
        run.getAnnotation(Scheduled::class.java).cron shouldBe "\${moyi.scheduling.embedding.cron:${EmbeddingJob.EVERY_MINUTE}}"
        EmbeddingJob.EVERY_MINUTE shouldBe "0 * * * * *"
        val lock = run.getAnnotation(SchedulerLock::class.java)
        lock.name shouldBe EmbeddingJob.LOCK
        lock.name shouldBe "embedding"
        lock.lockAtMostFor shouldBe "PT5M"
        lock.lockAtLeastFor shouldBe "PT20S"
    }

    @Test
    fun `while another instance holds the lock nothing is embedded`() {
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, $UTC_NOW + interval '5 minutes', $UTC_NOW, 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
            EmbeddingJob.LOCK,
        )

        job.run()

        sweep.budgets.shouldBeEmpty()
    }

    private fun lastSuccess(): Double = meters.get(EmbeddingJob.LAST_SUCCESS).gauge().value()

    private companion object {
        /** The database's clock as ShedLock reads it (see `CloseJobTest`). */
        const val UTC_NOW = "timezone('utc', now())"
    }
}
