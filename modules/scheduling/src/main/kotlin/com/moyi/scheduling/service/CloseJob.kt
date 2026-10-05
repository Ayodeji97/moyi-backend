package com.moyi.scheduling.service

import com.moyi.gratitude.api.CloseResult
import com.moyi.gratitude.api.DayCloser
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * The fifteen-minute job (spec §6.4). It knows the time, a lock and two
 * counters, and asks `gratitude` to do the rest: what the end of a day does
 * to it is not written here (spec §2.2).
 *
 * **Every fifteen minutes, not hourly.** `Asia/Kathmandu` is UTC+5:45 and
 * `Pacific/Chatham` +12:45; an hourly job closes those couples' days up to
 * forty-five minutes late.
 *
 * **Two meters, because one cannot tell healthy from stopped** (doc 11).
 * The timestamp is set on every run that finishes, including the many that
 * find nothing to do, so it only goes stale when the job has stopped. The
 * counter proves the job is doing work and not merely running: flat for more
 * than a day means no couple's midnight is being met.
 *
 * `lockAtMostFor` is a minute short of the interval, so an instance that
 * died mid-run cannot make the next run skip. `lockAtLeastFor` keeps a
 * second instance whose clock is a few seconds behind from running the same
 * quarter-hour again.
 */
@Component
internal class CloseJob(
    private val closer: DayCloser,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val settled: Counter =
        Counter
            .builder(SETTLED)
            .description("Bond-days the close job has settled: closed, revealed at their time, or written for a day nobody opened")
            .register(meters)
    private val failed: Counter =
        Counter
            .builder(FAILED)
            .description("Bond-days the close job could not settle and left for the next run")
            .register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the close job's last run that finished, whether or not it found work")
            .register(meters)
    }

    @Scheduled(cron = EVERY_FIFTEEN_MINUTES)
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT14M", lockAtLeastFor = "PT30S")
    fun run() {
        closeOnce()
    }

    /** One run, without the timer or the lock — what [run] does once it holds both. */
    fun closeOnce(): CloseResult {
        val now = clock.instant()
        val result = closer.closeElapsedDays(now, BUDGET)
        settled.increment((result.created + result.closed + result.revealed).toDouble())
        failed.increment(result.failed.toDouble())
        lastSuccess.set(now.epochSecond)
        if (result.created + result.closed + result.revealed + result.failed > 0 || result.backlog) {
            log.info(
                "close: created {}, closed {}, revealed {}, failed {}, bonds {}, more waiting: {}",
                result.created,
                result.closed,
                result.revealed,
                result.failed,
                result.bondsChanged.size,
                result.backlog,
            )
        }
        return result
    }

    internal companion object {
        const val EVERY_FIFTEEN_MINUTES = "0 */15 * * * *"
        const val LOCK = "close-days"

        /** `gratitude_close_job_last_success_timestamp` once exported (doc 11). */
        const val LAST_SUCCESS = "gratitude.close.job.last.success.timestamp"

        /** `gratitude_bonds_closed_total` once exported (doc 11): days, counted per bond-day. */
        const val SETTLED = "gratitude.bonds.closed"
        const val FAILED = "gratitude.close.days.failed"

        /**
         * Days closed, revealed or failed in one run before it stops and
         * leaves the rest to the next. Sized so a run finishes well inside
         * its lock; a backlog is drained over several runs, not held open.
         */
        const val BUDGET = 5_000
    }
}
