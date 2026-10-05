package com.moyi.bond.domain

import java.time.Instant

/** One real change of a bond's Strict mode: [strictMode] is the setting from [at] on. */
internal data class StrictModeChange(
    val at: Instant,
    val strictMode: Boolean,
)

/**
 * A bond's Strict mode over time (FR-073: "toggling Strict mode never alters
 * past days"), so that a day can be judged by the setting it ended under and
 * not by the one in force when something got round to judging it.
 *
 * [changes] are real changes, oldest first: each one's value differs from the
 * one before it. So the setting before the first is the opposite of the
 * first's, and a bond with none has had [current] all along.
 */
internal class StrictModeHistory(
    private val current: Boolean,
    changes: List<StrictModeChange>,
) {
    private val changes = changes.sortedBy { it.at }

    /**
     * The setting in force up to [instant], not including it. A day is
     * `[startsAt, endsAt)`, so a change stamped exactly at a day's end
     * belongs to the next day and does not touch the one that just ended.
     */
    fun before(instant: Instant): Boolean {
        val last = changes.lastOrNull { it.at.isBefore(instant) }
        val first = changes.firstOrNull()
        return when {
            last != null -> last.strictMode
            first != null -> !first.strictMode
            else -> current
        }
    }
}
