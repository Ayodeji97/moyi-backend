package com.moyi.bond.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** FR-073: "toggling Strict mode never alters past days" — which needs the bond to say what the setting *was*. */
internal class StrictModeHistoryTest {
    private val t = Instant.parse("2026-09-15T23:00:00Z")

    private fun on(at: Instant) = StrictModeChange(at, strictMode = true)

    private fun off(at: Instant) = StrictModeChange(at, strictMode = false)

    @Test
    fun `a bond that never changed it has had the current setting all along`() {
        StrictModeHistory(current = true, changes = emptyList()).before(t) shouldBe true
        StrictModeHistory(current = false, changes = emptyList()).before(t) shouldBe false
    }

    @Test
    fun `before its first change a bond had the opposite of what that change made it`() {
        val history = StrictModeHistory(current = true, changes = listOf(on(t.plusSeconds(60))))

        history.before(t) shouldBe false
        history.before(t.plusSeconds(61)) shouldBe true
    }

    /** The case one "last changed at" cannot answer: off and on again, both after the day ended. */
    @Test
    fun `two changes after an instant do not alter what the setting was at it`() {
        val changes = listOf(on(t.minusSeconds(3_600)), off(t.plusSeconds(1)), on(t.plusSeconds(2)))
        val history = StrictModeHistory(current = true, changes = changes)

        history.before(t) shouldBe true
        history.before(t.plusSeconds(2)) shouldBe false
        history.before(t.plusSeconds(3)) shouldBe true
    }

    @Test
    fun `with every change after the instant, the setting then is the opposite of the first of them`() {
        val history = StrictModeHistory(current = false, changes = listOf(on(t.plusSeconds(1)), off(t.plusSeconds(2))))

        history.before(t) shouldBe false
    }

    /** A day is `[startsAt, endsAt)`: a change at the very end is the next day's. */
    @Test
    fun `a change stamped exactly at the instant is not yet in force before it`() {
        StrictModeHistory(current = true, changes = listOf(on(t))).before(t) shouldBe false
    }

    @Test
    fun `the order the changes are handed over in does not matter`() {
        val history = StrictModeHistory(current = false, changes = listOf(off(t.plusSeconds(5)), on(t.minusSeconds(5))))

        history.before(t) shouldBe true
    }
}
