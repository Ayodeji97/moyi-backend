package com.moyi.scheduling.service

import com.moyi.media.api.LifecycleRun
import com.moyi.media.api.MediaLifecycle
import com.moyi.scheduling.infra.JobRuns
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The media reaper (spec §4 D5, ADR-0050), hourly: an upload never completed,
 * an hour after its URL stopped working, and a photo or voice note on no entry
 * for a day (doc 06:186), are deleted. It knows the time and a lock, and asks
 * `media` the rest; `media.purge` deletes the objects after each batch commits.
 *
 * **In batches of [BATCH], each its own transaction**, so no batch holds many
 * row locks for long, until one comes back short or the run has deleted
 * [RUN_BUDGET]; a backlog larger than that is drained by the next runs, and
 * said at WARN.
 *
 * At twenty-three minutes past, clear of the close job's quarter hours and the
 * idempotency reaper's seven past. The lock is a courtesy: two runs at once
 * would each skip what the other holds (`SKIP LOCKED`), and an asset is
 * deleted once, whoever gets to it.
 */
@Component
internal class MediaReaperJob(
    private val media: MediaLifecycle,
    private val clock: Clock,
    private val runs: JobRuns,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val reaped: Counter =
        Counter
            .builder(REAPED)
            .description("Media assets the reaper deleted: uploads never completed, and assets a day on no entry")
            .register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the media reaper's last run that finished, whether or not it found anything")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.media-reaper.cron:$HOURLY}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT50M", lockAtLeastFor = "PT30S")
    fun run() {
        reapOnce()
    }

    /** One run, without the timer or the lock: how many it deleted. */
    fun reapOnce(): Int {
        val now = clock.instant()
        val total = inBatches(now) { at, limit -> media.reap(at, limit) }
        reaped.increment(total.changed.toDouble())
        lastSuccess.set(now.epochSecond)
        // Only a run that finished: what the media alarm reads (ADR-0050), where ShedLock's row says only that it began.
        runs.succeeded(LOCK, now)
        if (total.more) log.warn("media reaper: deleted {}, and more are waiting", total.changed)
        return total.changed
    }

    internal companion object {
        /** The schedule, unless `moyi.scheduling.media-reaper.cron` says otherwise. */
        const val HOURLY = "0 23 * * * *"
        const val LOCK = "media-reaper"

        /** `gratitude_media_reaper_last_success_timestamp` once exported (doc 11). */
        const val LAST_SUCCESS = "gratitude.media.reaper.last.success.timestamp"
        const val REAPED = "gratitude.media.reaped"
    }
}

/** Assets one transaction of a lifecycle job changes at most. */
internal const val BATCH = 500

/** Assets one run of a lifecycle job changes at most, across its batches. */
internal const val RUN_BUDGET = 20_000

/**
 * Calls [batch] with [BATCH] until a batch comes back short or [RUN_BUDGET] is
 * spent: the total, and whether more remain.
 */
internal fun inBatches(
    now: Instant,
    batch: (Instant, Int) -> LifecycleRun,
): LifecycleRun {
    var changed = 0
    var more = true
    while (more && changed < RUN_BUDGET) {
        val run = batch(now, BATCH)
        changed += run.changed
        more = run.more
    }
    return LifecycleRun(changed, more)
}
