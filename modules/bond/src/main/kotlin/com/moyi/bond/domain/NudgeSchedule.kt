package com.moyi.bond.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * One reminder or nudge, due.
 *
 * @property index 1 is the reminder itself, 2 and 3 its nudges.
 * @property first When the reminder (index 1) of this same day fell.
 * @property at When this one falls: [first] plus `every × (index − 1)` of elapsed time.
 */
internal data class NudgeTime(
    val index: Int,
    val first: Instant,
    val at: Instant,
)

/**
 * When a member's nudges fall (C13): each is the reminder's instant plus whole
 * elapsed hours, so three real hours apart whatever the wall clock does. The
 * reminder's own instant is [ReminderTime]'s: no second DST rule lives here.
 *
 * It knows no Bond-day; the caller applies the day rule (C12).
 *
 * The count and spacing arrive as parameters because `domain` depends on
 * nothing, and `ReminderRoster`'s companion (api) is where they live once.
 */
internal object NudgeSchedule {
    /** Every index in `1..count` whose [NudgeTime.at] fell within `(after, upTo]`. */
    @Suppress("LongParameterList") // The window, the member's clock, and the roster's two constants.
    fun dueIn(
        local: LocalTime,
        zone: ZoneId,
        after: Instant,
        upTo: Instant,
        count: Int,
        every: Duration,
    ): List<NudgeTime> =
        (1..count).mapNotNull { index ->
            val shift = every.multipliedBy((index - 1).toLong())
            val first = ReminderTime.occurrenceIn(local, zone, after.minus(shift), upTo.minus(shift))
            first?.let { NudgeTime(index, it, it.plus(shift)) }
        }
}
