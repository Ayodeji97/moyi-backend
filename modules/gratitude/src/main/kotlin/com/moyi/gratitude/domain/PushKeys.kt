package com.moyi.gratitude.domain

import java.time.LocalDate
import java.time.temporal.IsoFields
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
     * At most one a week per member (FR-092): the ISO week of the Bond-day it
     * was asked on, so a second memory in the same week asks for nothing.
     */
    fun onThisDay(
        memberId: UUID,
        bondDate: LocalDate,
    ): String {
        val year = bondDate.get(IsoFields.WEEK_BASED_YEAR)
        val week = bondDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return "on-this-day:$memberId:$year-W${week.toString().padStart(2, '0')}"
    }
}
