package com.moyi.gratitude.domain

import java.time.Duration
import java.time.Instant

/**
 * BR-3a's late-entry grace (the owner's ruling of 2026-11-08, FR-045 as amended): how long a
 * Bond-day stays open after it ends, and how old an `intendedAt` may be and still be used.
 *
 * **One length, two rules, on purpose.** [DayAssignment.resolve] trusts a claim no older than
 * [length] before the submission, and the close job settles a day no sooner than [length] after
 * it ends ([closesDaysEndedBy]). Because they are the same length, a claim the first rule accepts
 * always names a day the second has not settled: the claim lies inside its day, so the day ends
 * after it, which is less than [length] before now. Two settings could be set apart, and a
 * claim accepted by one would then find its day closed by the other — the very fault this grace
 * exists to remove (an entry written at 23:00 offline and sent at 07:00 filed on the day it was
 * sent). That is why there is no separate "offline window" any more.
 *
 * Pure, like the rest of `domain`: the length comes from `moyi.gratitude.days.late-entry-grace`
 * (default twelve hours), read once by `service.DayProperties`.
 */
internal data class LateEntryGrace(
    val length: Duration,
) {
    init {
        require(!length.isNegative) { "a grace is never negative: $length" }
    }

    /** When a day that ends at [endsAt] is decided: missed or not, a rest day or not, its notifications. */
    fun settlesAt(endsAt: Instant): Instant = endsAt.plus(length)

    /** The oldest `intendedAt` still trusted for a submission at [submittedAt]. */
    fun oldestClaimAt(submittedAt: Instant): Instant = submittedAt.minus(length)

    /** The close job, at [now], settles only days that had ended by the instant this returns. */
    fun closesDaysEndedBy(now: Instant): Instant = now.minus(length)

    /**
     * The days of [calendar] still undecided at [now], oldest first: today, and every earlier
     * day whose grace has not run out. Each is asked of the calendar at the instant before the
     * next one starts, never worked out from a zone's midnight, so a day a zone change merged or
     * clipped is the calendar's own; a date it stepped over has no window and is not here.
     * Empty only when the calendar does not cover [now].
     */
    fun undecidedDays(
        calendar: BondCalendar,
        now: Instant,
    ): List<DayWindow> {
        val today = calendar.dayAt(now) ?: return emptyList()
        val days = ArrayDeque(listOf(today))
        while (true) {
            val earlier = calendar.dayAt(days.first().startsAt.minusNanos(1)) ?: break
            if (!settlesAt(earlier.endsAt).isAfter(now) || earlier.date >= days.first().date) break
            days.addFirst(earlier)
        }
        return days.toList()
    }

    companion object {
        /**
         * For a resolution that makes no claim (`intendedAt` is `null`): a read asking which day
         * today is. The length is never consulted there, and is zero so that nothing could be
         * filed early by mistake if it were.
         */
        val NONE = LateEntryGrace(Duration.ZERO)
    }
}
