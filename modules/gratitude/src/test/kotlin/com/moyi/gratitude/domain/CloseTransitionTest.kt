package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Spec §6.4 step 2: what the end of a day does to it. Pure — the service
 * proves the lock, the timeline and the transaction; this proves the rule,
 * one row of the table at a time.
 */
internal class CloseTransitionTest {
    private val opened = Instant.parse("2026-09-15T10:00:00Z")
    private val end = Instant.parse("2026-09-15T23:00:00Z")
    private val day =
        BondDay.open(
            BondDayId(UUID.randomUUID()),
            UUID.randomUUID(),
            DayWindow(LocalDate.of(2026, 9, 15), Instant.parse("2026-09-14T23:00:00Z"), end),
            ZoneId.of("Africa/Lagos"),
            opened,
        )

    @Test
    fun `a day nobody wrote on closes EMPTY`() {
        val closed = day.close(end)

        closed.status shouldBe BondDayStatus.EMPTY
        closed.closedAt shouldBe end
        closed.revealedAt shouldBe null
    }

    @Test
    fun `a day one member wrote on closes SOLO, and the day itself is not marked revealed`() {
        val closed = day.withEntry().close(end)

        closed.status shouldBe BondDayStatus.SOLO
        closed.entryCount shouldBe 1
        closed.closedAt shouldBe end
        // The lone ENTRY is revealed (FR-063), by the service. `revealedAt`
        // on the day means both were read together, and that did not happen.
        closed.revealedAt shouldBe null
    }

    @Test
    fun `a day still waiting on its reveal time is revealed by its own end`() {
        val pending = day.withEntry().withEntry().revealWhenDue(LocalTime.of(23, 59, 59), opened)
        pending.status shouldBe BondDayStatus.PENDING_REVEAL

        val closed = pending.close(end)

        closed.status shouldBe BondDayStatus.REVEALED
        closed.revealedAt shouldBe end
        closed.closedAt shouldBe end
    }

    @Test
    fun `a day revealed while it was open keeps its reveal instant and gains only closedAt`() {
        val revealed = day.withEntry().withEntry().revealWhenDue(null, opened)

        val closed = revealed.close(end)

        closed shouldBe revealed.copy(closedAt = end)
    }

    @Test
    fun `a suspended day is closed without being evaluated`() {
        val suspended = day.copy(status = BondDayStatus.SUSPENDED).withEntry().withEntry()

        val closed = suspended.close(end)

        closed shouldBe suspended.copy(closedAt = end)
    }

    @Test
    fun `closing is idempotent, and a later run does not move closedAt`() {
        val closed = day.withEntry().close(end)

        closed.close(end.plusSeconds(900)) shouldBe closed
    }

    @Test
    fun `closedAt is kept to the microsecond a timestamptz holds`() {
        day.close(end.plusNanos(1_999)).closedAt shouldBe end.plusNanos(1_000)
    }

    @Test
    fun `a day cannot be closed before it has ended`() {
        shouldThrow<IllegalArgumentException> { day.close(end.minusNanos(1_000)) }
    }

    /** Two counted entries and no reveal decision only ever exists in memory, between `withEntry` and `revealWhenDue`. */
    @Test
    fun `a two-entry PARTIAL day is refused rather than closed as something it is not`() {
        shouldThrow<IllegalStateException> { day.withEntry().withEntry().close(end) }
    }

    @Test
    fun `a closed joining day is not resumed`() {
        val activation = opened
        val closedSuspended = day.copy(status = BondDayStatus.SUSPENDED).withEntry().close(end)

        closedSuspended.resumeJoiningDay(activation) shouldBe closedSuspended
    }

    @Test
    fun `the sweep reveals a PENDING_REVEAL day whose time passed while nobody was looking`() {
        val pending = day.withEntry().withEntry().revealWhenDue(LocalTime.of(20, 0), opened)
        val longAfter = Instant.parse("2026-09-15T22:30:00Z")

        val revealed = pending.revealWhenDue(LocalTime.of(20, 0), longAfter)

        revealed.status shouldBe BondDayStatus.REVEALED
        revealed.revealedAt shouldBe longAfter
        revealed.closedAt shouldBe null
    }

