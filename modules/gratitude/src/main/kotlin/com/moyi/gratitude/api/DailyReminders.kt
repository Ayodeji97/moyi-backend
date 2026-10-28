package com.moyi.gratitude.api

import java.time.Instant

/**
 * The daily reminder to write (FR-080, FR-081, spec §4 N2, ADR-0045), asked
 * for by `scheduling` every five minutes. That module knows when; this one
 * knows who has written.
 */
interface DailyReminders {
    /**
     * Asks for a reminder push for every member whose reminder fell within
     * `(after, upTo]` in a bond that takes entries, **who has not themselves
     * written** for the bond-day it fell in. Whether the other member has
     * written is never asked: a reminder's existence must say nothing about
     * them (FR-076, T-09).
     *
     * One push per member per bond-day, however many times the same window
     * is asked about: the dedupe key is `reminder:{memberId}:{bond-day}`.
     */
    fun remindDue(
        after: Instant,
        upTo: Instant,
    ): ReminderRun
}

/** What one sweep did: members whose reminder fell due, how many were asked for, how many had written, how many failed. */
data class ReminderRun(
    val due: Int,
    val requested: Int,
    val alreadyWritten: Int,
    val failed: Int,
)
