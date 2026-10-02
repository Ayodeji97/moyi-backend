package com.moyi.gratitude.domain

import java.time.Instant
import java.time.LocalDate

/**
 * One Bond-day as the bond's calendar draws it: the calendar label [date],
 * and the UTC span `[startsAt, endsAt)` that belongs to it (spec §3.1).
 *
 * **Not always midnight to midnight, and not always non-empty.** A westward
 * anchor change merges two calendar dates into one day of up to ~48 hours
 * (plan R3); an eastward change can skip a label entirely, and that label's
 * day is the empty span `startsAt == endsAt` (doc 04 §8.5). That is why the
 * invariant below is `!startsAt.isAfter(endsAt)` and not `startsAt < endsAt`
 * — the stricter one would refuse the first skipped date it ever met. V12's
 * `bond_days_span_check` states the same bound in SQL.
 */
internal data class DayWindow(
    val date: LocalDate,
    val startsAt: Instant,
    val endsAt: Instant,
) {
    init {
        require(!startsAt.isAfter(endsAt)) { "a day's span may be empty but never inverted: [$startsAt, $endsAt)" }
    }
}

/**
 * "Which Bond-day contains this instant" — the one question [DayAssignment]
 * needs answered about a bond's calendar, owned here rather than imported
 * from `bond.api` (ruling P7).
 *
 * `bond.api.BondAnchorTimeline` is what really answers it, but that type and
 * its `BondDayBounds` have `internal` constructors (ADR-0026), so nothing in
 * this module — its unit tests included — can build one. A domain function
 * that took it would be untestable without a Spring context, and would make
 * `gratitude.domain` depend on another module's API types. Instead the
 * service layer adapts the bond's timeline to this interface in one line
 * (`SubmitEntry`, `GetToday`), so the date arithmetic still lives only in
 * `bond.domain.AnchorTimeline` and this module never reimplements it.
 */
internal fun interface BondCalendar {
    /**
     * The day containing [at], or `null` when [at] is before the calendar
     * begins — before the bond existed, so on no day of it. Only a client's
     * claim can name such an instant (BR-3a's `intendedAt`); the submission
     * instant itself always falls on a day.
     */
    fun dayAt(at: Instant): DayWindow?
}
