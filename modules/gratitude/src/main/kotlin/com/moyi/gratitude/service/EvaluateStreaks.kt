package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondClosingView
import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.gratitude.domain.DayOutcome
import com.moyi.gratitude.domain.Evaluation
import com.moyi.gratitude.domain.StreakChange
import com.moyi.gratitude.domain.StreakRules
import com.moyi.gratitude.domain.StreakState
import com.moyi.gratitude.infra.database.DayToEvaluate
import com.moyi.gratitude.infra.database.StreakStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The close job's third step (spec §6.4): the streak of every bond with a
 * day that is settled and not yet evaluated.
 *
 * **Why it is a step of its own, and not part of closing a day.** The first
 * two steps settle a bond's days out of order: a day nobody opened is written
 * before an older day that has a row is closed. The rules are a fold in date
 * order — a freeze banked on Tuesday is what covers a missed Wednesday — so a
 * day cannot be evaluated the moment it is settled. This step takes each
 * bond's days oldest first and **stops in front of the first it may not
 * evaluate yet**:
 *
 * - a day that is not closed (which the next rule would catch anyway, one day
 *   later: said outright so that nobody has to work that out);
 * - a day that does not directly follow the one before it, once the bond is
 *   two people. From its joining day on, every date of a bond has a row —
 *   written by an entry, or by the job — so a jump in the dates means a day
 *   is still to be written (the job's allowance for a run ran out, or it
 *   failed), and what comes after it has to wait for it.
 *
 * **One bond, one transaction, under the bond's `streak_states` row.** Two
 * runs on one bond would fold from the same state and count the same days
 * twice; the second waits, and then finds nothing left. The days themselves
 * are not locked: they are closed, and nothing else writes a closed day.
 *
 * **What is decided is written on the day** ([StreakStore.recordDay]): what
 * it was to the streak, whether Strict mode was on, whether it spent a
 * freeze. `RecalculateStreak` replays those and never asks again.
 */
