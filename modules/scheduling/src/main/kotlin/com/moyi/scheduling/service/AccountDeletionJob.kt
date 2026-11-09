package com.moyi.scheduling.service

import com.moyi.identity.api.AccountDeletions
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
 * Account deletion (FR-008, ADR-0058), hourly: every request whose 14-day cancellation window
 * has closed is carried out by identity, which publishes the event the other modules erase
 * from. Hourly, so a request is carried out within the hour its window closes, well inside
 * FR-008's 30 days; a missed run is made up by the next, because "due" is a date, not a tick.
 *
 * At forty-one past, clear of the close job's quarter hours, the idempotency reaper's seven
 * past and the media reaper's twenty-three. The lock is a courtesy: identity locks each
 * account's row and reads its request again under it, so two runs at once carry out each
 * account once.
 */
@Component
internal class AccountDeletionJob(
    private val deletions: AccountDeletions,
    private val clock: Clock,
    private val runs: JobRuns,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSuccess = AtomicLong(0)
    private val executed: Counter =
        Counter
            .builder(EXECUTED)
            .description("Accounts deleted at their owner's request once the cancellation window had closed")
            .register(meters)

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the account deletion job's last run that finished")
            .register(meters)
    }

    @Scheduled(cron = "\${moyi.scheduling.account-deletion.cron:$HOURLY}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT50M", lockAtLeastFor = "PT30S")
    fun run() {
        executeOnce()
    }

    /** One run, without the timer or the lock: how many accounts it deleted. */
    fun executeOnce(): Int {
        val now = clock.instant()
        var total = 0
        var batch: Int
        do {
            batch = deletions.executeDue(BATCH)
            total += batch
        } while (batch == BATCH && total < RUN_BUDGET)
        executed.increment(total.toDouble())
        lastSuccess.set(now.epochSecond)
        runs.succeeded(LOCK, now)
        if (total > 0) log.info("account deletion: carried out {}", total)
        return total
    }

    internal companion object {
        const val HOURLY = "0 41 * * * *"
        const val LOCK = "account-deletion"
        const val BATCH = 50
        const val RUN_BUDGET = 1_000
        const val LAST_SUCCESS = "moyi.account.deletion.last.success.timestamp"
        const val EXECUTED = "moyi.account.deletions"
    }
}
