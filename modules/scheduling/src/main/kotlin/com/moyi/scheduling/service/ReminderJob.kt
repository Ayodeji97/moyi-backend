package com.moyi.scheduling.service

import com.moyi.gratitude.api.DailyReminders
import com.moyi.gratitude.api.ReminderRun
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
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val requested: Counter =
        Counter
            .builder(REQUESTED)
            .description("Daily reminders asked for: members whose reminder fell and who had not written")
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
        val upTo = windowEndingBefore(now)
        val run = reminders.remindDue(upTo.minus(LOOKBACK), upTo)
        requested.increment(run.requested.toDouble())
        lastSuccess.set(now.epochSecond)
        if (run.failed > 0) {
            log.warn("reminders: due {}, asked for {}, had written {}, FAILED {}", run.due, run.requested, run.alreadyWritten, run.failed)
        } else if (run.due > 0) {
            // Counts only: who is reminded, and when they asked to be, is theirs.
            log.debug("reminders: due {}, asked for {}, had written {}", run.due, run.requested, run.alreadyWritten)
        }
        return run
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

        /** [now] rounded down to a multiple of [WINDOW] since the epoch. */
        fun windowEndingBefore(now: Instant): Instant =
            Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, WINDOW.seconds))
    }
}
