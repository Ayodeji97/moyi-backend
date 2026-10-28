package com.moyi.notification.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What FR-082's budget makes of one push. */
internal enum class BudgetDecision {
    /** Sent, in the Bond's one reveal-class slot for the day. */
    RESERVED,

    /** Sent, in one of the slots anything may take. */
    REMAINDER,

    /** Not sent, and never queued: `DROPPED_BUDGET`. */
    DROPPED,
}

/** The member's day in their reminder zone, `[start, end)`: 23 or 25 hours long across a DST change. */
internal data class BudgetDay(
    val start: Instant,
    val end: Instant,
)

/**
 * FR-082, as a rule over counts (spec §4 N2, D-16, ADR-0045).
 *
 * > ≤3 pushes per Bond per member per day; ≤6 per member per day across Bonds;
 * > **one slot per Bond per day reserved for reveal-class**; over-budget
 * > non-reserved messages are dropped, not queued.
 *
 * **Reservation, not precedence.** A dispatcher sees pushes one at a time as
 * they come, so it cannot hold back a reminder at 20:00 for a reveal that may
 * come at 22:00. Instead one of a Bond's three slots is never spent on
 * anything but a reveal-class push: reminders, milestones, on-this-day,
 * partner-joined and proposals share the other two. A reveal-class push takes
 * the reserved slot first and, if that is spent, competes for the remainder.
 *
 * **Two different days** (spec D-16). The per-Bond cap counts the member's
 * day in **that Bond's** reminder zone, because a member has a zone per Bond
 * and the user none. The cross-Bond cap counts a rolling 24 hours, because no
 * one zone's day is the member's. **The cross-Bond cap applies to every push,
 * reveal-class included** (the spec's reading): three Bonds' remainders are
 * six, and can use it up before a reveal comes. ADR-0045 records that
 * consequence for the owner.
 *
 * Only pushes that were **sent** count: one suppressed, held, dropped,
 * expired or failed spent nothing.
 */
internal object PushBudget {
    const val PER_BOND_PER_DAY = 3
    const val RESERVED_PER_BOND_PER_DAY = 1
    const val ACROSS_BONDS = 6
    private const val ACROSS_BONDS_WINDOW_HOURS = 24L
    val ACROSS_BONDS_WINDOW: Duration = Duration.ofHours(ACROSS_BONDS_WINDOW_HOURS)

    /**
     * @param sentInBondToday pushes of this Bond sent to this member in their
     *   [BudgetDay], the reserved one among them
     * @param reservedSpentToday whether one of those took the reserved slot
     * @param sentAcrossBonds pushes sent to this member in the last
     *   [ACROSS_BONDS_WINDOW], in every Bond
     */
    fun decide(
        sentInBondToday: Int,
        reservedSpentToday: Boolean,
        sentAcrossBonds: Int,
        revealClass: Boolean,
    ): BudgetDecision {
        val remainderSpent = sentInBondToday - if (reservedSpentToday) RESERVED_PER_BOND_PER_DAY else 0
        return when {
            sentAcrossBonds >= ACROSS_BONDS -> BudgetDecision.DROPPED

            // Three a day in one Bond, the reserved slot among them, whatever the counts say.
            sentInBondToday >= PER_BOND_PER_DAY -> BudgetDecision.DROPPED

            revealClass && !reservedSpentToday -> BudgetDecision.RESERVED

            remainderSpent < PER_BOND_PER_DAY - RESERVED_PER_BOND_PER_DAY -> BudgetDecision.REMAINDER

            else -> BudgetDecision.DROPPED
        }
    }

    /** The day in [zone] that [instant] falls in. Midnight is asked of the zone, which moves it where a DST change skips it. */
    fun dayOf(
        instant: Instant,
        zone: ZoneId,
    ): BudgetDay {
        val date = instant.atZone(zone).toLocalDate()
        return BudgetDay(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
    }
}
