package com.moyi.scheduling.service

import com.moyi.notification.api.HeldPushes
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Releases pushes held by a member's quiet hours once the hours end (Q7,
 * ADR-0045). Every minute, so a reveal held overnight goes within a minute of
 * the member's morning, plus the outbox's two seconds.
 *
 * **A lock, though the release is safe without one.** The release skips what
 * another instance holds (`SKIP LOCKED`), so two instances would not release a
 * push twice; the lock only saves the second the work of finding that out,
 * as the close job's does.
 *
 * A run releases at most [LIMIT]; a morning with more waits for the next
 * minute, and says so.
 */
@Component
internal class HeldPushJob(
    private val held: HeldPushes,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the held-push release's last run that finished")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.held-pushes.cron:$EVERY_MINUTE}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT50S", lockAtLeastFor = "PT5S")
    fun run() {
        releaseOnce()
    }

    /** One run, without the timer or the lock. */
    fun releaseOnce(): Int {
        val now = clock.instant()
        val released = held.releaseDue(now, LIMIT)
        lastSuccess.set(now.epochSecond)
        if (released >= LIMIT) log.warn("held pushes: released {}, and more are waiting", released)
        return released
    }

    internal companion object {
        const val EVERY_MINUTE = "15 * * * * *"
        const val LOCK = "release-held-pushes"
        const val LIMIT = 1_000

        /** `gratitude_held_push_job_last_success_timestamp` once exported (doc 11). */
        const val LAST_SUCCESS = "gratitude.held.push.job.last.success.timestamp"
    }
}
