package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.scheduling.infra.JobRuns
import com.moyi.scheduling.infra.RecordingAccountDeletions
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

/**
 * The account deletion job (ADR-0058): when it runs, under which lock, and how it batches.
 * What a deletion does is identity's (`AccountDeletionEndpointTest`).
 */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class AccountDeletionJobTest(
    @Autowired private val job: AccountDeletionJob,
    @Autowired private val deletions: RecordingAccountDeletions,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val runs: JobRuns,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() {
        jdbc.update("UPDATE shedlock SET lock_until = (now() AT TIME ZONE 'UTC') - interval '1 second'")
        jdbc.update("DELETE FROM job_runs")
        deletions.reset()
    }

    @Test
    fun `a run asks identity for one batch when it comes back short, and records that it finished`() {
        clock.set(NOW)
        deletions.next = 3

        job.executeOnce() shouldBe 3

        deletions.limits shouldBe listOf(AccountDeletionJob.BATCH)
        runs.lastSuccess(AccountDeletionJob.LOCK) shouldBe NOW
    }

    @Test
    fun `a full batch is followed by another, up to the run's budget`() {
        deletions.next = AccountDeletionJob.BATCH

        job.executeOnce() shouldBe AccountDeletionJob.RUN_BUDGET

        deletions.limits.size shouldBe AccountDeletionJob.RUN_BUDGET / AccountDeletionJob.BATCH
    }

    @Test
    fun `it is hourly under a lock of its own, and asks nothing while another instance holds it`() {
        val run = AccountDeletionJob::class.java.getMethod("run")
        run.getAnnotation(Scheduled::class.java).cron shouldBe "\${moyi.scheduling.account-deletion.cron:${AccountDeletionJob.HOURLY}}"
        AccountDeletionJob.HOURLY shouldBe "0 41 * * * *"
        run.getAnnotation(SchedulerLock::class.java).name shouldBe AccountDeletionJob.LOCK
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) " +
                "VALUES (?, (now() AT TIME ZONE 'UTC') + interval '50 minutes', now() AT TIME ZONE 'UTC', 'another-instance') " +
                "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by",
            AccountDeletionJob.LOCK,
        )

        job.run()

        deletions.limits.shouldBeEmpty()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-23T10:41:00Z")
    }
}
