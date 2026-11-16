package com.moyi.gratitude.api

import java.util.UUID

/**
 * FR-074's admin operation: a bond's streak rebuilt from its evaluated days (ADR-0034 decisions 9
 * and 10, ADR-0067 decision 6). Admin-only: `ArchitectureTest` holds that nothing but `admin`
 * reaches it.
 *
 * Joins the caller's transaction and refuses to run outside one (`MANDATORY`), so that the rebuild
 * and the console's audit row commit together or not at all. It writes `streak_states` and nothing
 * else, under the streak row's lock: no day is touched and nothing is announced.
 */
interface StreakRecalculation {
    fun rebuild(bondId: UUID): StreakRebuild
}

/** What a rebuild found: [before] and [after] are null exactly when the result is [RebuildResult.NO_DAYS]. */
class StreakRebuild(
    val result: RebuildResult,
    val before: StreakFigures?,
    val after: StreakFigures?,
) {
    override fun toString(): String = "StreakRebuild($result, before=$before, after=$after)"
}

enum class RebuildResult {
    /** The stored row had drifted from its days, and now matches them. */
    CHANGED,

    /** The stored row already matched its days. */
    UNCHANGED,

    /** The bond has no evaluated day (or is no bond): there was nothing to rebuild, and nothing was written. */
    NO_DAYS,
}

/** The counts of a couple's run an admin reads to see what a rebuild did: no date, no member. */
data class StreakFigures(
    val current: Int,
    val longest: Int,
    val freezesAvailable: Int,
    val completeDays: Int,
)
