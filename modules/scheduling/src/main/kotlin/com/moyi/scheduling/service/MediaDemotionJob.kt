package com.moyi.scheduling.service

import com.moyi.media.api.MediaLifecycle
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
 * The nightly demotion (D-16, spec §4 D5, ADR-0050): an attached photo whose
 * full-resolution variant is more than ninety days old keeps only its display
 * variant from then on. Irreversible; `media` holds every condition, and
 * `media.purge` deletes the full variant after each batch commits.
 *
 * At 03:40 UTC, after the nightly backup and clear of the close job's quarter
 * hours. Batched as the reaper is ([inBatches]): a night with more than a
 * run's budget leaves the rest to the next night, and says so.
 */
@Component
internal class MediaDemotionJob(
    private val media: MediaLifecycle,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val demoted: Counter =
        Counter
            .builder(DEMOTED)
            .description("Photos the nightly demotion moved to tier 2, their full variant given up (D-16)")
            .register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the media demotion's last run that finished, whether or not it found anything")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.media-demotion.cron:$NIGHTLY}", zone = "UTC")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT50M", lockAtLeastFor = "PT30S")
    fun run() {
        demoteOnce()
    }

    /** One run, without the timer or the lock: how many it demoted. */
    fun demoteOnce(): Int {
        val now = clock.instant()
        val total = inBatches(now) { at, limit -> media.demote(at, limit) }
        demoted.increment(total.changed.toDouble())
        lastSuccess.set(now.epochSecond)
        if (total.more) log.warn("media demotion: demoted {}, and more are waiting", total.changed)
        return total.changed
    }

    internal companion object {
        /** The schedule in UTC, unless `moyi.scheduling.media-demotion.cron` says otherwise. */
        const val NIGHTLY = "0 40 3 * * *"
        const val LOCK = "media-demotion"

        /** `gratitude_media_demotion_last_success_timestamp` once exported (doc 11). */
        const val LAST_SUCCESS = "gratitude.media.demotion.last.success.timestamp"
        const val DEMOTED = "gratitude.media.demoted"
    }
}
