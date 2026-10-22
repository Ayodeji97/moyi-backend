package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.Milestone
import com.moyi.gratitude.domain.Milestones
import com.moyi.gratitude.domain.StreakCell
import com.moyi.gratitude.infra.database.StreakCalendar
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** What a bond has reached (FR-075): each milestone with the date of the day that reached it, oldest first, and the next one to aim at. */
internal data class MilestonesView(
    val achieved: List<Milestone>,
    /** `null` once all four are reached, and whenever the caller's membership takes no writes. */
    val next: Int?,
)

/**
 * `GET /bonds/{bondId}/milestones` (ADR-0041).
 *
 * **Dated by the bond's days.** The Nth day evaluated `COMPLETE` is the day that reached N
 * (decision 2); `streak_states` is not read. **Today is added by `GET /streak`'s own rule**
 * ([GetStreak.countsToday]): once it has revealed, and only when it follows the last evaluated
 * day. Its square is read as `GetStreak.detail` reads it, from [StreakCalendar.between].
 *
 * **`next` is `null` when the membership takes no writes** (decision 7): ended, left, blocked
 * and the deletion countdown alike, so nothing says which. What was reached stays. Asked of the
 * membership **resolved again here** ([membershipNow]), after the joining-day reconcile, not of
 * the guard's: an ending can commit while the reconcile waits on the bond's lock, and the
 * guard's membership would still take writes (as `GetDays.onThisDay`; C6b final review m2).
 *
 * **It remembers nothing about who asked** (H-M1): read-only, and handed the bond's access
 * port, the calendar, the streak's rule and the clock, nothing else (`ArchitectureTest` holds
 * the list). Whether a milestone is new to somebody is their device's to know, from `achievedOn`.
 */
@Service
internal class GetMilestones(
    private val access: BondAccess,
    private val calendar: StreakCalendar,
    private val streak: GetStreak,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun of(membership: BondMembership): MilestonesView {
        val now = access.membershipNow(membership)
        val today = todayOf(membership, clock)
        // The timeline, then today's square, then (in countsToday) the last evaluated day. An
        // evaluation committing in between can leave one answer a day behind; nothing is counted
        // twice, for today is never evaluated while it is today (ADR-0034, Consequences).
        val evaluated = calendar.completeDays(membership.bondId, Milestones.THRESHOLDS)
        val todayComplete = calendar.between(membership.bondId, today, today, today).singleOrNull()?.cell == StreakCell.COMPLETE
        val counted = streak.countsToday(membership, today, todayComplete)
        val total = if (counted) evaluated.total + 1 else evaluated.total
        val dates = if (counted) evaluated.dates + (total to today) else evaluated.dates
        return MilestonesView(
            achieved = Milestones.achieved(total, dates),
            next = if (now.takesWrites) Milestones.next(total) else null,
        )
    }
}
