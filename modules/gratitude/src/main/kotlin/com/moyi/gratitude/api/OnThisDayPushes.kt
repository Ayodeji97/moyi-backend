package com.moyi.gratitude.api

import java.time.Instant
import java.time.LocalTime

/**
 * The on-this-day push (FR-080, FR-092, ADR-0039 Q-O2, ADR-0046), asked for by
 * `scheduling` on a timer. That module knows when; this one knows whether a
 * member has anything to remember.
 */
interface OnThisDayPushes {
    /**
     * Asks for an on-this-day push for every member **who opted in** whose
     * push time ([PUSH_AT] in their reminder zone) fell within `(after, upTo]`, in a Bond that
     * takes writes, **only where `GET /bonds/{bondId}/on-this-day` would answer
     * them with at least one day** at that moment.
     *
     * Private (FR-092): what is asked is this member's own answer, which
     * holds only what they can already read; the other member's opt-in,
     * settings and pushes are never consulted, and the push names nothing.
     *
     * At most one a week per member, however many memories the week holds or
     * how often a window is asked about: the dedupe key is the member and the
     * ISO week of their Bond-day.
     */
    fun pushDue(
        after: Instant,
        upTo: Instant,
    ): OnThisDayRun

    companion object {
        /**
         * The time of day the push is asked for, in the member's reminder
         * zone: midday, away from the evening reminder and the reveal, which
         * share the Bond's daily budget (FR-082). A default of ours; the
         * owner may move it (ADR-0046, Open).
         */
        val PUSH_AT: LocalTime = LocalTime.NOON
    }
}

/** What one sweep did: members whose time fell, how many were asked for, how many had nothing to remember, how many failed. */
data class OnThisDayRun(
    val due: Int,
    val requested: Int,
    val nothingToRemember: Int,
    val failed: Int,
)
