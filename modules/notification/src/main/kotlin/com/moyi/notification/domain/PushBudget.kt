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

    /**
     * Not sent, because it would leave too few of the member's cross-Bond
     * slots for the reveals still owed (ADR-0068 decision 5): a soft nudge.
     * `DROPPED_BUDGET` in the ledger, as [DROPPED] is; told apart only so
     * that it can be counted apart.
     */
    DROPPED_LEAVING_ROOM,
}

/** The member's day in their reminder zone, `[start, end)`: 23 or 25 hours long across a DST change. */
internal data class BudgetDay(
    val start: Instant,
    val end: Instant,
)

/**
 * FR-082, as a rule over counts (spec §4 N2, D-16, ADR-0045), as ADR-0068
 * amends it:
 *
 * > ≤4 pushes per Bond per member per day; ≤6 per member per day across Bonds;
 * > **one slot per Bond per day reserved for reveal-class**; over-budget
 * > non-reserved messages are dropped, not queued. Nudges 2 and 3 are sent
 * > only if, after them, a cross-Bond slot is left for each reveal still owed.
 *
 * **Reservation, not precedence.** A dispatcher sees pushes one at a time as
 * they come, so it cannot hold back a reminder at 20:00 for a reveal that may
 * come at 22:00. Instead one of a Bond's four slots is never spent on
 * anything but a reveal-class push: reminders and their nudges, milestones,
 * on-this-day, partner-joined and proposals share the other three. A
 * reveal-class push takes the reserved slot first and, if that is spent,
 * competes for the remainder.
 *
 * **Leaving room** (ADR-0068 decision 5). A push may carry a number of
 * cross-Bond slots it must leave free once it is sent: the reveals still owed
 * when a nudge 2 or 3 was asked for. Every other push carries zero, for which
 * the term is today's cross-Bond cap exactly. The caller counts what is owed;
 * this rule only spends against it.
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
    const val PER_BOND_PER_DAY = 4
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
     * @param leaveFreeAcrossBonds cross-Bond slots this push must leave free
     *   once sent; zero for all but nudges 2 and 3
     */
    fun decide(
        sentInBondToday: Int,
        reservedSpentToday: Boolean,
        sentAcrossBonds: Int,
        revealClass: Boolean,
        leaveFreeAcrossBonds: Int,
    ): BudgetDecision {
        val remainderSpent = sentInBondToday - if (reservedSpentToday) RESERVED_PER_BOND_PER_DAY else 0
        return when {
            sentAcrossBonds >= ACROSS_BONDS -> BudgetDecision.DROPPED

            // Sent, this one would make `sentAcrossBonds + 1`, and each reveal owed needs one more after it.
            sentAcrossBonds + leaveFreeAcrossBonds >= ACROSS_BONDS -> BudgetDecision.DROPPED_LEAVING_ROOM

            // Four a day in one Bond, the reserved slot among them, whatever the counts say.
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
