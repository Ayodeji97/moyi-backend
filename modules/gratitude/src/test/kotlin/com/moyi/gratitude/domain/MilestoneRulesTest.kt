package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * FR-075 as the owner ruled it on 2026-10-22 (ADR-0041): four thresholds of complete days,
 * cumulative, and nothing after the last. What a day crosses, what is reached, what is next.
 */
internal class MilestoneRulesTest {
    @Test
    fun `the thresholds are 7, 30, 100 and 365, and nothing after`() {
        // Q-M2. The contract's description states the same four in words (ADR-0041 decision 14).
        Milestones.THRESHOLDS shouldBe listOf(7, 30, 100, 365)
    }

    @Test
    fun `a day crosses a threshold when it takes the count from below it to it`() {
        Milestones.crossed(6, 7) shouldBe listOf(7)
        Milestones.crossed(7, 7) shouldBe emptyList()
        Milestones.crossed(7, 8) shouldBe emptyList()
        Milestones.crossed(29, 30) shouldBe listOf(30)
        Milestones.crossed(99, 100) shouldBe listOf(100)
        Milestones.crossed(364, 365) shouldBe listOf(365)
        Milestones.crossed(365, 366) shouldBe emptyList()
        // Not one step's: a step adds one complete day at most. Stated so that "one per threshold" is a property of the rule.
        Milestones.crossed(0, 30) shouldBe listOf(7, 30)
        Milestones.crossed(0, 1_000) shouldBe listOf(7, 30, 100, 365)
    }

    @Test
    fun `next is the first threshold not yet reached, and none after the last`() {
        Milestones.next(0) shouldBe 7
        Milestones.next(6) shouldBe 7
        Milestones.next(7) shouldBe 30
        Milestones.next(364) shouldBe 365
        Milestones.next(365) shouldBe null
        Milestones.next(1_000) shouldBe null
    }

    @Test
    fun `what is reached is each threshold at or below the count, dated by the day that reached it`() {
        val dates = mapOf(7 to day(7), 30 to day(31), 31 to day(32))

        Milestones.achieved(6, emptyMap()) shouldBe emptyList()
        Milestones.achieved(30, dates) shouldBe listOf(Milestone(7, day(7)), Milestone(30, day(31)))
        // A count with no date for a threshold it passes is a read that went wrong, not an answer.
        shouldThrow<IllegalStateException> { Milestones.achieved(100, dates) }
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())
}
