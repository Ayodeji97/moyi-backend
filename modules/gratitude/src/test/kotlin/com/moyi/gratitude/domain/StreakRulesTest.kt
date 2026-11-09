package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Spec §6.5, one rule at a time. `StreakPropertiesTest` holds what must be true of any timeline. */
internal class StreakRulesTest {
    private val start = LocalDate.of(2026, 9, 1)

    /** Folds [outcomes] from [from], one a day, with Strict mode as given for all of them. */
    private fun after(
        vararg outcomes: DayOutcome,
        from: StreakState = StreakState.NONE,
        strict: Boolean = false,
    ): StreakState =
        outcomes.foldIndexed(from) { day, state, outcome -> StreakRules.step(state, start.plusDays(day.toLong()), outcome, strict).state }

    private fun complete(days: Int) = Array(days) { DayOutcome.COMPLETE }

    @Test
    fun `a day both wrote on extends the run and is the last complete date`() {
        val one = StreakRules.step(StreakState.NONE, start, DayOutcome.COMPLETE, strict = false)

        one.change shouldBe StreakChange.EXTENDED
        one.state shouldBe
            StreakState.NONE.copy(current = 1, longest = 1, lastCompleteDate = start, freezeProgress = 1, totalCompleteDays = 1)
        after(*complete(5)).current shouldBe 5
    }

    @Test
    fun `a missed day with nothing banked ends the run, and the longest run stands`() {
        val broken = StreakRules.step(after(*complete(5)), start.plusDays(5), DayOutcome.MISSED, strict = false)

        broken.change shouldBe StreakChange.BROKEN
        broken.freezeApplied shouldBe false
        broken.state.current shouldBe 0
        broken.state.longest shouldBe 5
        broken.state.lastCompleteDate shouldBe start.plusDays(4)
    }

    /** Issue #141, `states.md` §7: "Your streak ended at 21 days." `longest` is the longest ever; this is the last. */
    @Test
    fun `a break records the run it ended - its length, and the day that ended it`() {
        StreakState.NONE.lastRunLength shouldBe null
        val broken = StreakRules.step(after(*complete(5)), start.plusDays(5), DayOutcome.MISSED, strict = false).state

        (broken.lastRunLength to broken.lastRunBrokenOn) shouldBe (5 to start.plusDays(5))

        // It stays the last ended run while a new one grows, and a longer run before it does not replace it.
        val longerBefore = after(*complete(9), DayOutcome.MISSED, *complete(3), DayOutcome.MISSED, *complete(2))
        (longerBefore.lastRunLength to longerBefore.lastRunBrokenOn) shouldBe (3 to start.plusDays(13))
        (longerBefore.longest to longerBefore.current) shouldBe (9 to 2)
    }

    @Test
    fun `only a break records an ended run - a covered day, a day missed on nothing and a stepped-over date do not`() {
        val covered = StreakRules.step(after(*complete(14)), start.plusDays(14), DayOutcome.MISSED, strict = false)
        covered.change shouldBe StreakChange.FREEZE_CONSUMED
        covered.state.lastRunLength shouldBe null

        StreakRules.step(StreakState.NONE, start, DayOutcome.MISSED, strict = false).state.lastRunLength shouldBe null
        after(*complete(3), DayOutcome.FROZEN_BY_SKIP, DayOutcome.SUSPENDED, DayOutcome.AFTER_THE_END).lastRunLength shouldBe null
    }

    @Test
    fun `a missed day on a run already at zero changes nothing and breaks nothing`() {
        val again = StreakRules.step(StreakState.NONE, start, DayOutcome.MISSED, strict = false)

        again.change shouldBe StreakChange.NONE
        again.state shouldBe StreakState.NONE
    }

    @Test
    fun `the fourteenth complete day banks a freeze and progress starts again`() {
        after(*complete(13)).let { (it.freezesAvailable to it.freezeProgress) shouldBe (0 to 13) }

        val fourteenth = StreakRules.step(after(*complete(13)), start.plusDays(13), DayOutcome.COMPLETE, strict = false)

        fourteenth.freezeBanked shouldBe true
        (fourteenth.state.freezesAvailable to fourteenth.state.freezeProgress) shouldBe (1 to 0)
        fourteenth.state.totalCompleteDays shouldBe 14
    }

    @Test
    fun `no more than two freezes are banked, and the threshold still resets progress`() {
        val six = after(*complete(42))

        (six.freezesAvailable to six.freezeProgress) shouldBe (2 to 0)
        six.totalCompleteDays shouldBe 42
    }

