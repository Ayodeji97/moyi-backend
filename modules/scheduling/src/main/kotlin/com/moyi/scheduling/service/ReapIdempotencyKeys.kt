package com.moyi.scheduling.service

import com.moyi.common.web.idempotency.IdempotencyKeyStore
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Removes `Idempotency-Key` rows past their 24 hours (doc 06 §1; ADR-0031,
 * Owed). Until this ran, an expired row was only removed when its own key was
 * used again, so a key used once — which is nearly all of them — stayed.
 *
 * Hourly, at seven minutes past: not on the quarter-hour the close job owns.
 * A lock of its own, so neither job waits for the other.
 */
@Component
internal class ReapIdempotencyKeys(
    private val keys: IdempotencyKeyStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = HOURLY)
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun run() {
        reapOnce()
    }

    fun reapOnce(): Int {
        val removed = keys.deleteAllExpired(clock.instant())
        if (removed > 0) log.info("idempotency: {} expired keys removed", removed)
        return removed
    }

    internal companion object {
        const val HOURLY = "0 7 * * * *"
        const val LOCK = "reap-idempotency-keys"
    }
}
