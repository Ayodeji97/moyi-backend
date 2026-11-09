package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.Milestone
import com.moyi.gratitude.domain.Milestones
import com.moyi.gratitude.infra.database.StreakCalendar
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
 * (decision 2); `streak_states` is not read. **The undecided days are added by `GET /streak`'s
 * own rule** ([GetStreak.pending]): today, and yesterday while its late-entry grace runs (BR-3a
 * as amended 2026-11-08), each once it has revealed, and only while each follows the last
 * evaluated day or the one added before it. Their squares are read as `GetStreak.detail` reads
 * them, from [StreakCalendar.between].
 *
 * **What is pending is decided before the timeline is read, and trimmed after** (review I1, and
 * the grace). The reads are separate statements at `READ COMMITTED`, and the close job may
 * commit an evaluation between them. Before the grace, today was never evaluated while it was
 * today, so reading "does today count" first was enough. Now yesterday can be pending and be
 * evaluated a moment later, so a pending day is dropped if the last evaluated date read after
 * the timeline has reached it: a day the timeline counted is never counted again. A commit
 * between the timeline and that last read can leave the total one short for a moment, the
 * same tolerance as before, never one over ("7 reached today" for the day `MilestoneReached`
 * dates yesterday, an answer neither moment gives) (ADR-0041 decision 5).
 *
 * **`next` is `null` when the membership takes no writes** (decision 7): ended, left, blocked
 * and the deletion countdown alike, so nothing says which. What was reached stays. Asked of the
 * membership **resolved again here** ([membershipNow]), after the joining-day reconcile, not of
 * the guard's: an ending can commit while the reconcile waits on the bond's lock, and the
 * guard's membership would still take writes (as `GetDays.onThisDay`; C6b final review m2).
 *
 * **It remembers nothing about who asked** (H-M1): read-only, and handed the bond's access
 * port, the calendar and the streak's rule, nothing else (`ArchitectureTest` holds
 * the list). Whether a milestone is new to somebody is their device's to know, from `achievedOn`.
 */
@Service
internal class GetMilestones(
    private val access: BondAccess,
    private val calendar: StreakCalendar,
    private val streak: GetStreak,
) {
    @Transactional(readOnly = true)
    fun of(membership: BondMembership): MilestonesView {
        val now = access.membershipNow(membership)
        // The undecided days first, the timeline last: see the class KDoc. Each statement reads
        // what has committed by then, and an evaluation sets a day's evaluated_at and
        // evaluated_as in one commit, so the order is the guarantee.
        val pending = streak.pending(membership)
        val evaluated = calendar.completeDays(membership.bondId, Milestones.THRESHOLDS)
        // A pending day the job evaluated after `pending` read it is in the timeline now: counted
        // there, it is dropped here. Asked after the timeline, so a commit between the two can
        // only leave the total one short for a moment, never count a day twice.
        val evaluatedSince = streak.lastEvaluatedDate(membership)
        val counted = pending.complete.filter { evaluatedSince == null || it.isAfter(evaluatedSince) }
        val total = evaluated.total + counted.size
        val dates = evaluated.dates + counted.mapIndexed { index, date -> (evaluated.total + index + 1) to date }
        return MilestonesView(
            achieved = Milestones.achieved(total, dates),
            next = if (now.takesWrites) Milestones.next(total) else null,
        )
    }
}
