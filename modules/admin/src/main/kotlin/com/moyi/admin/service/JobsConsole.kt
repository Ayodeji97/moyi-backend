package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.common.security.ClientAddress
import com.moyi.gratitude.api.DateSettlement
import com.moyi.gratitude.api.DaySettlement
import com.moyi.gratitude.api.RebuildResult
import com.moyi.gratitude.api.StreakFigures
import com.moyi.gratitude.api.StreakRebuild
import com.moyi.gratitude.api.StreakRecalculation
import com.moyi.scheduling.api.CloseRun
import com.moyi.scheduling.api.JobConsole
import com.moyi.scheduling.api.JobStatus
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The jobs page (FR-114, FR-074, ADR-0067 decisions 1 to 6): what the scheduled jobs are doing, a
 * date's progress, the close job run by hand, and a bond's streak rebuilt.
 *
 * **The close run is not one transaction, and this method is not transactional.** The run is
 * thousands of transactions of its own, one per day, so its `JOB_RUN` row is written first, in its
 * own transaction, while the job's lock is held (`JobConsole.runCloseNow`'s `beforeRun`): a run that
 * dies has left its row, and a row that cannot be written stops the run before any work. What the
 * run did is answered, logged at INFO and counted, never put in the row (decision 5).
 *
 * The justification is `ModerationNote`'s, checked before any port is called, and reaches only the
 * row's `justification`: never a log line, a metric tag or the metadata.
 */
@Service
@ConditionalOnAdminConsole
@Suppress("LongParameterList") // Three ports, the audit trail, the run's own transactions, the clock and the meters.
internal class JobsConsole(
    private val jobs: JobConsole,
    private val settlement: DaySettlement,
    private val streaks: StreakRecalculation,
    private val audit: AuditTrail,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The page's table. No audit row: it names nobody, as the home page's counts do not (decision 1). */
    @PreAuthorize("hasRole('SUPPORT')")
    fun page(): JobsPage = JobsPage(jobs.jobs(), jobs.timerOn())

    /** The date check (decision 3): counts of days, at the close job's own threshold. No audit row. */
    @PreAuthorize("hasRole('SUPPORT')")
    fun settlement(date: LocalDate): DateSettlement = settlement.on(date, clock.instant())

    @PreAuthorize("hasRole('ADMIN')")
    fun runClose(
        justification: String,
        adminId: UUID,
        from: ClientAddress?,
    ): CloseRun {
        val checked = ModerationNote.require(justification)
        val run =
            jobs.runCloseNow {
                transactions.executeWithoutResult { record(AuditOutcome.SUCCEEDED, STARTED, checked, adminId, from) }
            }
        if (run is CloseRun.Busy) {
            transactions.executeWithoutResult { record(AuditOutcome.REFUSED, BUSY, checked, adminId, from) }
        }
        val result = if (run is CloseRun.Ran) STARTED else BUSY
        meters.counter(RUN_METRIC, "job", CLOSE_JOB, "result", result).increment()
        log.info("job run by hand admin={} job={} {}", adminId, CLOSE_JOB, run)
        return run
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    fun rebuild(
        bondId: UUID,
        justification: String,
        adminId: UUID,
        from: ClientAddress?,
    ): StreakRebuild {
        val checked = ModerationNote.require(justification)
        val rebuilt = streaks.rebuild(bondId)
        val metadata = linkedMapOf<String, Any>(RESULT to rebuilt.result.name)
        rebuilt.before?.let { metadata["before"] = figures(it) }
        rebuilt.after?.let { metadata["after"] = figures(it) }
        audit.record(
            action = AuditAction.STREAK_RECALCULATED,
            outcome = if (rebuilt.result == RebuildResult.NO_DAYS) AuditOutcome.REFUSED else AuditOutcome.SUCCEEDED,
            actorId = adminId,
            targetType = BOND,
            targetId = bondId.toString(),
            justification = checked.text,
            metadata = metadata,
            clientAddress = from,
        )
        meters.counter(REBUILD_METRIC, RESULT, rebuilt.result.name).increment()
        log.info("streak rebuilt admin={} bond={} result={}", adminId, bondId, rebuilt.result)
        return rebuilt
    }

    private fun record(
        outcome: AuditOutcome,
        result: String,
        checked: ModerationNote,
        adminId: UUID,
        from: ClientAddress?,
    ) {
        audit.record(
            action = AuditAction.JOB_RUN,
            outcome = outcome,
            actorId = adminId,
            targetType = null,
            targetId = null,
            justification = checked.text,
            metadata = linkedMapOf("job" to CLOSE_JOB, RESULT to result),
            clientAddress = from,
        )
    }

    private fun figures(figures: StreakFigures): Map<String, Int> =
        linkedMapOf(
            "current" to figures.current,
            "longest" to figures.longest,
            "freezesAvailable" to figures.freezesAvailable,
            "completeDays" to figures.completeDays,
        )

    private companion object {
        /** `CloseJob.LOCK`, which is `scheduling`'s internal: the job's name everywhere. */
        const val CLOSE_JOB = "close-days"
        const val STARTED = "STARTED"
        const val BUSY = "BUSY"
        const val RESULT = "result"
        const val BOND = "BOND"
        const val RUN_METRIC = "moyi.admin.job.run"
        const val REBUILD_METRIC = "moyi.admin.streak.rebuild"
    }
}

/** The jobs page's table and whether this instance's timer fires them. */
internal class JobsPage(
    val jobs: List<JobStatus>,
    val timerOn: Boolean,
)
