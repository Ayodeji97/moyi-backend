package com.moyi.gratitude.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal class DayAssignmentTest {
    private val lagos = ZoneId.of("Africa/Lagos")
    private val never: (LocalDate) -> Boolean = { false }

    @Test
    fun `the bond's zone decides the day, not the writer's`() {
        // Doc 04 §6's worked example, as a test rather than a comment. Tunde is
        // in Manchester and writes at 23:30 his time on the 14th; in Lagos it is
        // already 00:30 on the 15th, so the entry is the 15th's.
        //
        // 14 January rather than the doc's 14 September: Europe/London is on
        // BST (UTC+1) in September, the same offset as Africa/Lagos's fixed
        // UTC+1, so that date never actually crosses a Bond-day boundary — a
        // 23:30 write stays 23:30 in Lagos too. In January, Manchester is on
        // GMT (UTC+0) and Lagos is still UTC+1, which is the hour of drift the
        // worked example is illustrating.
        val manchester2330 = ZonedDateTime.of(2026, 1, 14, 23, 30, 0, 0, ZoneId.of("Europe/London")).toInstant()

        DayAssignment.dateFor(manchester2330, null, lagos, never) shouldBe LocalDate.of(2026, 1, 15)
    }

    @Test
    fun `intendedAt is used when it is recent, honest and lands on an open day`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val yesterdayEvening = Instant.parse("2026-09-14T20:00:00Z")

        DayAssignment.dateFor(now, yesterdayEvening, lagos, never) shouldBe LocalDate.of(2026, 9, 14)
    }

    @Test
    fun `intendedAt more than five minutes ahead is ignored`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        DayAssignment.dateFor(now, now.plusSeconds(299), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
        DayAssignment.dateFor(now, now.plusSeconds(301), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
        // The second one fell back rather than filing tomorrow's date:
        DayAssignment.dateFor(now, now.plus(Duration.ofDays(2)), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `intendedAt more than thirty-six hours old is ignored`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        DayAssignment.dateFor(now, now.minus(Duration.ofHours(35)), lagos, never) shouldBe LocalDate.of(2026, 9, 13)
        DayAssignment.dateFor(now, now.minus(Duration.ofHours(37)), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `intendedAt landing on a closed day falls back rather than filing into it`() {
        // BR-3a's third clause, and EMPTY is the one the corpus's first draft
        // left out: BR-10 makes a closed day's status authoritative and BR-1
        // grants read access only on REVEALED or closed SOLO, so two entries
        // back-filled onto a closed EMPTY day would satisfy neither — unreadable
        // by either member, with no transition able to release them. Silently
        // swallowing words is the harm BR-3a exists to prevent.
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val yesterday = Instant.parse("2026-09-14T20:00:00Z")
        val closed: (LocalDate) -> Boolean = { it == LocalDate.of(2026, 9, 14) }

        DayAssignment.dateFor(now, yesterday, lagos, closed) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `a forty-five minute offset and a DST boundary both land where the calendar says`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        DayAssignment.dateFor(Instant.parse("2026-09-14T18:20:00Z"), null, kathmandu, never) shouldBe LocalDate.of(2026, 9, 15)

        // Europe/London springs forward 2026-03-29 at 01:00 — a 23-hour day.
        val london = ZoneId.of("Europe/London")
        DayAssignment.dateFor(Instant.parse("2026-03-29T01:30:00Z"), null, london, never) shouldBe LocalDate.of(2026, 3, 29)
    }
}
