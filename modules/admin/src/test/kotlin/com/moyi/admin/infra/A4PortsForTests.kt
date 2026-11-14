package com.moyi.admin.infra

import com.moyi.gratitude.api.DateSettlement
import com.moyi.gratitude.api.DaySettlement
import com.moyi.gratitude.api.RebuildResult
import com.moyi.gratitude.api.StreakRebuild
import com.moyi.gratitude.api.StreakRecalculation
import com.moyi.scheduling.api.CloseRun
import com.moyi.scheduling.api.JobConsole
import com.moyi.scheduling.api.JobStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A4's ports in this module's test context. The support inbox is real (`SupportInboxStore`, found by
 * name as A2's stores are, over the real functions); the jobs, the date check and the streak rebuild
 * are scripted, since `scheduling` and `gratitude` are not built here and their own tests hold what
 * the ports do. Each scripted port records what it was asked and answers what a test sets.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = ["com.moyi.support.infra.database"],
    useDefaultFilters = false,
    includeFilters = [ComponentScan.Filter(type = FilterType.REGEX, pattern = [".*\\.SupportInboxStore"])],
)
internal class A4PortsForTests {
    @Bean
    fun jobConsole(): JobConsole = ScriptedJobs

    @Bean
    fun daySettlement(): DaySettlement = ScriptedJobs.settlement

    @Bean
    fun streakRecalculation(): StreakRecalculation = ScriptedJobs.streaks
}

internal object ScriptedJobs : JobConsole {
    /** What [runCloseNow] answers; with [CloseRun.Ran], `beforeRun` is called first and then [duringRun]. */
    @Volatile var next: CloseRun = CloseRun.Ran(1, 2, 3, 4, 0, backlog = false)

    /** Stands in for the close job's work: called after `beforeRun`, inside the run. */
    @Volatile var duringRun: () -> Unit = {}

    val calls: MutableList<String> = CopyOnWriteArrayList()

    val jobs =
        listOf(
            JobStatus("close-days", "Closes days.", "0 1/15 * * * *", Instant.parse("2026-11-14T10:01:00Z"), null, null, running = false),
        )

    override fun jobs(): List<JobStatus> = jobs

    override fun timerOn(): Boolean = true

    override fun runCloseNow(beforeRun: () -> Unit): CloseRun {
        calls += "run"
        val answer = next
        if (answer is CloseRun.Ran) {
            beforeRun()
            calls += "closer"
            duringRun()
        }
        return answer
    }

    val settlement =
        object : DaySettlement {
            override fun on(
                date: LocalDate,
                now: Instant,
            ): DateSettlement {
                calls += "settlement:$date"
                return DateSettlement(open = 3, pastDue = 2, uncounted = 1)
            }
        }

    /** What [streaks] answers next. */
    @Volatile var rebuild: StreakRebuild = StreakRebuild(RebuildResult.NO_DAYS, null, null)

    val streaks =
        object : StreakRecalculation {
            override fun rebuild(bondId: UUID): StreakRebuild {
                // The real port is MANDATORY; so is this stand-in, so a console that forgot its transaction fails here too.
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw IllegalTransactionStateException("no transaction")
                }
                calls += "rebuild:$bondId"
                return rebuild
            }
        }

    fun reset() {
        next = CloseRun.Ran(1, 2, 3, 4, 0, backlog = false)
        duringRun = {}
        rebuild = StreakRebuild(RebuildResult.NO_DAYS, null, null)
        calls.clear()
    }
}
