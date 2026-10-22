package com.moyi.gratitude.domain

import java.time.LocalDate

/** A milestone a bond has reached: [threshold] complete days, the last of them on [achievedOn]. */
internal data class Milestone(
    val threshold: Int,
    val achievedOn: LocalDate,
)

/**
 * FR-075's milestones, as the owner ruled them on 2026-10-22 (ADR-0041): **complete days**,
 * days both wrote on, counted over the bond's whole life (Q-M1). The count never goes down, so
 * nothing reached is ever lost; a day a freeze covered and a date a zone change stepped over
 * extend the run and are not complete days (ADR-0034 decision 5), so no milestone celebrates a
 * day somebody missed. Exactly four thresholds, and nothing after the last (Q-M2).
 *
 * Asked by the close job's evaluation, which announces a crossing (`EvaluateStreaks`), and by
 * the read, which dates what was reached and names what is next (`GetMilestones`). Pure, as
 * [StreakRules] is: no clock, no store.
 */
internal object Milestones {
    private const val A_WEEK = 7
    private const val A_MONTH = 30
    private const val A_HUNDRED = 100
    private const val A_YEAR = 365

    /** Oldest first. The contract states them in words (`MilestoneDocumentation.kt`): change both. */
    val THRESHOLDS: List<Int> = listOf(A_WEEK, A_MONTH, A_HUNDRED, A_YEAR)

    /** The thresholds a step from [before] complete days to [after] takes the bond across. */
    fun crossed(
        before: Int,
        after: Int,
    ): List<Int> = THRESHOLDS.filter { it > before && it <= after }

    /** The first threshold above [total], or `null` once all four are reached (Q-M2). Not a countdown: no count goes with it (Q-M3). */
    fun next(total: Int): Int? = THRESHOLDS.firstOrNull { it > total }

    /**
     * Every threshold at or below [total], oldest first, each dated by [dates]: the date of the
     * day that was that threshold's complete day, by its place among them.
     */
    fun achieved(
        total: Int,
        dates: Map<Int, LocalDate>,
    ): List<Milestone> =
        THRESHOLDS.filter { it <= total }.map { threshold ->
            Milestone(threshold, checkNotNull(dates[threshold]) { "a reached threshold has its day's date: $threshold" })
        }
}