    @Test
    fun `a banked freeze covers a missed day - the run goes on and the freeze is spent`() {
        val banked = after(*complete(14))

        val covered = StreakRules.step(banked, start.plusDays(14), DayOutcome.MISSED, strict = false)

        covered.change shouldBe StreakChange.FREEZE_CONSUMED
        covered.freezeApplied shouldBe true
        covered.state.current shouldBe 15
        covered.state.longest shouldBe 15
        (covered.state.freezesAvailable to covered.state.freezesConsumed) shouldBe (0 to 1)
        // Nobody completed that day: it earns nothing toward the next freeze.
        covered.state.totalCompleteDays shouldBe 14
        covered.state.freezeProgress shouldBe 0
        covered.state.lastCompleteDate shouldBe start.plusDays(13)
    }

    @Test
    fun `in Strict mode the threshold banks nothing, and resets progress all the same`() {
        val strict = after(*complete(14), strict = true)

        (strict.freezesAvailable to strict.freezeProgress) shouldBe (0 to 0)
    }

    /** BR-5, the reason it is not a formula: a fortnight spent in Strict mode is not paid out later. */
    @Test
    fun `switching Strict mode off does not hand back the freezes of the days spent in it`() {
        val inStrict = after(*complete(28), strict = true)

        val afterSwitchingOff = after(*complete(13), from = inStrict, strict = false)

        afterSwitchingOff.freezesAvailable shouldBe 0
        afterSwitchingOff.totalCompleteDays shouldBe 41
    }

    /**
     * A freeze saves a run; with none to save it is kept. Spent on a run of
     * zero it made a streak of one out of a day nobody wrote on.
     */
    @Test
    fun `a missed day on a run of zero spends no freeze, and the freeze is still there for a run`() {
        val banked = after(*complete(14))
        val broken = StreakRules.step(banked, start.plusDays(14), DayOutcome.MISSED, strict = true).state
        (broken.current to broken.freezesAvailable) shouldBe (0 to 1)

        val missedAgain = StreakRules.step(broken, start.plusDays(15), DayOutcome.MISSED, strict = false)

        missedAgain.freezeApplied shouldBe false
        missedAgain.change shouldBe StreakChange.NONE
        (missedAgain.state.current to missedAgain.state.freezesAvailable) shouldBe (0 to 1)
    }

    @Test
    fun `a calendar square says what the day was to the streak`() {
        fun cell(
            status: BondDayStatus,
            outcome: DayOutcome?,
            today: Boolean = false,
        ) = StreakCell.of(status, outcome, today)

        cell(BondDayStatus.REVEALED, DayOutcome.COMPLETE) shouldBe StreakCell.COMPLETE
        // A day one of the two wrote on is drawn as that (the owner's ruling, ADR-0034).
        cell(BondDayStatus.SOLO, DayOutcome.MISSED) shouldBe StreakCell.SOLO
        cell(BondDayStatus.EMPTY, DayOutcome.MISSED) shouldBe StreakCell.MISSED
        // A freeze covered it, or a zone change stepped over it.
        cell(BondDayStatus.FROZEN, DayOutcome.MISSED) shouldBe StreakCell.FROZEN
        cell(BondDayStatus.FROZEN, DayOutcome.FROZEN_BY_SKIP) shouldBe StreakCell.FROZEN
        // Days that moved nothing are not drawn.
        cell(BondDayStatus.SUSPENDED, DayOutcome.SUSPENDED) shouldBe null
        cell(BondDayStatus.SOLO, DayOutcome.AFTER_THE_END) shouldBe null
        // A day the bond was refusing writes for, whoever wrote on what was left of it.
        cell(BondDayStatus.SOLO, DayOutcome.SUSPENDED) shouldBe null
        // Nor is a day still to be evaluated, unless it is today.
        cell(BondDayStatus.SOLO, null) shouldBe null
        cell(BondDayStatus.REVEALED, null) shouldBe null
        // Today: open until both have written, whoever has. That much is still `GET /today`'s to say.
        for (status in listOf(BondDayStatus.OPEN, BondDayStatus.PARTIAL, BondDayStatus.PENDING_REVEAL, BondDayStatus.SUSPENDED)) {
            cell(status, null, today = true) shouldBe StreakCell.OPEN
        }
        cell(BondDayStatus.REVEALED, null, today = true) shouldBe StreakCell.COMPLETE
    }

    /** Issue #141, `states.md` §7a: the "we used a rest day" copy is wrong for a date a zone change stepped over. */
    @Test
    fun `a FROZEN square says why - a rest day spent, or a date a zone change stepped over - and no other square does`() {
        fun square(
            status: BondDayStatus,
            outcome: DayOutcome?,
            today: Boolean = false,
        ) = StreakDay.of(start, status, outcome, today)

        square(BondDayStatus.FROZEN, DayOutcome.MISSED) shouldBe StreakDay(start, StreakCell.FROZEN, FrozenReason.REST_DAY)
        square(BondDayStatus.FROZEN, DayOutcome.FROZEN_BY_SKIP) shouldBe StreakDay(start, StreakCell.FROZEN, FrozenReason.TIMEZONE_SHIFT)
        square(BondDayStatus.REVEALED, DayOutcome.COMPLETE) shouldBe StreakDay(start, StreakCell.COMPLETE, null)
        square(BondDayStatus.SOLO, DayOutcome.MISSED) shouldBe StreakDay(start, StreakCell.SOLO, null)
        square(BondDayStatus.EMPTY, DayOutcome.MISSED) shouldBe StreakDay(start, StreakCell.MISSED, null)
        square(BondDayStatus.PARTIAL, null, today = true) shouldBe StreakDay(start, StreakCell.OPEN, null)
        square(BondDayStatus.SUSPENDED, DayOutcome.SUSPENDED) shouldBe null
    }

