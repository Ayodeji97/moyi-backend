package com.moyi.scheduling.service

import com.moyi.scheduling.api.CloseRun
import com.moyi.scheduling.api.JobConsole
import com.moyi.scheduling.api.JobStatus
import com.moyi.scheduling.infra.JobRuns
import com.moyi.scheduling.infra.LockRows
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The jobs page's port (ADR-0067 decisions 1 to 5). The registry below is the one list of the jobs
 * that run under a ShedLock lock; the outbox poller holds none and is not on it (decision 2).
 * `JobConsoleTest` holds the registry to the `@SchedulerLock` names in this package, so a job added
 * without a line here fails the build.
 *
 * Running the close job by hand goes through ShedLock's own [LockingTaskExecutor] over the
 * application's [LockProvider], with the timer's lock name and `lockAtMostFor`: so a run by hand and
 * a run by the timer, on this instance or another, never overlap. `lockAtLeastFor` is zero: the
 * timer's thirty seconds guard against a second instance's late clock re-running a quarter-hour,
 * which a person pressing a button is not.
 */
@Component
internal class ScheduledJobsConsole(
    lockProvider: LockProvider,
    private val closeJob: CloseJob,
    private val locks: LockRows,
    private val runs: JobRuns,
    private val environment: Environment,
) : JobConsole {
    private val executor: LockingTaskExecutor = DefaultLockingTaskExecutor(lockProvider)

    override fun jobs(): List<JobStatus> {
        val rows = locks.all()
        val finishes = runs.all()
        return JOBS.map { job ->
            val row = rows[job.lock]
            val finished = finishes[job.lock]
            JobStatus(
                name = job.lock,
                purpose = job.purpose,
                schedule = environment.getProperty(job.cronProperty, job.defaultCron),
                lastStartedAt = row?.lockedAt,
                lockedUntil = row?.lockUntil,
                lastFinishedAt = finished,
                running = row != null && row.held && (finished == null || row.lockedAt.isAfter(finished)),
            )
        }
    }

    override fun timerOn(): Boolean = environment.getProperty(TIMER_PROPERTY, Boolean::class.java, true)

    override fun runCloseNow(beforeRun: () -> Unit): CloseRun {
        // ShedLock's clock, as its annotations use: the lock's times are the database's (`usingDbTime`),
        // and a lock configured at the application's clock (a test's, set in the past) is one ShedLock
        // treats as already lapsed and does not take.
        val lock = LockConfiguration(ClockProvider.now(), CloseJob.LOCK, CLOSE_AT_MOST, Duration.ZERO)
        val result =
            executor.executeWithLock(
                LockingTaskExecutor.TaskWithResult {
                    beforeRun()
                    closeJob.closeOnce()
                },
                lock,
            )
        if (!result.wasExecuted()) return CloseRun.Busy
        val run = checkNotNull(result.result) { "the close job ran and answered nothing" }
        return CloseRun.Ran(run.created, run.closed, run.revealed, run.evaluated, run.failed, run.backlog)
    }

    /** One job: its lock, what it does, and the property and default its `@Scheduled` cron reads. */
    class Job(
        val lock: String,
        val purpose: String,
        val cronProperty: String,
        val defaultCron: String,
    )

    internal companion object {
        const val TIMER_PROPERTY = "moyi.scheduling.enabled"

        /** The timer's own `lockAtMostFor` (`CloseJob`): a minute short of its interval. */
        val CLOSE_AT_MOST: Duration = Duration.ofMinutes(14)

        private const val P = "moyi.scheduling"

        val JOBS =
            listOf(
                Job(
                    CloseJob.LOCK,
                    "Closes days that have ended, reveals those fallen due, counts settled days in their streak.",
                    "$P.close.cron",
                    CloseJob.EVERY_FIFTEEN_MINUTES,
                ),
                Job(
                    ReminderJob.LOCK,
                    "Asks for the daily reminders and their nudges due in the last half hour.",
                    "$P.reminders.cron",
                    ReminderJob.EVERY_FIVE_MINUTES,
                ),
                Job(
                    OnThisDayJob.LOCK,
                    "Asks for the on-this-day pushes due in the last half hour.",
                    "$P.on-this-day.cron",
                    OnThisDayJob.EVERY_QUARTER_HOUR,
                ),
                Job(
                    HeldPushJob.LOCK,
                    "Sends the pushes held by quiet hours once they end.",
                    "$P.held-pushes.cron",
                    HeldPushJob.EVERY_MINUTE,
                ),
                Job(
                    EmbeddingJob.LOCK,
                    "Embeds entries written since the last run, for search.",
                    "$P.embedding.cron",
                    EmbeddingJob.EVERY_MINUTE,
                ),
                Job(
                    AccountDeletionJob.LOCK,
                    "Deletes accounts whose cancellation window has closed.",
                    "$P.account-deletion.cron",
                    AccountDeletionJob.HOURLY,
                ),
                Job(DataExportJob.LOCK, "Builds the data exports members asked for.", "$P.data-export.cron", DataExportJob.EVERY_MINUTE),
                Job(
                    DataExportJob.EXPIRY_LOCK,
                    "Deletes data exports past their expiry.",
                    "$P.data-export-expiry.cron",
                    DataExportJob.HOURLY,
                ),
                Job(
                    MediaReaperJob.LOCK,
                    "Deletes unfinished uploads and media attached to no entry.",
                    "$P.media-reaper.cron",
                    MediaReaperJob.HOURLY,
                ),
                Job(
                    MediaDemotionJob.LOCK,
                    "Drops the full-resolution copy of photos older than ninety days.",
                    "$P.media-demotion.cron",
                    MediaDemotionJob.NIGHTLY,
                ),
                Job(
                    ReapIdempotencyKeys.LOCK,
                    "Removes idempotency keys past their lifetime.",
                    "$P.idempotency-keys.cron",
                    ReapIdempotencyKeys.HOURLY,
                ),
            )
    }
}
