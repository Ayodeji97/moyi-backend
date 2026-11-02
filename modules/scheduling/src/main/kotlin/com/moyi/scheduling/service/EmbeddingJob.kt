package com.moyi.scheduling.service

import com.moyi.gratitude.api.EmbeddingRun
import com.moyi.gratitude.api.EmbeddingSweep
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * The embedding sweep's job (ADR-0054, spec §3.3). It knows the time and a lock, and asks
 * `gratitude` the rest: which entries are pending, and whether their bonds allow a vector.
 *
 * **Every minute, [BUDGET] entries at most.** A new entry is searchable by meaning within a
 * minute or two; a backlog (a new provider, a model change) drains at a steady pace rather than
 * in one run that outlives its lock.
 *
 * **The last-success gauge is stamped only by a run the provider did not fail**: a provider
 * that is down leaves it stale, as a job that stopped running does. With the provider's own
 * `gratitude.ai.embedding.requests{outcome=failed}` it is what alerting reads in S1; the gauge
 * of the oldest pending entry's age is deferred (ADR-0054).
 */
@Component
internal class EmbeddingJob(
    private val sweep: EmbeddingSweep,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the embedding job's last run that the provider did not fail")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.embedding.cron:$EVERY_MINUTE}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    fun run() {
        embedOnce()
    }

    /** One run, without the timer or the lock. */
    fun embedOnce(): EmbeddingRun {
        val now = clock.instant()
        val run = sweep.embedPending(BUDGET)
        if (run.failed) {
            // Counts only: which entries were pending is the couple's.
            log.warn("embedding: provider unavailable; embedded {}, skipped {} before it failed", run.embedded, run.skipped)
        } else {
            lastSuccess.set(now.epochSecond)
            if (run.embedded + run.skipped > 0) log.debug("embedding: embedded {}, skipped {}", run.embedded, run.skipped)
        }
        return run
    }

    internal companion object {
        /** The schedule, unless `moyi.scheduling.embedding.cron` says otherwise. */
        const val EVERY_MINUTE = "0 * * * * *"
        const val LOCK = "embedding"

        /** Entries embedded per run, at most. */
        const val BUDGET = 200

        const val LAST_SUCCESS = "gratitude.embedding.job.last.success.timestamp"
    }
}
