package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** BR-3a's late-entry grace as a value: when a day settles, and which days are still undecided. */
internal class LateEntryGraceTest {
    private val lagos = ZoneId.of("Africa/Lagos")
    private val grace = LateEntryGrace(Duration.ofHours(12))

    // Lagos is UTC+1 all year: the 14th runs 13T23:00Z to 14T23:00Z, and settles at 15T11:00Z.
    private val the14thEnds = Instant.parse("2026-09-14T23:00:00Z")

    @Test
    fun `a day settles its grace after it ends, and the close looks that far back`() {
        grace.settlesAt(the14thEnds) shouldBe Instant.parse("2026-09-15T11:00:00Z")
        grace.closesDaysEndedBy(Instant.parse("2026-09-15T11:00:00Z")) shouldBe the14thEnds
        grace.oldestClaimAt(Instant.parse("2026-09-15T11:00:00Z")) shouldBe the14thEnds
    }

    @Test
    fun `yesterday is undecided until its grace has run out, and then only today is`() {
        dates(Instant.parse("2026-09-15T07:00:00Z")) shouldBe listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15))
        dates(Instant.parse("2026-09-15T10:59:59.999999Z")) shouldBe listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15))
        // At the instant it settles, it is no longer undecided: the claim window and this agree.
        dates(Instant.parse("2026-09-15T11:00:00Z")) shouldBe listOf(LocalDate.of(2026, 9, 15))
    }

    /** Review M1: past its grace a day is undecided until the job has evaluated it, and decided after. */
    @Test
    fun `a stored day is undecided in its grace, and after it until the job has evaluated it`() {
        val settles = Instant.parse("2026-09-15T11:00:00Z")
        grace.undecidedUntil(inGrace = settles, endsAt = the14thEnds, evaluated = false) shouldBe settles
        grace.undecidedUntil(inGrace = null, endsAt = the14thEnds, evaluated = false) shouldBe settles
        grace.undecidedUntil(inGrace = null, endsAt = the14thEnds, evaluated = true) shouldBe null
    }

    @Test
    fun `with no grace only today is undecided, and a negative grace is refused`() {
        LateEntryGrace.NONE.undecidedDays(fixed(lagos), Instant.parse("2026-09-15T00:00:00Z")).map { it.date } shouldBe
            listOf(LocalDate.of(2026, 9, 15))
        shouldThrow<IllegalArgumentException> { LateEntryGrace(Duration.ofSeconds(-1)) }
    }

    @Test
    fun `a grace longer than a day keeps every day it covers undecided`() {
        LateEntryGrace(Duration.ofHours(36)).undecidedDays(fixed(lagos), Instant.parse("2026-09-15T07:00:00Z")).map { it.date } shouldBe
            listOf(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15))
    }

    @Test
    fun `the days are the calendar's, and stop where the bond's calendar begins`() {
        // A bond created at 14T22:00Z: its first day is the 14th, and nothing before it is asked for.
        val created = Instant.parse("2026-09-14T22:00:00Z")
        val sinceCreation = BondCalendar { at -> if (at.isBefore(created)) null else fixed(lagos).dayAt(at) }

        grace.undecidedDays(sinceCreation, Instant.parse("2026-09-15T07:00:00Z")).map { it.date } shouldBe
            listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15))
        grace.undecidedDays(sinceCreation, Instant.parse("2026-09-14T21:00:00Z")).shouldBeEmpty()
    }

    private fun dates(now: Instant): List<LocalDate> = grace.undecidedDays(fixed(lagos), now).map { it.date }

    private fun fixed(zone: ZoneId): BondCalendar =
        BondCalendar { at ->
            val date = at.atZone(zone).toLocalDate()
            DayWindow(date, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        }
}
