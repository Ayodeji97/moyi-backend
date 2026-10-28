package com.moyi.bond.api

import com.moyi.notification.api.PushRecipient
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
     * Every member whose `reminder_time_local`, in their `reminder_timezone`,
     * fell within `(after, upTo]`, in a bond that takes entries, who has not
     * left it. Each with the bond-day their reminder fell in, by the bond's
     * own calendar (the anchor's, not the member's zone: the day an entry
     * would be written to), and their settings resolved for the push.
     *
     * Whether they have written that day is not asked here: entries are
     * `gratitude`'s.
     */
    fun remindersDueBetween(
        after: Instant,
        upTo: Instant,
    ): List<DueReminder>
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
