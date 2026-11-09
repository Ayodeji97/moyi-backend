package com.moyi.scheduling.service

import com.moyi.export.api.DataExportJobs
import com.moyi.scheduling.infra.JobRuns
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
 * The data export (FR-009, FR-094, ADR-0059), on two timers: every minute, the exports waiting
 * are built ([prepare]), so that "target: minutes" (NFR-051, doc 09 §5) holds; hourly, the ones
 * whose 24 hours are over are expired and their archives deleted ([expire]). Each under a lock of
 * its own: a build is long, and an expiry must not wait behind it.
 *
 * The locks are a courtesy: each export is claimed by a conditional update before it is built
 * (`DataExports.claim`), so two runs at once build each export once.
 */
@Component
internal class DataExportJob(
    private val exports: DataExportJobs,
    private val clock: Clock,
    private val runs: JobRuns,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val prepared: Counter = Counter.builder(PREPARED).description("Data exports built and made ready").register(meters)
    private val expired: Counter = Counter.builder(EXPIRED).description("Data exports expired and their archives deleted").register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the data export job's last build run that finished")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.data-export.cron:$EVERY_MINUTE}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    fun prepare() {
        prepareOnce()
    }

    @Scheduled(cron = "\${moyi.scheduling.data-export-expiry.cron:$HOURLY}")
    @SchedulerLock(name = EXPIRY_LOCK, lockAtMostFor = "PT50M", lockAtLeastFor = "PT30S")
    fun expire() {
        expireOnce()
    }

    /** One build run, without the timer or the lock: how many exports it made ready. */
    fun prepareOnce(): Int {
        val now = clock.instant()
        val made = exports.prepareDue(BATCH)
        prepared.increment(made.toDouble())
        lastSuccess.set(now.epochSecond)
        runs.succeeded(LOCK, now)
        if (made > 0) log.info("data export: {} made ready", made)
        return made
    }

    /** One expiry run, without the timer or the lock: how many exports it expired. */
    fun expireOnce(): Int {
        val now = clock.instant()
        var total = 0
        var batch: Int
        do {
            batch = exports.expireDue(EXPIRY_BATCH)
            total += batch
        } while (batch == EXPIRY_BATCH && total < EXPIRY_BUDGET)
        expired.increment(total.toDouble())
        runs.succeeded(EXPIRY_LOCK, now)
        if (total > 0) log.info("data export: {} expired", total)
        return total
    }

    internal companion object {
        const val EVERY_MINUTE = "0 * * * * *"

        /** At fifty-three past, clear of the other hourly jobs (seven, twenty-three, forty-one). */
        const val HOURLY = "0 53 * * * *"
        const val LOCK = "data-export"
        const val EXPIRY_LOCK = "data-export-expiry"

        /** Builds per run: one archive can take a while, and the next minute takes the rest. */
        const val BATCH = 5
        const val EXPIRY_BATCH = 50
        const val EXPIRY_BUDGET = 1_000
        const val LAST_SUCCESS = "moyi.export.last.success.timestamp"
        const val PREPARED = "moyi.exports.prepared"
        const val EXPIRED = "moyi.exports.expired"
    }
}
