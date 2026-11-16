package com.moyi.bond.api

import com.moyi.notification.api.PushRecipient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Whose daily reminder fell when (FR-080, FR-081, spec §4 N2, ADR-0045): what
 * `gratitude`'s reminder sweep asks of `bond`, which keeps every member's
 * reminder time, zone, quiet hours and toggles.
 *
 * **Unguarded, like the closer's view of a bond.** It answers a timer, not a
 * request, and it names members of every bond. `ArchitectureTest` holds that
 * only the reminder sweep calls it.
 */
interface ReminderRoster {
    /**
     * Every reminder and nudge that fell within `(after, upTo]` (#200): for each
     * member in a bond that takes entries, who has not left it and whose bond
     * is not paused, each index of their series whose instant is in the window.
     * Nudge 1 falls at their `reminder_time_local` in their `reminder_timezone`;
     * nudge *n* [NUDGE_EVERY] × (*n* − 1) of elapsed time after it (C13).
     *
     * Each with the bond-day its nudge falls in, by the bond's own calendar
     * (the anchor's, not the member's zone: the day an entry would be written
     * to), and their settings resolved for the push. A series lies in one
     * bond-day (C12): a nudge is due only if nudge 1 fell at or after the start
     * of the bond-day the nudge falls in, so none falls after its day's end,
     * and none is due on a day whose nudge 1 fell before the bond began.
     *
     * Whether they have written that day is not asked here: entries are
     * `gratitude`'s.
     */
    fun remindersDueBetween(
        after: Instant,
        upTo: Instant,
    ): List<DueNudge>

    companion object {
        /** Reminders a member may be sent in one Bond-day: the first, then two nudges (C12). */
        const val NUDGES_PER_BOND_DAY = 3

        /** Elapsed time between one reminder and the next, not wall-clock time (C13). */
        val NUDGE_EVERY: Duration = Duration.ofHours(3)
    }
}

/**
 * One reminder or nudge, due (#200). `internal constructor`, as [DueReminder]'s.
 *
 * @property reminder Whose, in which bond, for which bond-day; its
 *   [DueReminder.remindAt] is this nudge's own instant.
 * @property index 1 is the reminder itself; 2 and 3 its nudges.
 * @property revealDays For a nudge 2 or 3, the bond-days the member's user is
 *   owed a reveal for at this nudge's instant, before `notification` is asked
 *   which are settled (C17): every bond of theirs, this one included, that is
 *   `ACTIVE`, not paused, where they want reveals, and that has begun. Empty
 *   for nudge 1, which no cap may drop.
 */
class DueNudge internal constructor(
    val reminder: DueReminder,
    val index: Int,
    val revealDays: List<RevealDay>,
) {
    /** Ids only, as [DueReminder]'s. */
    override fun toString(): String = "DueNudge(bondId=${reminder.bondId}, memberId=${reminder.memberId}, index=$index)"
}

/** A bond-day a reveal may still be owed for (C17). `internal constructor`, as [DueReminder]'s. */
class RevealDay internal constructor(
    val bondId: UUID,
    val bondDate: LocalDate,
) {
    /** Ids only: which day a person has not yet seen revealed is theirs. */
    override fun toString(): String = "RevealDay(bondId=$bondId)"
}

/**
 * One member's reminder, due. `internal constructor`, as [BondMembership]'s:
 * a caller outside `bond` may read one and cannot make one.
 *
 * @property bondDate The bond-day's calendar label at [remindAt].
 * @property bondDayEndsAt When that bond-day ends: a reminder still unsent
 *   then is about a day that can no longer be written to.
 */
@Suppress("LongParameterList") // Seven facts, each named at the one place that builds this.
class DueReminder internal constructor(
    val bondId: UUID,
    val memberId: UUID,
    val userId: UUID,
    val remindAt: Instant,
    val bondDate: LocalDate,
    val bondDayEndsAt: Instant,
    val recipient: PushRecipient,
) {
    /** Ids only: when somebody is reminded, and their settings, are theirs (doc 18 §5). */
    override fun toString(): String = "DueReminder(bondId=$bondId, memberId=$memberId)"
}
