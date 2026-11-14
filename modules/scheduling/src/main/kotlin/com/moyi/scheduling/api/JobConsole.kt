package com.moyi.scheduling.api

import java.time.Instant

/**
 * The scheduled jobs as the admin console sees them (FR-114, ADR-0067 decisions 1 to 5): what each
 * is, when it last started and finished, and the one job an admin may run by hand, the close job.
 * Admin-only: `ArchitectureTest` holds that nothing but `admin` reaches it.
 *
 * Says **when** and **whether**, never what a job does to anybody: the close job's counts are
 * numbers of days, and nothing here names a person, a bond or a word.
 */
interface JobConsole {
    /** Every job that runs under a ShedLock lock, in the order they are listed, with what the database says of each. */
    fun jobs(): List<JobStatus>

    /** Whether this instance's timer fires the jobs at all (`moyi.scheduling.enabled`); off, only a run by hand runs. */
    fun timerOn(): Boolean

    /**
     * Runs the close job once, now, under its own lock (`close-days`), whether or not the timer is on.
     * [beforeRun] is called once the lock is held and before any work: the console writes its audit
     * row there, and a [beforeRun] that throws stops the run (the lock is released and the exception
     * is the caller's). When the lock is held elsewhere, by the timer or another instance, nothing is
     * called and the answer is [CloseRun.Busy].
     */
    fun runCloseNow(beforeRun: () -> Unit): CloseRun
}

/**
 * One job. [lastStartedAt] and [lockedUntil] are ShedLock's row (null: never run here); [lastFinishedAt]
 * is `job_runs` (null: never finished a run, or not since the table was written). [running] is "started
 * after its last finish and still holds its lock", as the database's clock has it.
 */
@Suppress("LongParameterList") // A row of the page: each column named, none derivable from another.
class JobStatus(
    /** The lock's name, which is the job's name everywhere: logs, `shedlock`, `job_runs`, the runbook. */
    val name: String,
    /** What it does, in one line. */
    val purpose: String,
    /** The cron it runs on here, as configured; `-` is switched off on this instance. */
    val schedule: String,
    val lastStartedAt: Instant?,
    val lockedUntil: Instant?,
    val lastFinishedAt: Instant?,
    val running: Boolean,
) {
    override fun toString(): String = "JobStatus($name, started=$lastStartedAt, finished=$lastFinishedAt, running=$running)"
}

/** What running the close job by hand did. */
sealed interface CloseRun {
    /** It ran: days written for nobody, closed, revealed, counted in their streak, and failed; [backlog] when more wait. */
    class Ran(
        val created: Int,
        val closed: Int,
        val revealed: Int,
        val evaluated: Int,
        val failed: Int,
        val backlog: Boolean,
    ) : CloseRun {
        override fun toString(): String =
            "Ran(created=$created, closed=$closed, revealed=$revealed, evaluated=$evaluated, failed=$failed, backlog=$backlog)"
    }

    /** The lock was held elsewhere: nothing ran, and [JobConsole.runCloseNow]'s `beforeRun` was not called. */
    data object Busy : CloseRun
}
