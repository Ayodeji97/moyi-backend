package com.moyi.gratitude.domain

import java.time.LocalDate
import java.util.UUID

/**
 * The dedupe keys of the pushes this module asks for (spec §4 N3, ADR-0046),
 * beside [ReminderKey]: each names one push to one member, so the same fact
 * asked about twice (an event delivered twice, a sweep that overlaps the
 * last) asks for nothing the second time.
 *
 * The user id, not the member id, ends the Bond-day's keys, as the spec writes
 * them: a Bond-day belongs to one Bond, so the two name the same person there.
 */
internal object PushKeys {
    fun reveal(
        bondDayId: BondDayId,
        userId: UUID,
    ): String = "reveal:${bondDayId.value}:$userId"

    /** A day that closed with one entry, opened to the partner who did not write (Q8). */
    fun soloUnlock(
        bondDayId: BondDayId,
        userId: UUID,
    ): String = "solo:${bondDayId.value}:$userId"

    /** One per day, however many thresholds it crossed (ADR-0041 Owed). */
    fun milestone(
        bondDayId: UUID,
        userId: UUID,
    ): String = "milestone:$bondDayId:$userId"

    /**
     * One per member per Bond-day: the sweep's overlapping windows ask for
     * nothing twice. FR-092's "at most one a week" is not this key's: it is a
     * rolling seven days counted from the ledger, where a push that reached
     * nobody does not spend it (ADR-0046 decision 8). A week's key spent by a
     * push that then expired in quiet hours took the member's week with it.
     */
    fun onThisDay(
        memberId: UUID,
        bondDate: LocalDate,
    ): String = "on-this-day:$memberId:$bondDate"
}
