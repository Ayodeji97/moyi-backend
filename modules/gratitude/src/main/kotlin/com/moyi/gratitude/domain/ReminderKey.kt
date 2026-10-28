package com.moyi.gratitude.domain

import java.time.LocalDate
import java.util.UUID

/**
 * The dedupe key of a member's daily reminder for one bond-day (ADR-0045):
 * the one name under which the sweep asks for it and an entry calls it off.
 *
 * `reminder:{memberId}:{bond-day label}`. The spec writes `{bondDayId}`; the
 * label stands for it because a bond-day's row is made by the first entry or
 * by the close job, and the day a member is reminded is usually one with no
 * row yet. A bond's labels are unique to it (`bond_days_bond_date_key`), and
 * a member id belongs to one bond, so the key names the same day the id would.
 */
internal object ReminderKey {
    fun of(
        memberId: UUID,
        bondDate: LocalDate,
    ): String = "reminder:$memberId:$bondDate"
}
