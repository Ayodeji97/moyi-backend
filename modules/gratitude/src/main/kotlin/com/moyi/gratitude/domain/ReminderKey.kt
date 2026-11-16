package com.moyi.gratitude.domain

import java.time.LocalDate
import java.util.UUID

/**
 * The dedupe keys of a member's reminders for one bond-day (ADR-0045, and
 * #200's nudges, spec C15): the one place their format lives, under which the
 * sweep asks for each and an entry calls the whole series off.
 *
 * Nudge 1, the reminder itself, keeps the key it had before nudges existed,
 * `reminder:{memberId}:{bond-day label}`, so a reminder in flight at the
 * deploy is still called off by writing and is not asked twice that day.
 * Nudge *n* ≥ 2 is that key with `:{n}` after it. The keys also bound a day:
 * each index can be asked for once, whatever the member's reminder time does.
 *
 * The spec writes `{bondDayId}`; the label stands for it because a bond-day's
 * row is made by the first entry or by the close job, and the day a member is
 * reminded is usually one with no row yet. A bond's labels are unique to it
 * (`bond_days_bond_date_key`), and a member id belongs to one bond, so the key
 * names the same day the id would.
 *
 * Domain depends on nothing, so the series' length is the caller's: the
 * sweep and the write both pass `ReminderRoster.NUDGES_PER_BOND_DAY`, the one
 * place it is written.
 */
internal object ReminderKey {
    fun of(
        memberId: UUID,
        bondDate: LocalDate,
        nudge: Int,
    ): String {
        require(nudge >= 1) { "a nudge's index is 1 or more" }
        val first = "reminder:$memberId:$bondDate"
        return if (nudge == 1) first else "$first:$nudge"
    }

    /** Every key of the day's series, nudges 1 to [count]: what a write calls off. */
    fun series(
        memberId: UUID,
        bondDate: LocalDate,
        count: Int,
    ): List<String> = (1..count).map { of(memberId, bondDate, it) }

    /** The keys of the nudges before [nudge] the same day: what it supersedes if still unsent. */
    fun before(
        memberId: UUID,
        bondDate: LocalDate,
        nudge: Int,
    ): Set<String> = (1 until nudge).mapTo(linkedSetOf()) { of(memberId, bondDate, it) }
}