@Service
@Suppress("LongParameterList") // What one bond's evaluation touches, each named.
internal class EvaluateStreaks(
    private val access: BondAccess,
    private val streaks: StreakStore,
    private val events: EventPublisher,
    private val ids: IdGenerator,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** What one call did: days evaluated, the bonds they belong to, and bonds that failed and were left. */
    data class Evaluated(
        val days: Int,
        val bonds: Set<UUID>,
        val failed: Int,
    )

    fun evaluate(): Evaluated {
        var days = 0
        var failed = 0
        val bonds = mutableSetOf<UUID>()
        var page = streaks.bondsToEvaluate(null, PAGE)
        while (page.isNotEmpty()) {
            for (bondId in page) {
                val count = evaluateOrCount(bondId)
                if (count == null) {
                    failed++
                } else if (count > 0) {
                    bonds += bondId
                }
                days += count ?: 0
            }
            page = streaks.bondsToEvaluate(page.last(), PAGE)
        }
        return Evaluated(days, bonds, failed)
    }

    /** The days evaluated for [bondId], or `null` if it failed. One bond must not hold the others. */
    @Suppress("TooGenericExceptionCaught") // As `CloseElapsedDays.settle`.
    private fun evaluateOrCount(bondId: UUID): Int? =
        try {
            // `null` from the transaction: the bond's own row is gone. There is
            // no foreign key from a day to its bond (no key crosses a module),
            // so such days can exist, and they would be picked up again on
            // every run. That is counted and said, not passed over as "none".
            transactions.execute { evaluateBond(bondId) }
                ?: null.also { log.warn("streak: bond {} has days to evaluate and no bond row; they are left as they are", bondId) }
        } catch (failure: Exception) {
            log.error("streak: bond {} could not be evaluated: {}", bondId, failure.javaClass.simpleName)
            null
        }

    private fun evaluateBond(bondId: UUID): Int? {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // The bond's lock first, as the closer takes it (ADR-0033): a change
        // of Strict mode still committing is then in, with its stamp, before
        // a day is judged by it. And before the streak's own lock, which
        // makes a row: a bond whose row is gone should not be given one.
        val view = access.lockClosingViewOf(bondId) ?: return null
        var state = streaks.lock(bondId, now)
        val joiningDate = view.activeSince?.let(view.anchorTimeline::dateAt)
        var previous = streaks.lastEvaluatedDate(bondId)
        var evaluated = 0
        for (day in streaks.unevaluatedDays(bondId)) {
            if (!day.isClosed || !follows(day.date, previous, joiningDate)) break
            val outcome = outcomeOf(day, view)
            // The setting the day ended under, not the one in force now
            // (FR-073): this runs after the day is over, and the setting is
            // one member's to change in the meantime.
            val strict = view.strictModeBefore(day.endsAt)
            val step = StreakRules.step(state, day.date, outcome, strict)
            streaks.recordDay(day.id, outcome, strict, step.freezeApplied, now)
            record(bondId, day.date, state, step.state, announce = outcome == DayOutcome.COMPLETE, step, now)
            state = step.state
            // Never backwards. Days are read in date order, so this is a
            // guard and not a path: nothing reaches here out of order today.
            previous = maxOf(previous ?: day.date, day.date)
            evaluated++
        }
        if (evaluated > 0) streaks.save(bondId, state, now)
        return evaluated
    }

    /**
     * Whether [date] may be evaluated after [previous]. Before the bond was
     * two people its days are the ones its creator happened to write on, and
     * need not be consecutive; they are all suspended and move nothing. From
     * the joining day on, each must directly follow the last.
     */
    private fun follows(
        date: LocalDate,
        previous: LocalDate?,
        joiningDate: LocalDate?,
    ): Boolean =
        joiningDate == null ||
            !date.isAfter(joiningDate) ||
            date == previous?.plusDays(1)

    /**
     * What [day] is to the streak. Its status says, with one exception no
     * status carries: a day **missed** after the bond stopped taking writes
     * does not end the run (doc 04 §8.3 — the streak "is preserved at its
     * value"). Only a missed day: a day both wrote on before one of them
     * left that afternoon is a complete day like any other, and §8.3
     * protects a streak from a break, it does not take a day away from it.
     */
    private fun outcomeOf(
        day: DayToEvaluate,
        view: BondClosingView,
    ): DayOutcome {
        val byStatus = checkNotNull(DayOutcome.of(day.status)) { "a closed bond-day has a settled status: ${day.id}" }
        val ended = view.endedAt
        return if (byStatus == DayOutcome.MISSED && ended != null && day.endsAt.isAfter(ended)) DayOutcome.AFTER_THE_END else byStatus
    }

    /**
     * What the day did to the run, written down: a line in the audit log for
     * every change, and an event for the two a consumer may act on.
     *
     * **`StreakExtended` is for a day both wrote on, and no other.** A missed
     * day covered by a freeze and a date a zone change stepped over also
     * extend the run, and are in `streak_events`; announced, they would be a
     * celebration of a day nobody wrote. **`StreakBroken` must never become a
     * message to a member** (FR-076): to the one who wrote, "your streak
     * ended" says the other did not. It is for the screen to state when
     * opened (`states.md` §7), and for analytics. Both carry the bond's id
     * and nothing else.
     */
    @Suppress("LongParameterList") // One day's before and after.
    private fun record(
        bondId: UUID,
        date: LocalDate,
        before: StreakState,
        after: StreakState,
        announce: Boolean,
        step: Evaluation,
        now: Instant,
    ) {
        val lines = listOfNotNull(StreakStore.eventOf(step.change), StreakStore.FREEZE_BANKED.takeIf { step.freezeBanked })
        lines.forEach { streaks.appendEvent(ids.timeOrdered(), bondId, date, it, before.current, after.current, now) }
        when {
            step.change == StreakChange.BROKEN -> publish("StreakBroken", bondId, now)
            step.change == StreakChange.EXTENDED && announce -> publish("StreakExtended", bondId, now)
        }
    }

    private fun publish(
        type: String,
        bondId: UUID,
        now: Instant,
    ) {
        events.publish(OutboxEvent("Bond", bondId, type, mapOf("bondId" to bondId), now))
    }

    private companion object {
        const val PAGE = 200
    }
}
