package com.moyi.gratitude.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * Spec §6.5's four invariants, over random timelines: "property-based tests
 * belong here and nowhere else in the phase". The rules are a fold over a
 * list, so the generator is a seeded [Random] and a loop; a failure names its
 * seed, and the same seed is the same timeline on any machine.
 *
 * A timeline is a list of days, each an outcome and whether the bond was in
 * Strict mode when the day was evaluated. Outcomes are weighted towards
 * complete days: a uniform draw almost never reaches a fourteenth in a row,
 * and a timeline that never banks a freeze tests nothing about them.
 */
internal class StreakPropertiesTest {
    private data class Day(
        val outcome: DayOutcome,
        val strict: Boolean,
    )

    private data class Run(
        val states: List<StreakState>,
        val decisions: List<Boolean>,
    )

    private val start = LocalDate.of(2026, 1, 1)

    private fun timeline(random: Random): List<Day> {
        var strict = random.nextInt(4) == 0
        return List(random.nextInt(1, 200)) {
            if (random.nextInt(25) == 0) strict = !strict
            val outcome =
                when (random.nextInt(20)) {
                    in 0..14 -> DayOutcome.COMPLETE
                    15, 16 -> DayOutcome.MISSED
                    17 -> DayOutcome.FROZEN_BY_SKIP
                    else -> DayOutcome.SUSPENDED
                }
            Day(outcome, strict)
        }
    }

    /** Evaluates [days] in order; with [recorded], replays those freeze decisions instead of making them. */
    private fun run(
        days: List<Day>,
        recorded: List<Boolean>? = null,
    ): Run {
        val states = mutableListOf<StreakState>()
        val decisions = mutableListOf<Boolean>()
        days.foldIndexed(StreakState.NONE) { index, state, day ->
            val step = StreakRules.step(state, start.plusDays(index.toLong()), day.outcome, day.strict, recorded?.get(index))
            states += step.state
            decisions += step.freezeApplied
            step.state
        }
        return Run(states, decisions)
    }

    private fun forEachTimeline(check: (seed: Int, days: List<Day>, random: Random) -> Unit) {
        repeat(TIMELINES) { seed ->
            val random = Random(seed)
            withClue("seed $seed") { check(seed, timeline(random), random) }
        }
    }

    @Test
    fun `the longest run never decreases, and is never shorter than the current one`() {
        forEachTimeline { _, days, _ ->
            run(days).states.zipWithNext().forEach { (before, after) ->
                after.longest shouldBeGreaterThanOrEqual before.longest
                after.longest shouldBeGreaterThanOrEqual after.current
            }
        }
    }

    /** FR-074: recalculate "MUST produce identical results". Replaying what was decided is a fixed point. */
    @Test
    fun `replaying a timeline with its recorded decisions reproduces every state`() {
        forEachTimeline { _, days, _ ->
            val first = run(days)

            run(days, recorded = first.decisions) shouldBe first
        }
    }

    /** Doc 04 §8.1: suspended days "neither extend nor break it", however many and wherever they fall. */
    @Test
    fun `a run of suspended days of any length, anywhere, leaves the streak as it was`() {
        forEachTimeline { _, days, random ->
            val at = random.nextInt(days.size + 1)
            val suspended = List(random.nextInt(1, 60)) { Day(DayOutcome.SUSPENDED, random.nextBoolean()) }

            val with = run(days.take(at) + suspended + days.drop(at))

            // Dates shift by the inserted run, and nothing else may: compare everything but the date.
            fun StreakState.undated() = copy(lastCompleteDate = null)
            with.states.last().undated() shouldBe (run(days).states.lastOrNull() ?: StreakState.NONE).undated()
            with.decisions.filterIndexed { index, _ -> index !in at until at + suspended.size } shouldBe run(days).decisions
        }
    }

    /** FR-073: "toggling Strict mode never alters past days". */
    @Test
    fun `changing Strict mode for the rest of a timeline alters nothing evaluated before the change`() {
        forEachTimeline { _, days, random ->
            val from = random.nextInt(days.size)
            val toggled = days.mapIndexed { index, day -> if (index >= from) day.copy(strict = !day.strict) else day }

            run(toggled).states.take(from) shouldBe run(days).states.take(from)
            run(toggled).decisions.take(from) shouldBe run(days).decisions.take(from)
        }
    }

    /** Not one of the four, and worth the loop: the counters stay what they claim to be on every timeline. */
    @Test
    fun `freezes are never negative, never above the cap, and every one spent was first banked`() {
        forEachTimeline { _, days, _ ->
            var banked = 0
            days.foldIndexed(StreakState.NONE) { index, state, day ->
                val step = StreakRules.step(state, start.plusDays(index.toLong()), day.outcome, day.strict)
                if (step.freezeBanked) banked++
                step.state.freezesAvailable shouldBe banked - step.state.freezesConsumed
                step.state
            }
        }
    }

    private companion object {
        const val TIMELINES = 2_000
    }
}
