package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.JobRuns
import com.moyi.scheduling.infra.RecordingDataExports
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
import java.time.Instant

/** The data export job (ADR-0059): its two timers and locks, and how each run batches. What a build does is `export`'s. */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class DataExportJobTest(
    @Autowired private val job: DataExportJob,
    @Autowired private val exports: RecordingDataExports,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val runs: JobRuns,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = (now() AT TIME ZONE 'UTC') - interval '1 second'")
        jdbc.update("DELETE FROM job_runs")
        exports.reset()
    }

    @Test
    fun `a build run asks for one small batch and records that it finished`() {
        clock.set(NOW)

        job.prepareOnce()

        exports.prepares shouldBe listOf(DataExportJob.BATCH)
        runs.lastSuccess(DataExportJob.LOCK) shouldBe NOW
    }

    @Test
    fun `an expiry run takes full batches until one comes back short, within its budget`() {
        exports.nextExpired = DataExportJob.EXPIRY_BATCH

        job.expireOnce() shouldBe DataExportJob.EXPIRY_BUDGET

        exports.expiries.size shouldBe DataExportJob.EXPIRY_BUDGET / DataExportJob.EXPIRY_BATCH
    }

    @Test
    fun `each timer has its own cron property and lock, and a held lock stops the build`() {
        val prepare = DataExportJob::class.java.getMethod("prepare")
        val expire = DataExportJob::class.java.getMethod("expire")
        prepare.getAnnotation(Scheduled::class.java).cron shouldBe "\${moyi.scheduling.data-export.cron:${DataExportJob.EVERY_MINUTE}}"
        expire.getAnnotation(Scheduled::class.java).cron shouldBe "\${moyi.scheduling.data-export-expiry.cron:${DataExportJob.HOURLY}}"
        prepare.getAnnotation(SchedulerLock::class.java).name shouldBe DataExportJob.LOCK
        expire.getAnnotation(SchedulerLock::class.java).name shouldBe DataExportJob.EXPIRY_LOCK
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, (now() AT TIME ZONE 'UTC') + interval '30 minutes', now() AT TIME ZONE 'UTC', 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
            DataExportJob.LOCK,
        )

        job.prepare()

        exports.prepares.shouldBeEmpty()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-23T10:53:00Z")
    }
}
