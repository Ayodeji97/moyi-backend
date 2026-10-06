package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.DayAssignment
import com.moyi.gratitude.domain.DayOutcome
import com.moyi.gratitude.domain.StreakCell
import com.moyi.gratitude.domain.StreakDay
import com.moyi.gratitude.domain.StreakRules
import com.moyi.gratitude.domain.StreakState
import com.moyi.gratitude.infra.database.StreakCalendar
import com.moyi.gratitude.infra.database.StreakStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** A bond's streak as a member is shown it: the stored run, with today in it when today is complete. */
internal data class StreakView(
    val current: Int,
    val longest: Int,
    val freezesAvailable: Int,
    val freezeProgress: Int,
    val strictMode: Boolean,
    val totalCompleteDays: Int,
    val lastCompleteDate: LocalDate?,
)

internal data class StreakDetail(
    val streak: StreakView,
    val days: List<StreakDay>,
)

/**
 * `GET /bonds/{bondId}/streak`, and the `streak` object on `GET /today`
 * (spec §5.1, §5.2, §6.5).
 *
 * **What is stored is the run through the last day the close job has
 * evaluated; what is shown adds today, when today is complete** (FR-071:
 * consecutive complete days "ending at today or yesterday"). A couple at
 * thirty who have both written today see thirty-one today, not at midnight.
 * Today is added only when it directly follows the last evaluated day — or
 * is the bond's first day as two people, with nothing evaluated yet. If
 * yesterday has not been evaluated, the job is a minute or a run behind, and
 * the stored run is shown as it is until it has caught up.
 *
 * **Nothing else is worked out at read time.** A run that lapsed reads zero
 * because the close job wrote the missed days and evaluated them, not because
 * a read noticed the date. One place decides the streak.
 *
 * **The calendar starts on the day the bond became two people.** Earlier
 * days have a row only where the creator wrote while waiting, and are
 * private (spec §12.4): showing them would tell the partner which days those
 * were.
 */
@Service
internal class GetStreak(
    private val streaks: StreakStore,
    private val calendar: StreakCalendar,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun detail(membership: BondMembership): StreakDetail {
        val today = today(membership)
        // An ended bond's calendar ends where the bond did, and stays: its
        // record is kept "permanently" (doc 04 §8.3), not until it scrolls
        // off the end of a window that follows the clock.
        val last = membership.endedAt?.let(membership.anchorTimeline::dateAt)?.takeIf { it.isBefore(today) } ?: today
        val from = maxOf(last.minusDays(CALENDAR_DAYS - 1), joiningDate(membership) ?: last.plusDays(1))
        val stored = calendar.between(membership.bondId, from, last, today)
        // Today has a row only once somebody has written. Drawn only then,
        // the square's presence would say that somebody has; so a bond two
        // people can write in always has today's square, and it says OPEN.
        val unwritten = last == today && !from.isAfter(today) && membership.isOpen && stored.lastOrNull()?.date != today
        val days = if (unwritten) stored + StreakDay(today, StreakCell.OPEN) else stored
        val todayComplete = days.lastOrNull()?.takeIf { it.date == today }?.cell == StreakCell.COMPLETE
        return StreakDetail(view(membership, today, todayComplete), days)
    }

    /** The streak for [today]. [todayComplete]: both have written today. Reads; the caller owns the transaction. */
    fun view(
        membership: BondMembership,
        today: LocalDate,
        todayComplete: Boolean,
    ): StreakView {
        val stored = streaks.find(membership.bondId) ?: StreakState.NONE
        // Today, by the same rule the close job will apply to it tonight, so
        // every number moves together: on a fourteenth day the freeze shows
        // as earned beside the fourteen, not at midnight.
        val state =
            if (todayComplete && todayFollows(membership, today)) {
                StreakRules.step(stored, today, DayOutcome.COMPLETE, membership.strictMode).state
            } else {
                stored
            }
        return StreakView(
            current = state.current,
            longest = state.longest,
            freezesAvailable = state.freezesAvailable,
            freezeProgress = state.freezeProgress,
            strictMode = membership.strictMode,
            totalCompleteDays = state.totalCompleteDays,
            lastCompleteDate = state.lastCompleteDate,
        )
    }

    /** Whether a complete today extends the stored run: see the class KDoc. An evaluated today is already in it. */
    private fun todayFollows(
        membership: BondMembership,
        today: LocalDate,
    ): Boolean =
        when (val last = streaks.lastEvaluatedDate(membership.bondId)) {
            null -> joiningDate(membership) == today
            else -> last == today.minusDays(1) || (last.isBefore(today) && joiningDate(membership) == today)
        }

    private fun today(membership: BondMembership): LocalDate =
        DayAssignment.dateFor(clock.instant(), null, membership.anchorTimeline.asCalendar()) { false }

    private fun joiningDate(membership: BondMembership): LocalDate? = membership.activeSince?.let(membership.anchorTimeline::dateAt)

    private companion object {
        /** Fifty-three weeks: a year of the calendar with whole weeks at both ends. */
        const val CALENDAR_DAYS = 371L
    }
}
