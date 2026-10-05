package com.moyi.gratitude.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The reveal rules are independent of persistence; the service tests prove their transaction. */
internal class RevealTransitionTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val day =
        BondDay.open(
            BondDayId(UUID.randomUUID()),
            UUID.randomUUID(),
            DayWindow(LocalDate.of(2026, 9, 15), now.minusSeconds(3600), now.plusSeconds(43200)),
            ZoneId.of("Africa/Lagos"),
            now,
        )

    @Test
    fun `both entries reveal immediately without a configured time`() {
        val revealed = day.withEntry().withEntry().revealWhenDue(null, now)
        revealed.status shouldBe BondDayStatus.REVEALED
        revealed.revealedAt shouldBe now
        revealed.revealWhenDue(null, now.plusSeconds(1)) shouldBe revealed
    }

    @Test
    fun `both entries wait in their own status until the exact reveal instant`() {
        val pending = day.withEntry().withEntry().revealWhenDue(LocalTime.NOON, now)
        pending.status shouldBe BondDayStatus.PENDING_REVEAL
        pending.revealedAt shouldBe null
        pending.revealWhenDue(LocalTime.NOON, now.plusSeconds(3600)).status shouldBe BondDayStatus.REVEALED
    }

    @Test
    fun `one entry and a suspended day cannot reveal`() {
        day.withEntry().revealWhenDue(null, now).status shouldBe BondDayStatus.PARTIAL
        day
            .copy(status = BondDayStatus.SUSPENDED)
            .withEntry()
            .withEntry()
            .revealWhenDue(null, now)
            .status shouldBe BondDayStatus.SUSPENDED
    }

    @Test
    fun `only the day containing activation resumes including after it elapsed`() {
        val suspended = day.copy(status = BondDayStatus.SUSPENDED)
        suspended.resumeJoiningDay(now).status shouldBe BondDayStatus.OPEN
        suspended.withEntry().resumeJoiningDay(now).status shouldBe BondDayStatus.PARTIAL
        suspended
            .withEntry()
            .withEntry()
            .resumeJoiningDay(now)
            .revealWhenDue(null, day.endsAt)
            .status shouldBe BondDayStatus.REVEALED
        suspended.resumeJoiningDay(day.endsAt) shouldBe suspended
        suspended.resumeJoiningDay(day.startsAt.minusSeconds(1)) shouldBe suspended
        suspended.resumeJoiningDay(null) shouldBe suspended
    }

    // --- FR-062's time, on the days where a wall clock is not a simple thing ---

    /**
     * 01:30 does not happen in London on 2026-03-29: the clocks go from 01:00
     * straight to 02:00. The reveal is not skipped — it comes at the instant
     * the missing half-hour would have been, 02:30 summer time.
     */
    @Test
    fun `a reveal time inside a spring-forward gap falls after the gap, not never`() {
        val london = londonDay("2026-03-29", startsAt = "2026-03-29T00:00:00Z", endsAt = "2026-03-29T23:00:00Z")
        val due = Instant.parse("2026-03-29T01:30:00Z")

        london.revealWhenDue(LocalTime.of(1, 30), due.minusSeconds(1)).status shouldBe BondDayStatus.PENDING_REVEAL
        london.revealWhenDue(LocalTime.of(1, 30), due).status shouldBe BondDayStatus.REVEALED
    }

    /** 01:30 happens twice in London on 2026-10-25. The reveal comes the first time. */
    @Test
    fun `a reveal time that happens twice in an autumn overlap falls on the first`() {
        val london = londonDay("2026-10-25", startsAt = "2026-10-24T23:00:00Z", endsAt = "2026-10-26T00:00:00Z")
        val first = Instant.parse("2026-10-25T00:30:00Z")

        london.revealWhenDue(LocalTime.of(1, 30), first.minusSeconds(1)).status shouldBe BondDayStatus.PENDING_REVEAL
        london.revealWhenDue(LocalTime.of(1, 30), first).status shouldBe BondDayStatus.REVEALED
    }

    /**
     * A westward zone change runs a day on past its own midnight (spec §3.1).
     * The time is still read on the date the day is labelled with, in the
     * zone it opened in — not on the second calendar day it now also covers.
     */
    @Test
    fun `a day run on by a westward change still reveals at the time on its own date`() {
        val runOn = day.copy(endsAt = day.endsAt.plusSeconds(86_400)).withEntry().withEntry()
        val due = Instant.parse("2026-09-15T19:00:00Z")

        runOn.revealWhenDue(LocalTime.of(20, 0), due.minusSeconds(1)).status shouldBe BondDayStatus.PENDING_REVEAL
        runOn.revealWhenDue(LocalTime.of(20, 0), due).status shouldBe BondDayStatus.REVEALED
    }

    // --- a deletion before the reveal steps the day back; after it, the day is a record ---

    @Test
    fun `withoutEntry steps an unsettled day back by one`() {
        val one = day.withEntry()
        one.withoutEntry().let { it.status to it.entryCount } shouldBe (BondDayStatus.OPEN to 0)

        val pending = day.withEntry().withEntry().revealWhenDue(LocalTime.of(20, 0), now)
        pending.status shouldBe BondDayStatus.PENDING_REVEAL
        pending.withoutEntry().let { it.status to it.entryCount } shouldBe (BondDayStatus.PARTIAL to 1)

        val suspended = day.copy(status = BondDayStatus.SUSPENDED).withEntry().withEntry()
        suspended.withoutEntry().let { it.status to it.entryCount } shouldBe (BondDayStatus.SUSPENDED to 1)
    }

    @Test
    fun `withoutEntry leaves a revealed day exactly as it was revealed`() {
        val revealed = day.withEntry().withEntry().revealWhenDue(null, now)

        revealed.withoutEntry() shouldBe revealed
    }

    private fun londonDay(
        date: String,
        startsAt: String,
        endsAt: String,
    ): BondDay =
        BondDay
            .open(
                BondDayId(UUID.randomUUID()),
                UUID.randomUUID(),
                DayWindow(LocalDate.parse(date), Instant.parse(startsAt), Instant.parse(endsAt)),
                ZoneId.of("Europe/London"),
                Instant.parse(startsAt),
            ).withEntry()
            .withEntry()
}
