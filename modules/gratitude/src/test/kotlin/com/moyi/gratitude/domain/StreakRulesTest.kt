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
        BondDayStatus.entries.associateWith(DayOutcome::of) shouldBe
            mapOf(
                BondDayStatus.OPEN to null,
                BondDayStatus.PARTIAL to null,
                BondDayStatus.PENDING_REVEAL to null,
                BondDayStatus.REVEALED to DayOutcome.COMPLETE,
                BondDayStatus.SOLO to DayOutcome.MISSED,
                BondDayStatus.EMPTY to DayOutcome.MISSED,
                BondDayStatus.SUSPENDED to DayOutcome.SUSPENDED,
                BondDayStatus.FROZEN to DayOutcome.FROZEN_BY_SKIP,
            )
    }
}
