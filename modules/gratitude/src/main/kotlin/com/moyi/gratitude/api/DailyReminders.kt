package com.moyi.gratitude.api

import java.time.Instant

/**
 * The daily reminder to write and its nudges (FR-080, FR-081, spec §4 N2,
 * ADR-0045, ADR-0068), asked for by `scheduling` every five minutes. That
 * module knows when; this one knows who has written.
 */
interface DailyReminders {
    /**
     * Asks for a reminder push for every reminder or nudge that fell within
     * `(after, upTo]` to a member in a bond that takes entries, **who has not
     * themselves written** for the bond-day it fell in. Whether the other
     * member has written is never asked: a reminder's existence must say
     * nothing about them (FR-076, T-09).
     *
     * At most three per member per bond-day, however many times the same
     * window is asked about: each index has its own dedupe key,
     * `reminder:{memberId}:{bond-day}` for the first and `…:2`, `…:3` for the
     * nudges after it, and each is asked for once.
     */
    fun remindDue(
        after: Instant,
        upTo: Instant,
    ): ReminderRun
}

/**
 * What one sweep did: reminders and nudges that fell due, how many were asked
 * for, how many were to members who had written, how many failed; and the
 * same by nudge index. A nudge asked before, in an earlier sweep of the same
 * window, is counted only in [byNudge] ([NudgeCounts.alreadyAsked]).
 *
 * [byNudge] has no default, so a caller cannot drop the per-nudge counts by
 * leaving it out: a run that did nothing passes `emptyMap()`.
 */
data class ReminderRun(
    val due: Int,
    val requested: Int,
    val alreadyWritten: Int,
    val failed: Int,
    val byNudge: Map<Int, NudgeCounts>,
)

/** One nudge index's share of a [ReminderRun]. */
data class NudgeCounts(
    val requested: Int,
    val written: Int,
    val alreadyAsked: Int,
    val failed: Int,
)
