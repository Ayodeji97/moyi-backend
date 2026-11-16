package com.moyi.scheduling.service

import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import com.moyi.common.core.FlagReading
import com.moyi.gratitude.api.DailyReminders
import com.moyi.gratitude.api.ReminderRun
import com.moyi.scheduling.infra.JobRuns
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The daily reminder's five-minute job (spec §4 N2, ADR-0045). It knows the
 * time and a lock, and asks `gratitude` to do the rest: whose reminder fell,
 * and who has written, are not written here.
 *
 * **The window is the half hour that ended on the last boundary**,
 * `(boundary - 30 min, boundary]`, with the boundary the run's time rounded
 * down to a multiple of five minutes. Every run asks about the last six
 * windows, not only its own: a run that never happened, in a restart or a
 * deploy, is made up by the next, and the dedupe key makes the overlap ask
 * for nothing twice. A run that starts late, or on another instance, asks
 * about the same half hour as one that started on time. An outage longer
 * than half an hour still loses reminders: one sent later than that is no
 * longer the time the member chose, and is noise (doc 25:69).
 *
 * **At thirty seconds past each fifth minute.** Reminder times are whole
 * minutes, and the window ends on the minute; firing a little after it keeps
 * a clock that is a second behind from asking about the window before last.
 *
 * Two meters, as the close job has, for its reason: the timestamp says the
 * job runs, the counter that it finds work.
 */
@Component
internal class ReminderJob(
    private val reminders: DailyReminders,
    private val flags: FeatureFlags,
    private val runs: JobRuns,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val requested: Counter =
        Counter
            .builder(REQUESTED)
            .description("Reminder nudges asked for, every index")
            .register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the reminder job's last run that finished, whether or not it found anybody due")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.reminders.cron:$EVERY_FIVE_MINUTES}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    fun run() {
        remindOnce()
    }

    /** One run, without the timer or the lock. */
    fun remindOnce(): ReminderRun {
        val now = clock.instant()
        // Switched off by an ADMIN (FR-115, ADR-0067 decision 7): nobody is asked about. A reminder that
        // falls due meanwhile is never sent: once on, the window starts no earlier than the switch
        // ([windowStart]). Still a run that finished: a pause is not a stopped job.
        val flag = flags.read(FeatureFlag.DAILY_REMINDERS)
        val upTo = windowEndingBefore(now)
        // Off, or switched on since the last boundary (an empty window): nobody is asked about, and
        // it is still a run that finished.
        val after = (flag as? FlagReading.On)?.let { windowStart(upTo, LOOKBACK, it.since) }
        if (after == null) {
            if (flag == FlagReading.Off) log.debug("reminders: switched off")
            finished(now)
            return ReminderRun(due = 0, requested = 0, alreadyWritten = 0, failed = 0, byNudge = emptyMap())
        }
        val run = reminders.remindDue(after, upTo)
        requested.increment(run.requested.toDouble())
        countByNudge(run)
        finished(now)
        if (run.failed > 0) {
            log.warn("reminders: due {}, asked for {}, had written {}, FAILED {}", run.due, run.requested, run.alreadyWritten, run.failed)
        } else if (run.due > 0) {
            // Counts only: who is reminded, and when they asked to be, is theirs.
            log.debug("reminders: due {}, asked for {}, had written {}", run.due, run.requested, run.alreadyWritten)
        }
        return run
    }

    /** Per nudge index and outcome; the index is one of at most three, so the tag set is small. */
    private fun countByNudge(run: ReminderRun) {
        run.byNudge.forEach { (index, counts) ->
            count(index, "requested", counts.requested)
            count(index, "written", counts.written)
            count(index, "already_asked", counts.alreadyAsked)
            count(index, "failed", counts.failed)
        }
    }

    private fun count(
        index: Int,
        outcome: String,
        by: Int,
    ) {
        Counter
            .builder(NUDGES)
            .description("Reminder nudges that fell due, by nudge index and what became of them")
            .tag("nudge", index.toString())
            .tag("outcome", outcome)
            .register(meters)
            .increment(by.toDouble())
    }

    private fun finished(now: Instant) {
        lastSuccess.set(now.epochSecond)
        runs.succeeded(LOCK)
    }

    internal companion object {
        /** The schedule, unless `moyi.scheduling.reminders.cron` says otherwise (`scripts/smoke.sh` does). */
        const val EVERY_FIVE_MINUTES = "30 */5 * * * *"
        const val LOCK = "daily-reminders"
        val WINDOW: Duration = Duration.ofMinutes(5)
        private const val LOOKBACK_MINUTES = 30L

        /**
         * How far back each run asks: six of its windows. A tick lost to a restart or a
         * deploy is asked about by the next one; the dedupe key makes the overlap ask for
         * nothing twice, and a reminder's own deadline (the end of its bond-day) bounds
         * how late one can be sent. Half an hour, because a reminder later than that is
         * no longer the time the member chose (ADR-0045).
         */
        val LOOKBACK: Duration = Duration.ofMinutes(LOOKBACK_MINUTES)

        /** `gratitude_reminder_job_last_success_timestamp` once exported (doc 11). */
        const val LAST_SUCCESS = "gratitude.reminder.job.last.success.timestamp"
        const val REQUESTED = "gratitude.reminders.requested"
        const val NUDGES = "gratitude.reminders.nudges"

        /** [now] rounded down to a multiple of [WINDOW] since the epoch. */
        fun windowEndingBefore(now: Instant): Instant =
            Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, WINDOW.seconds))
    }
}
