package com.moyi.gratitude.api

import java.time.Instant
import java.time.LocalDate

/**
 * FR-114's "for a given date", answered as a check rather than a second run (ADR-0067 decision 3):
 * how far the close job has got with the days labelled [date], across every bond. Counts only; no
 * bond, member or word. Admin-only: `ArchitectureTest` holds that nothing but `admin` reaches it.
 *
 * Uses the close job's own threshold, so "past due" means "the next run closes it". A day with no
 * row yet (nobody wrote and the job has not written it closed) is in no count.
 */
fun interface DaySettlement {
    fun on(
        date: LocalDate,
        now: Instant,
    ): DateSettlement
}

/** The days of one date: still [open], of which [pastDue] the next close run takes; and closed but not yet [uncounted] in their streak. */
data class DateSettlement(
    val open: Int,
    val pastDue: Int,
    val uncounted: Int,
)