    // --- ADR-0035 question 9: an ended bond reveals nothing new (ruled 2026-10-21, 2026-10-22) ---

    @Test
    fun `a pending day closed withheld keeps its status, gains closedAt, and records no reveal`() {
        val pending = day.withEntry().withEntry().revealWhenDue(LocalTime.of(20, 0), opened)

        val closed = pending.close(end, withheld = true)

        closed.status shouldBe BondDayStatus.PENDING_REVEAL
        closed.closedAt shouldBe end
        closed.revealedAt shouldBe null
        closed.isSettled shouldBe true
        closed shouldBe pending.copy(closedAt = end)
    }

    @Test
    fun `withholding a resumed joining day both wrote on makes it pending, so it can close`() {
        val resumed =
            day
                .copy(status = BondDayStatus.SUSPENDED)
                .withEntry()
                .withEntry()
                .resumeJoiningDay(opened)
        resumed.status shouldBe BondDayStatus.PARTIAL

        val withheld = resumed.withholdReveal()

        withheld shouldBe resumed.copy(status = BondDayStatus.PENDING_REVEAL)
        withheld.close(end, withheld = true).status shouldBe BondDayStatus.PENDING_REVEAL
    }

    @Test
    fun `withholding leaves a revealed day, a SOLO day and an open one as they are`() {
        val revealed = day.withEntry().withEntry().revealWhenDue(null, opened)
        val solo = day.withEntry().close(end)
        val lone = day.withEntry()

        revealed.withholdReveal() shouldBe revealed
        solo.withholdReveal() shouldBe solo
        lone.withholdReveal() shouldBe lone
    }

    @Test
    fun `the reveal falls due at the reveal time on the day's calendar, capped at its end, and at its end with no reveal time`() {
        day.revealFallsDueAt(LocalTime.of(20, 0)) shouldBe Instant.parse("2026-09-15T19:00:00Z")
        day.revealFallsDueAt(LocalTime.of(23, 59)) shouldBe Instant.parse("2026-09-15T22:59:00Z")
        day.revealFallsDueAt(null) shouldBe end
        // A day an eastward zone change cut short ends at 21:00Z; a reveal time of 23:00 there would be 22:00Z.
        val cut = day.copy(endsAt = Instant.parse("2026-09-15T21:00:00Z"))
        cut.revealFallsDueAt(LocalTime.of(23, 0)) shouldBe cut.endsAt
    }

    @Test
    fun `a day both wrote on is withheld only by a separation before its reveal fell due`() {
        val pending = day.withEntry().withEntry().revealWhenDue(LocalTime.of(20, 0), opened)
        val due = Instant.parse("2026-09-15T19:00:00Z")

        pending.isWithheldBy(due.minusSeconds(1), LocalTime.of(20, 0)) shouldBe true
        pending.isWithheldBy(due, LocalTime.of(20, 0)) shouldBe false
        pending.isWithheldBy(null, LocalTime.of(20, 0)) shouldBe false
        // No reveal time left: it falls due at the day's end.
        pending.isWithheldBy(end.minusSeconds(1), null) shouldBe true
        pending.isWithheldBy(end, null) shouldBe false
        // Read together already, or only one wrote: nothing to withhold. (A lone entry is CloseDay's own rule.)
        day
            .withEntry()
            .withEntry()
            .revealWhenDue(null, opened)
            .isWithheldBy(opened, LocalTime.of(20, 0)) shouldBe false
        day.withEntry().isWithheldBy(opened, LocalTime.of(20, 0)) shouldBe false
        // A resumed joining day both wrote on, not yet asked the reveal rule.
        day
            .copy(status = BondDayStatus.SUSPENDED)
            .withEntry()
            .withEntry()
            .resumeJoiningDay(opened)
            .isWithheldBy(opened, LocalTime.of(20, 0)) shouldBe true
    }
}
