package com.moyi.scheduling.service

import com.moyi.gratitude.api.OnThisDayPushes
import com.moyi.gratitude.api.OnThisDayRun
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
 * The on-this-day push's job (FR-092, spec §4 N3, ADR-0046). It knows the
 * time and a lock, and asks `gratitude` the rest: who opted in, whose push
 * time fell, and who has a memory today.
 *
 * **Every quarter-hour, at most one push a week per member.** The spec's
 * "weekly sweep" is the push's cadence, not the job's: a memory is a date in
 * an earlier year, and a sweep that ran one day a week would look at one date
 * in seven and miss the rest. So the job asks every day, at each member's own
 * midday ([OnThisDayPushes.PUSH_AT] in their reminder zone), and `gratitude`
 * counts seven days back from the ledger: one sent, or still owed, in that
 * span is the week's push (ADR-0046 decision 8).
 *
 * **The window is the hour that ended on the last quarter-hour boundary**,
 * the reminder job's design at a slower pace: a tick lost to a restart or a
 * deploy is asked about by the next, and the key makes the overlap ask for
 * nothing twice. A memory's own deadline (the end of its bond-day) bounds how
 * late one can be sent.
 */
@Component
internal class OnThisDayJob(
    private val pushes: OnThisDayPushes,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val requested: Counter =
        Counter
            .builder(REQUESTED)
            .description("On-this-day pushes asked for: members who opted in, whose time fell, with a memory today")
            .register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the on-this-day job's last run that finished, whether or not it found anybody due")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.on-this-day.cron:$EVERY_QUARTER_HOUR}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT14M", lockAtLeastFor = "PT30S")
    fun run() {
        pushOnce()
    }

    /** One run, without the timer or the lock. */
    fun pushOnce(): OnThisDayRun {
        val now = clock.instant()
        val upTo = windowEndingBefore(now)
        val run = pushes.pushDue(upTo.minus(LOOKBACK), upTo)
        requested.increment(run.requested.toDouble())
        lastSuccess.set(now.epochSecond)
        if (run.failed > 0) {
            log.warn(
                "on this day: due {}, asked for {}, nothing to remember {}, FAILED {}",
                run.due,
                run.requested,
                run.nothingToRemember,
                run.failed,
            )
        } else if (run.due > 0) {
            // Counts only: who has a memory today is theirs.
            log.debug("on this day: due {}, asked for {}, nothing to remember {}", run.due, run.requested, run.nothingToRemember)
        }
        return run
    }

    internal companion object {
        /**
         * The schedule, unless `moyi.scheduling.on-this-day.cron` says otherwise. Forty-five
         * seconds past, clear of the reminder job's thirty.
         */
        const val EVERY_QUARTER_HOUR = "45 */15 * * * *"
        const val LOCK = "on-this-day-pushes"
        val WINDOW: Duration = Duration.ofMinutes(15)

        /** Four of its windows: a lost tick is made up by the next, as with the reminder. */
        val LOOKBACK: Duration = Duration.ofHours(1)

        const val LAST_SUCCESS = "gratitude.on.this.day.job.last.success.timestamp"
        const val REQUESTED = "gratitude.on.this.day.requested"

        /** [now] rounded down to a multiple of [WINDOW] since the epoch. */
        fun windowEndingBefore(now: Instant): Instant =
            Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, WINDOW.seconds))
    }
}
