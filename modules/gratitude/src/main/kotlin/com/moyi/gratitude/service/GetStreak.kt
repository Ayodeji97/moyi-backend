package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.DayOutcome
import com.moyi.gratitude.domain.LateEntryGrace
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
    /** [StreakState.lastRunLength]: the run the latest break ended. Today never changes it. */
    val lastRunLength: Int?,
    val lastRunBrokenOn: LocalDate?,
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
 * evaluated; what is shown adds the undecided days that have already
 * revealed** (FR-071: consecutive complete days "ending at today or
 * yesterday"). An undecided day is today, or an earlier day still inside its
 * late-entry grace (BR-3a as amended 2026-11-08): the close job settles a day
 * only twelve hours after it ends, so until noon or so yesterday is not
 * evaluated either. A couple at thirty who both wrote yesterday and today see
 * thirty-two this morning, not thirty until yesterday settles and thirty-one
 * after. A revealed day is complete whatever the close later decides, so this
 * is the number the job will store. They are added oldest first, only while
 * each directly follows the last evaluated day (or the one added before it),
 * or is the bond's first day as two people with nothing evaluated yet, and
 * only while each has revealed: the first undecided day that has not stops
 * the walk, because the run cannot pass a day that is still open. If the
 * last settled day has not been evaluated, the job is a minute or a run
 * behind, and the stored run is shown as it is until it has caught up.
 *
 * **Nothing else is worked out at read time.** A run that lapsed reads zero
 * because the close job wrote the missed days and evaluated them, not because
 * a read noticed the date. One place decides the streak.
 *
 * **Read in this order: the stored run, the last evaluated date, then the
 * squares** ([pending]). The reads are separate statements, and the close job
 * may evaluate a day between them. In this order that can only leave the
 * number one day short for a moment, never count a day twice: a day evaluated
 * after the run was read is either already in the last evaluated date, so the
 * walk starts after it, or not yet, and then its square is read as evaluated,
 * no longer undecided, and the walk stops at it.
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
    private val grace: LateEntryGrace,
) {
    /**
     * The run as stored, the last evaluated date, and the undecided days that have revealed
     * and extend it, oldest first — read in that order (see the class KDoc).
     */
    data class Pending(
        val stored: StreakState,
        val lastEvaluated: LocalDate?,
        val complete: List<LocalDate>,
    )

    @Transactional(readOnly = true)
    fun detail(membership: BondMembership): StreakDetail {
        val now = clock.instant()
        val today = todayOf(membership, now)
        val undecided = grace.undecidedDaysOf(membership, now)
        // An ended bond's calendar ends where the bond did, and stays: its
        // record is kept "permanently" (doc 04 §8.3), not until it scrolls
        // off the end of a window that follows the clock.
        val last = membership.endedAt?.let(membership.anchorTimeline::dateAt)?.takeIf { it.isBefore(today) } ?: today
        val from = maxOf(last.minusDays(CALENDAR_DAYS - 1), joiningDate(membership) ?: last.plusDays(1))
        val state = streaks.find(membership.bondId) ?: StreakState.NONE
        val lastEvaluated = streaks.lastEvaluatedDate(membership.bondId)
        // OPEN is "this day can still be completed". A bond that ended today
        // with one entry in keeps that day's row unsettled until its grace has
        // run out; nobody can write on it, so it is not drawn as open in the meantime.
        val stored =
            calendar
                .between(membership.bondId, from, last, undecided)
                .filter { membership.isOpen || it.cell != StreakCell.OPEN }
        // An undecided day has a row only once somebody has written. Drawn only
        // then, the square's presence would say that somebody has; so a bond two
        // people can write in always has today's square, and yesterday's while
        // its grace runs, and each says OPEN until it is complete.
        val drawn = stored.mapTo(mutableSetOf()) { it.date }
        val unwritten =
            if (membership.isOpen) {
                undecided
                    .filterKeys { !it.isBefore(from) && !it.isAfter(last) && it !in drawn }
                    .map { (date, settlesAt) -> StreakDay(date, StreakCell.OPEN, settlesAt = settlesAt) }
            } else {
                emptyList()
            }
        val days = (stored + unwritten).sortedBy { it.date }
        val pending = Pending(state, lastEvaluated, walk(membership, lastEvaluated, days.filter { it.date in undecided }))
        return StreakDetail(shown(membership, pending), days)
    }

    /** The streak as `GET /today` shows it: [detail]'s numbers, without the calendar. Reads; the caller owns the transaction. */
    fun view(membership: BondMembership): StreakView = shown(membership, pending(membership))

    /**
     * The stored run and the undecided days that extend it: the one rule for what is added,
     * which [view] applies to the numbers and `GetMilestones` to the milestones (ADR-0041
     * decision 4), so that the two screens never disagree. A day is added once it has
     * **revealed** — a day both have written that waits for its reveal time is not complete yet.
     * Reads; the caller owns the transaction.
     */
    fun pending(membership: BondMembership): Pending {
        val now = clock.instant()
        val undecided = grace.undecidedDaysOf(membership, now)
        val state = streaks.find(membership.bondId) ?: StreakState.NONE
        val lastEvaluated = streaks.lastEvaluatedDate(membership.bondId)
        val squares =
            undecided.keys.firstOrNull()?.let { first ->
                calendar.between(membership.bondId, first, undecided.keys.last(), undecided)
            } ?: emptyList()
        return Pending(state, lastEvaluated, walk(membership, lastEvaluated, squares))
    }

    /** The date of the bond's last evaluated day, read now: what `GetMilestones` trims its pending days by. */
    fun lastEvaluatedDate(membership: BondMembership): LocalDate? = streaks.lastEvaluatedDate(membership.bondId)

    /** The undecided [squares] that extend the run past [lastEvaluated]: see the class KDoc. */
    private fun walk(
        membership: BondMembership,
        lastEvaluated: LocalDate?,
        squares: List<StreakDay>,
    ): List<LocalDate> {
        val joining = joiningDate(membership)
        var expected = if (lastEvaluated == null || (joining != null && joining.isAfter(lastEvaluated))) joining else lastEvaluated.plusDays(1)
        val added = mutableListOf<LocalDate>()
        for (square in squares.sortedBy { it.date }) {
            // Already in the stored run: a day the job settled early, by a clock ahead of this one's.
            if (expected != null && square.date.isBefore(expected)) continue
            if (expected == null || square.date != expected || square.cell != StreakCell.COMPLETE) break
            added += square.date
            expected = square.date.plusDays(1)
        }
        return added
    }

    private fun shown(
        membership: BondMembership,
        pending: Pending,
    ): StreakView {
        // Each added day by the same rule the close job will apply to it once
        // it settles, so every number moves together: on a fourteenth day the
        // freeze shows as earned beside the fourteen, not hours later.
        val state =
            pending.complete.fold(pending.stored) { run, date ->
                StreakRules.step(run, date, DayOutcome.COMPLETE, membership.strictMode).state
            }
        return StreakView(
            current = state.current,
            longest = state.longest,
            freezesAvailable = state.freezesAvailable,
            freezeProgress = state.freezeProgress,
            strictMode = membership.strictMode,
            totalCompleteDays = state.totalCompleteDays,
            lastCompleteDate = state.lastCompleteDate,
            lastRunLength = state.lastRunLength,
            lastRunBrokenOn = state.lastRunBrokenOn,
        )
    }

    private fun joiningDate(membership: BondMembership): LocalDate? = membership.activeSince?.let(membership.anchorTimeline::dateAt)

    private companion object {
        /** Fifty-three weeks: a year of the calendar with whole weeks at both ends. */
        const val CALENDAR_DAYS = 371L
    }
}
