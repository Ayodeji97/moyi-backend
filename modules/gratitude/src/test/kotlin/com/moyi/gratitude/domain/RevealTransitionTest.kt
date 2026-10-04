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
}
