package com.moyi.gratitude.service

import com.moyi.gratitude.domain.StreakRules
import com.moyi.gratitude.domain.StreakState
import com.moyi.gratitude.infra.database.StreakStore
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * FR-074: a bond's streak, rebuilt from its days alone — "MUST produce
 * identical results".
 *
 * It folds [StreakRules] over the bond's evaluated days, oldest first,
 * **replaying what was decided for each** and deciding nothing: what the day
 * was to the streak, whether Strict mode was on, whether it spent a freeze
 * are all read off the day (V17). So Strict mode switched on or off since
 * changes nothing here (FR-073), and a day's status changing under it — a
 * missed day made `FROZEN` by a freeze — does not change what it replays as.
 * It is the same function the close job folds with, which is the only reason
 * to believe the two agree.
 *
 * **It writes `streak_states` and nothing else.** No day is touched, no event
 * is written: a recalculation that announced the streak again would send two
 * people a second "your streak grew". It takes the bond's streak row, so it
 * cannot interleave with an evaluation.
 *
 * A service and not yet a route: FR-074 calls it an admin operation, and
 * there is no admin surface or role to put it behind (ADR-0034).
 */
@Service
internal class RecalculateStreak(
    private val streaks: StreakStore,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    /** What the bond's streak was before, and is now. Equal, unless the stored row had drifted from its days. */
    data class Recalculated(
        val before: StreakState,
        val after: StreakState,
    ) {
        val changed: Boolean get() = before != after
    }

    fun recalculate(bondId: UUID): Recalculated =
        checkNotNull(
            transactions.execute {
                val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                val before = streaks.lock(bondId, now)
                val after =
                    streaks.evaluatedDays(bondId).fold(StreakState.NONE) { state, day ->
                        StreakRules.step(state, day.date, day.outcome, day.strict, recordedFreeze = day.freezeApplied).state
                    }
                streaks.save(bondId, after, now, recomputed = true)
                Recalculated(before, after)
            },
        )
}
