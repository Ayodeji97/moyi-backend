package com.moyi.gratitude.service

import com.moyi.gratitude.api.RebuildResult
import com.moyi.gratitude.api.StreakFigures
import com.moyi.gratitude.api.StreakRebuild
import com.moyi.gratitude.api.StreakRecalculation
import com.moyi.gratitude.domain.StreakState
import com.moyi.gratitude.infra.database.StreakStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [StreakRecalculation] over [RecalculateStreak], which does the work and whose transaction joins
 * this one. A bond with no evaluated day is answered [RebuildResult.NO_DAYS] before anything is
 * locked, so the operation is never a way to make a streak row (as `RecalculateStreak` itself refuses).
 */
@Service
internal class StreakRecalculationService(
    private val recalculate: RecalculateStreak,
    private val streaks: StreakStore,
) : StreakRecalculation {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun rebuild(bondId: UUID): StreakRebuild {
        if (streaks.evaluatedDays(bondId).isEmpty()) return StreakRebuild(RebuildResult.NO_DAYS, null, null)
        val done = recalculate.recalculate(bondId)
        return StreakRebuild(
            if (done.changed) RebuildResult.CHANGED else RebuildResult.UNCHANGED,
            figures(done.before),
            figures(done.after),
        )
    }

    private fun figures(state: StreakState) = StreakFigures(state.current, state.longest, state.freezesAvailable, state.totalCompleteDays)
}
