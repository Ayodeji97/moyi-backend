package com.moyi.notification.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * FR-082 as a rule over counts (spec §4 N2, ADR-0045). The ledger supplies
 * the counts and the lock; what they mean is decided here. The same rules
 * are run against the real ledger in `PushPreferencesBudgetTest`.
 */
internal class PushBudgetTest {
    /**
     * Walks one member's day in one Bond through the rule, counting what it
     * sends the way the ledger would, and returns what each push came to.
     */
    private fun day(
        vararg pushes: Boolean,
        sentElsewhere: Int = 0,
    ): List<BudgetDecision> {
        var sent = 0
        var reserved = false
        return pushes.map { revealClass ->
            val decision = PushBudget.decide(sent, reserved, sent + sentElsewhere, revealClass)
            if (decision != BudgetDecision.DROPPED) sent++
            if (decision == BudgetDecision.RESERVED) reserved = true
            decision
        }
    }

    @Test
    fun `FR-082's own example - the day's reminders cannot consume the 22-00 reveal's slot`() {
        // A reminder at 20:00, another kind at 20:15, a third at 20:30, the reveal at 22:00.
        day(OTHER, OTHER, OTHER, REVEAL) shouldBe
            listOf(BudgetDecision.REMAINDER, BudgetDecision.REMAINDER, BudgetDecision.DROPPED, BudgetDecision.RESERVED)
    }

    @Test
    fun `a reveal-class push takes the reserved slot first, and the remainder after it`() {
        day(REVEAL, REVEAL, REVEAL, REVEAL) shouldBe
            listOf(BudgetDecision.RESERVED, BudgetDecision.REMAINDER, BudgetDecision.REMAINDER, BudgetDecision.DROPPED)
    }

    @Test
    fun `a reveal that came first leaves the remainder whole for the rest`() {
        day(REVEAL, OTHER, OTHER, OTHER) shouldBe
            listOf(BudgetDecision.RESERVED, BudgetDecision.REMAINDER, BudgetDecision.REMAINDER, BudgetDecision.DROPPED)
    }

    @Test
    fun `no more than three a day in one Bond, whatever they are`() {
        listOf(REVEAL, OTHER).forEach { first ->
            day(first, REVEAL, OTHER, REVEAL, OTHER).count { it != BudgetDecision.DROPPED } shouldBe PushBudget.PER_BOND_PER_DAY
        }
    }

    @Test
    fun `three Bonds of three pushes each stop at six across them, reveal-class included`() {
        var acrossBonds = 0
        val outcomes =
            (1..3).flatMap {
                var sent = 0
                var reserved = false
                listOf(REVEAL, OTHER, OTHER).map { revealClass ->
                    val decision = PushBudget.decide(sent, reserved, acrossBonds, revealClass)
                    if (decision != BudgetDecision.DROPPED) {
                        sent++
                        acrossBonds++
                    }
                    if (decision == BudgetDecision.RESERVED) reserved = true
                    decision
                }
            }
        outcomes.count { it != BudgetDecision.DROPPED } shouldBe PushBudget.ACROSS_BONDS
        // The third Bond's reveal found the reserved slot free and the member's six spent (the spec's reading, ADR-0045).
        outcomes.subList(6, 9) shouldBe listOf(BudgetDecision.DROPPED, BudgetDecision.DROPPED, BudgetDecision.DROPPED)
    }

    @Test
    fun `the cross-Bond cap holds back even a reveal whose own Bond has room`() {
        PushBudget.decide(sentInBondToday = 0, reservedSpentToday = false, sentAcrossBonds = 6, revealClass = true) shouldBe
            BudgetDecision.DROPPED
        PushBudget.decide(sentInBondToday = 0, reservedSpentToday = false, sentAcrossBonds = 5, revealClass = true) shouldBe
            BudgetDecision.RESERVED
    }

    @Test
    fun `the budget's day is the member's day in their reminder zone, 23 or 25 hours across a DST change`() {
        val london = ZoneId.of("Europe/London")
        // 2026-03-29: the clocks go forward at 01:00 GMT. The day is 23 hours.
        val spring = PushBudget.dayOf(Instant.parse("2026-03-29T12:00:00Z"), london)
        spring shouldBe BudgetDay(Instant.parse("2026-03-29T00:00:00Z"), Instant.parse("2026-03-29T23:00:00Z"))
        Duration.between(spring.start, spring.end) shouldBe Duration.ofHours(23)
        // 2026-10-25: back at 01:00 GMT. 25 hours.
        val autumn = PushBudget.dayOf(Instant.parse("2026-10-25T12:00:00Z"), london)
        Duration.between(autumn.start, autumn.end) shouldBe Duration.ofHours(25)
        // Kathmandu, UTC+5:45: the day begins at 18:15 UTC the evening before.
        PushBudget.dayOf(Instant.parse("2026-10-28T20:00:00Z"), ZoneId.of("Asia/Kathmandu")).start shouldBe
            Instant.parse("2026-10-28T18:15:00Z")
    }

    @Test
    fun `a day whose midnight the clock skips begins when the clock reaches it`() {
        // Havana, 2026-03-08: midnight CST becomes 01:00 CDT; the day begins at 05:00Z.
        PushBudget.dayOf(Instant.parse("2026-03-08T12:00:00Z"), ZoneId.of("America/Havana")).start shouldBe
            Instant.parse("2026-03-08T05:00:00Z")
    }

    private companion object {
        const val REVEAL = true
        const val OTHER = false
    }
}
