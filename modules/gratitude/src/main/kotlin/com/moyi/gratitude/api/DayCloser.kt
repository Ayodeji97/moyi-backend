package com.moyi.gratitude.api

import java.time.Instant
import java.util.UUID

/**
 * What the end of a day does to it, for the one caller outside this module
 * that has to ask: the close job in `scheduling` (spec §2.2).
 *
 * `scheduling` knows about a timer, a lock that keeps two instances from
 * running at once, and two counters. It does not know what a `PARTIAL` day
 * becomes, and must not: the synchronous path decides the same things when a
 * second entry arrives, and two statements of one rule drift. So the rule
 * stays here and the job only says when.
 *
 * **Not per zone**, though spec §2.2's first sketch was
 * `closeElapsedDays(zone)`. A day's end is read from its bond's own timeline
 * of zones (spec §6.4, ADR-0031 decision 13), and a day opened under one zone
 * may belong to a bond that now requests another; "the days in this zone" is
 * not a question the data can answer. The job passes the time and nothing
 * else (ADR-0033).
 */
interface DayCloser {
    /**
     * Settles days that have ended as of [now] and reveals days whose reveal
     * time has come, each in a transaction of its own, and stops after
     * [budget] of them have been closed, revealed or have failed. A day that
     * is looked at and left does not count. Idempotent: a second call finds nothing the first one finished.
     * A day that fails is counted and left for the next call; the rest go on.
     */
    fun closeElapsedDays(
        now: Instant,
        budget: Int,
    ): CloseResult
}

/** What one call to [DayCloser.closeElapsedDays] did. Counts and ids only. */
data class CloseResult(
    /** Days that had ended and are now closed. */
    val closed: Int,
    /** Days not yet ended whose reveal fell due. */
    val revealed: Int,
    /** Days that threw while being settled, and were left as they were. */
    val failed: Int,
    /** Bonds with at least one day closed or revealed — where a streak may have moved (C4). */
    val bondsChanged: Set<UUID>,
    /** True when [DayCloser.closeElapsedDays] stopped at its budget with days still waiting. */
    val backlog: Boolean,
)
