package com.moyi.gratitude.api

import java.time.Duration
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
     * Writes the days nobody opened (at most [MAX_CREATED_PER_RUN] a call,
     * spec §6.4 step 1), settles days that had ended [SETTLE_MARGIN] before
     * [now], and reveals days whose reveal time has come by [now] — each in a
     * transaction of its own. It stops after [budget] days have been closed,
     * revealed or have failed; a day that is looked at and left does not
     * count. Idempotent: a second call finds nothing the first one finished.
     * A day or a bond that fails is counted and left for the next call; the
     * rest go on.
     */
    fun closeElapsedDays(
        now: Instant,
        budget: Int,
    ): CloseResult

    companion object {
        /**
         * How long a day must have been over before it is settled.
         *
         * This margin absorbs ordinary clock skew between the scheduled
         * instance and writers. The closer takes the bond lock before the
         * day lock, so a pairing or zone change already in flight commits
         * before its day is evaluated; a writer that arrives afterwards sees
         * the settled day under the same lock order.
         */
        val SETTLE_MARGIN: Duration = Duration.ofMinutes(1)

        /** Spec §6.4 step 1: "batches of at most 400 missing days". */
        const val MAX_CREATED_PER_RUN = 400
    }
}

/** What one call to [DayCloser.closeElapsedDays] did. Counts and ids only. */
data class CloseResult(
    /** Days nobody opened, now written closed (`EMPTY`, or `FROZEN` for a skipped label). */
    val created: Int,
    /** Days that had ended and are now closed. */
    val closed: Int,
    /** Days not yet ended whose reveal fell due. */
    val revealed: Int,
    /** Settled days whose effect on their bond's streak was worked out and recorded (spec §6.4 step 3). */
    val evaluated: Int,
    /** Days that threw while being settled, and bonds whose missing days or streak could not be written; each left as it was. */
    val failed: Int,
    /** Bonds with at least one day written, closed or revealed — where a streak may have moved (C4). */
    val bondsChanged: Set<UUID>,
    /** True when [DayCloser.closeElapsedDays] stopped at its budget with days still waiting. */
    val backlog: Boolean,
)