    @Test
    fun `a stepped-over date does not start a run from nothing`() {
        val skipped = StreakRules.step(StreakState.NONE, start, DayOutcome.FROZEN_BY_SKIP, strict = false)

        skipped.change shouldBe StreakChange.NONE
        skipped.state shouldBe StreakState.NONE
    }

    @Test
    fun `Strict mode never spends a freeze that is banked`() {
        val banked = after(*complete(14))

        val missedInStrict = StreakRules.step(banked, start.plusDays(14), DayOutcome.MISSED, strict = true)

        missedInStrict.change shouldBe StreakChange.BROKEN
        missedInStrict.freezeApplied shouldBe false
        missedInStrict.state.current shouldBe 0
        missedInStrict.state.freezesAvailable shouldBe 1
    }

    @Test
    fun `a suspended day neither extends the run nor ends it`() {
        val five = after(*complete(5))

        val suspended = StreakRules.step(five, start.plusDays(5), DayOutcome.SUSPENDED, strict = false)

        suspended.state shouldBe five
        suspended.change shouldBe StreakChange.NONE
    }

    /** Doc 04 §8.3: ending a relationship "should not additionally delete the record that it was good". */
    @Test
    fun `a day after the bond ended moves nothing, whatever happened on it`() {
        val five = after(*complete(5))

        val afterTheEnd = StreakRules.step(five, start.plusDays(5), DayOutcome.AFTER_THE_END, strict = false)

        afterTheEnd.state shouldBe five
        afterTheEnd.change shouldBe StreakChange.NONE
    }

    @Test
    fun `a date a zone change stepped over extends the run and earns nothing toward a freeze`() {
        val five = after(*complete(5))

        val skipped = StreakRules.step(five, start.plusDays(5), DayOutcome.FROZEN_BY_SKIP, strict = false)

        skipped.change shouldBe StreakChange.EXTENDED
        skipped.state shouldBe five.copy(current = 6, longest = 6)
    }

    @Test
    fun `a replay spends the freeze it is told was spent, whatever Strict mode says now`() {
        val banked = after(*complete(14))

        val replayed = StreakRules.step(banked, start.plusDays(14), DayOutcome.MISSED, strict = true, recordedFreeze = true)

        replayed.freezeApplied shouldBe true
        replayed.state.current shouldBe 15
    }

    @Test
    fun `a replay told no freeze was spent spends none, though one is banked and Strict mode is off`() {
        val banked = after(*complete(14))

        val replayed = StreakRules.step(banked, start.plusDays(14), DayOutcome.MISSED, strict = false, recordedFreeze = false)

        replayed.change shouldBe StreakChange.BROKEN
        replayed.state.freezesAvailable shouldBe 1
    }

    @Test
    fun `a record of a freeze the bond never had is refused rather than replayed into a negative`() {
        shouldThrow<IllegalStateException> {
            StreakRules.step(StreakState.NONE, start, DayOutcome.MISSED, strict = false, recordedFreeze = true)
        }
    }

    @Test
    fun `each settled status is one outcome, and an unsettled one is none`() {
        BondDayStatus.entries.associateWith { DayOutcome.of(it, settled = true) } shouldBe
            mapOf(
                BondDayStatus.OPEN to null,
                BondDayStatus.PARTIAL to null,
                // Only a closed one: a day both wrote on that an ended bond withheld (owner, 2026-10-22).
                BondDayStatus.PENDING_REVEAL to DayOutcome.COMPLETE,
                BondDayStatus.REVEALED to DayOutcome.COMPLETE,
                BondDayStatus.SOLO to DayOutcome.MISSED,
                BondDayStatus.EMPTY to DayOutcome.MISSED,
                BondDayStatus.SUSPENDED to DayOutcome.SUSPENDED,
                BondDayStatus.FROZEN to DayOutcome.FROZEN_BY_SKIP,
            )
    }

    @Test
    fun `a pending-reveal day that is not settled has no outcome yet`() {
        DayOutcome.of(BondDayStatus.PENDING_REVEAL, settled = false) shouldBe null
        DayOutcome.of(BondDayStatus.REVEALED, settled = false) shouldBe DayOutcome.COMPLETE
    }
}
