package com.moyi.notification.domain

import io.kotest.assertions.withClue
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
            val decision = PushBudget.decide(sent, reserved, sent + sentElsewhere, revealClass, leaveFreeAcrossBonds = 0)
            if (decision.sends) sent++
            if (decision == BudgetDecision.RESERVED) reserved = true
            decision
        }
    }

    @Test
    fun `FR-082's own example - four before 22-00 send three, and the reveal still goes`() {
        // A reminder at 20:00, another kind at 20:15 and 20:30, a nudge at 20:45, the reveal at 22:00 (ADR-0068 decision 4).
        day(OTHER, OTHER, OTHER, OTHER, REVEAL) shouldBe
            listOf(
                BudgetDecision.REMAINDER,
                BudgetDecision.REMAINDER,
                BudgetDecision.REMAINDER,
                BudgetDecision.DROPPED,
                BudgetDecision.RESERVED,
            )
        PushBudget.decide(3, false, 3, false, 0) shouldBe BudgetDecision.DROPPED
        PushBudget.decide(3, false, 3, true, 0) shouldBe BudgetDecision.RESERVED
    }

    @Test
    fun `a reveal-class push takes the reserved slot first, and the remainder after it`() {
        day(REVEAL, REVEAL, REVEAL, REVEAL, REVEAL) shouldBe
            listOf(
                BudgetDecision.RESERVED,
                BudgetDecision.REMAINDER,
                BudgetDecision.REMAINDER,
                BudgetDecision.REMAINDER,
                BudgetDecision.DROPPED,
            )
    }

    @Test
    fun `a reveal that came first leaves the remainder whole for the rest`() {
        day(REVEAL, OTHER, OTHER, OTHER, OTHER) shouldBe
            listOf(
                BudgetDecision.RESERVED,
                BudgetDecision.REMAINDER,
                BudgetDecision.REMAINDER,
                BudgetDecision.REMAINDER,
                BudgetDecision.DROPPED,
            )
    }

    @Test
    fun `no more than four a day in one Bond, whatever they are`() {
        PushBudget.PER_BOND_PER_DAY shouldBe 4
        PushBudget.decide(4, true, 4, true, 0) shouldBe BudgetDecision.DROPPED
        listOf(REVEAL, OTHER).forEach { first ->
            day(first, REVEAL, OTHER, REVEAL, OTHER, REVEAL).count { it.sends } shouldBe PushBudget.PER_BOND_PER_DAY
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
                    val decision = PushBudget.decide(sent, reserved, acrossBonds, revealClass, 0)
                    if (decision.sends) {
                        sent++
                        acrossBonds++
                    }
                    if (decision == BudgetDecision.RESERVED) reserved = true
                    decision
                }
            }
        outcomes.count { it.sends } shouldBe PushBudget.ACROSS_BONDS
        // The third Bond's reveal found the reserved slot free and the member's six spent (the spec's reading, ADR-0045).
        outcomes.subList(6, 9) shouldBe listOf(BudgetDecision.DROPPED, BudgetDecision.DROPPED, BudgetDecision.DROPPED)
    }

    @Test
    fun `the reserved slot is never a fifth push in one Bond`() {
        // Four sent and the reserved slot unspent: a count the rule never makes by itself, but the
        // ledger can hold one (the day is counted in the member's zone, and the zone can change).
        PushBudget.decide(
            sentInBondToday = 4,
            reservedSpentToday = false,
            sentAcrossBonds = 4,
            revealClass = true,
            leaveFreeAcrossBonds = 0,
        ) shouldBe
            BudgetDecision.DROPPED
        PushBudget.decide(
            sentInBondToday = 3,
            reservedSpentToday = false,
            sentAcrossBonds = 3,
            revealClass = true,
            leaveFreeAcrossBonds = 0,
        ) shouldBe
            BudgetDecision.RESERVED
    }

    @Test
    fun `with nothing to leave free, every decision is the rule before nudges`() {
        // The rule as ADR-0045 documented it, with ADR-0068's per-Bond four: written out here, not
        // read from PushBudget, so that a change to the rule is a change to this table.
        fun documented(
            sent: Int,
            reserved: Boolean,
            across: Int,
            revealClass: Boolean,
        ): BudgetDecision =
            when {
                across >= 6 -> BudgetDecision.DROPPED
                sent >= 4 -> BudgetDecision.DROPPED
                revealClass && !reserved -> BudgetDecision.RESERVED
                sent - (if (reserved) 1 else 0) < 3 -> BudgetDecision.REMAINDER
                else -> BudgetDecision.DROPPED
            }
        val bools = listOf(false, true)
        val counts =
            (0..7).flatMap { sent -> bools.flatMap { reserved -> (0..7).map { across -> Triple(sent, reserved, across) } } }
        counts.size * bools.size shouldBe 256
        counts.forEach { (sent, reserved, across) ->
            bools.forEach { revealClass ->
                withClue("sent=$sent reserved=$reserved across=$across revealClass=$revealClass") {
                    PushBudget.decide(sent, reserved, across, revealClass, leaveFreeAcrossBonds = 0) shouldBe
                        documented(sent, reserved, across, revealClass)
                }
            }
        }
    }

    @Test
    fun `a nudge goes if, after it, a slot is left for each reveal owed`() {
        PushBudget.decide(0, false, 3, false, 2) shouldBe BudgetDecision.REMAINDER
        PushBudget.decide(0, false, 4, false, 2) shouldBe BudgetDecision.DROPPED_LEAVING_ROOM
        PushBudget.decide(0, false, 5, false, 0) shouldBe BudgetDecision.REMAINDER
        PushBudget.decide(0, false, 6, false, 0) shouldBe BudgetDecision.DROPPED
    }

    @Test
    fun `two Bonds nudged all day still have room for both reveals (spec ruling B7)`() {
        // Both reminders at 08:00, the member writing in neither until the afternoon; both reveals owed all day.
        var acrossBonds = 0
        val inBond = mutableMapOf("A" to 0, "B" to 0)
        val reservedIn = mutableMapOf("A" to false, "B" to false)

        fun push(
            bond: String,
            revealClass: Boolean,
            leaveFree: Int,
        ): Pair<Int, BudgetDecision> {
            val before = acrossBonds
            val decision = PushBudget.decide(inBond.getValue(bond), reservedIn.getValue(bond), acrossBonds, revealClass, leaveFree)
            if (decision.sends) {
                acrossBonds++
                inBond[bond] = inBond.getValue(bond) + 1
            }
            if (decision == BudgetDecision.RESERVED) reservedIn[bond] = true
            return before to decision
        }

        val owed = 2
        listOf(
            push("A", OTHER, 0), // 08:00 A1, hard
            push("B", OTHER, 0), // 08:00 B1, hard
            push("A", OTHER, owed), // 11:00 A2
            push("B", OTHER, owed), // 11:00 B2
            push("A", OTHER, owed), // 14:00 A3
            push("B", OTHER, owed), // 14:00 B3
            push("A", REVEAL, 0), // the evening's reveals
            push("B", REVEAL, 0),
        ) shouldBe
            listOf(
                0 to BudgetDecision.REMAINDER,
                1 to BudgetDecision.REMAINDER,
                2 to BudgetDecision.REMAINDER,
                3 to BudgetDecision.REMAINDER,
                4 to BudgetDecision.DROPPED_LEAVING_ROOM,
                4 to BudgetDecision.DROPPED_LEAVING_ROOM,
                4 to BudgetDecision.RESERVED,
                5 to BudgetDecision.RESERVED,
            )
    }

    @Test
    fun `the cross-Bond cap holds back even a reveal whose own Bond has room`() {
        PushBudget.decide(
            sentInBondToday = 0,
            reservedSpentToday = false,
            sentAcrossBonds = 6,
            revealClass = true,
            leaveFreeAcrossBonds = 0,
        ) shouldBe
            BudgetDecision.DROPPED
        PushBudget.decide(
            sentInBondToday = 0,
            reservedSpentToday = false,
            sentAcrossBonds = 5,
            revealClass = true,
            leaveFreeAcrossBonds = 0,
        ) shouldBe
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

    private val BudgetDecision.sends: Boolean
        get() = this == BudgetDecision.RESERVED || this == BudgetDecision.REMAINDER

    private companion object {
        const val REVEAL = true
        const val OTHER = false
    }
}
