package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal class DayAssignmentTest {
    private val lagos = ZoneId.of("Africa/Lagos")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati")
    private val never: (LocalDate) -> Boolean = { false }

    // ---- BR-3/BR-3a's trust checks, against a plain fixed-zone calendar --------

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

        DayAssignment.dateFor(manchester2330, null, fixed(lagos), never) shouldBe LocalDate.of(2026, 1, 15)
    }

    @Test
    fun `intendedAt is used when it is recent, honest and lands on an open day`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val yesterdayEvening = Instant.parse("2026-09-14T20:00:00Z")

        val resolution = DayAssignment.resolve(now, yesterdayEvening, fixed(lagos), never)

        resolution.date shouldBe LocalDate.of(2026, 9, 14)
        resolution.resolvedAt shouldBe yesterdayEvening
        resolution.usedIntendedAt shouldBe true
        // Lagos is UTC+1 all year: the 14th runs 13T23:00Z to 14T23:00Z.
        resolution.bounds shouldBe
            DayWindow(LocalDate.of(2026, 9, 14), Instant.parse("2026-09-13T23:00:00Z"), Instant.parse("2026-09-14T23:00:00Z"))
    }

    @Test
    fun `no intendedAt files against the submission instant, and says it did`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        val resolution = DayAssignment.resolve(now, null, fixed(lagos), never)

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
        resolution.resolvedAt shouldBe now
        resolution.usedIntendedAt shouldBe false
    }

    @Test
    fun `intendedAt more than five minutes ahead is ignored`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        DayAssignment.resolve(now, now.plusSeconds(299), fixed(lagos), never).usedIntendedAt shouldBe true
        DayAssignment.resolve(now, now.plusSeconds(301), fixed(lagos), never).usedIntendedAt shouldBe false
        // Two days ahead fell back rather than filing a date in the future:
        val farAhead = DayAssignment.resolve(now, now.plus(Duration.ofDays(2)), fixed(lagos), never)
        farAhead.date shouldBe LocalDate.of(2026, 9, 15)
        farAhead.resolvedAt shouldBe now
        farAhead.usedIntendedAt shouldBe false
    }

    @Test
    fun `intendedAt more than thirty-six hours old is ignored`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        // 35h before 15T08:00Z is 13T21:00Z, which is 13T22:00 in Lagos.
        DayAssignment.dateFor(now, now.minus(Duration.ofHours(35)), fixed(lagos), never) shouldBe LocalDate.of(2026, 9, 13)
        val stale = DayAssignment.resolve(now, now.minus(Duration.ofHours(37)), fixed(lagos), never)
        stale.date shouldBe LocalDate.of(2026, 9, 15)
        stale.usedIntendedAt shouldBe false
    }

    @Test
    fun `intendedAt landing on a settled day falls back rather than filing into it`() {
        // BR-3a's third clause, and EMPTY is the one the corpus's first draft
        // left out: BR-10 makes a closed day's status authoritative and BR-1
        // grants read access only on REVEALED or closed SOLO, so two entries
        // back-filled onto a closed EMPTY day would satisfy neither — unreadable
        // by either member, with no transition able to release them. Silently
        // swallowing words is the harm BR-3a exists to prevent.
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val yesterday = Instant.parse("2026-09-14T20:00:00Z")
        val settled: (LocalDate) -> Boolean = { it == LocalDate.of(2026, 9, 14) }

        val resolution = DayAssignment.resolve(now, yesterday, fixed(lagos), settled)

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
        resolution.resolvedAt shouldBe now
        resolution.usedIntendedAt shouldBe false
        resolution.bounds shouldBe
            DayWindow(LocalDate.of(2026, 9, 15), Instant.parse("2026-09-14T23:00:00Z"), Instant.parse("2026-09-15T23:00:00Z"))
    }

    @Test
    fun `a forty-five minute offset and a DST boundary both land where the calendar says`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        DayAssignment.dateFor(Instant.parse("2026-09-14T18:20:00Z"), null, fixed(kathmandu), never) shouldBe LocalDate.of(2026, 9, 15)

        // Europe/London springs forward 2026-03-29 at 01:00 UTC — a 23-hour
        // day, running from 29T00:00Z (GMT midnight) to 29T23:00Z (BST
        // midnight). The window is the calendar's, carried through as given.
        val london = ZoneId.of("Europe/London")
        val springForward = DayAssignment.resolve(Instant.parse("2026-03-29T01:30:00Z"), null, fixed(london), never)
        springForward.date shouldBe LocalDate.of(2026, 3, 29)
        springForward.bounds.startsAt shouldBe Instant.parse("2026-03-29T00:00:00Z")
        springForward.bounds.endsAt shouldBe Instant.parse("2026-03-29T23:00:00Z")
    }

    @Test
    fun `intendedAt from before the bond's calendar begins falls back rather than failing`() {
        // A bond created at 15T07:00Z has no day before then; a claim of
        // 15T06:00Z is inside BR-3a's windows but names a time the bond did
        // not exist. It degrades to "filed now", like every other rejected
        // claim, rather than asking the calendar a question it cannot answer.
        val created = Instant.parse("2026-09-15T07:00:00Z")
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val sinceCreation = BondCalendar { at -> if (at.isBefore(created)) null else fixed(lagos).dayAt(at) }

        val resolution = DayAssignment.resolve(now, Instant.parse("2026-09-15T06:00:00Z"), sinceCreation, never)

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
        resolution.resolvedAt shouldBe now
        resolution.usedIntendedAt shouldBe false
    }

    // ---- the calendar's bounds are carried, never recomputed ------------------

    @Test
    fun `a day resolved mid-handoff carries the interval it was resolved against, not the bond's new zone`() {
        // Lagos (UTC+1) hands off to Kiritimati (UTC+14) at Lagos midnight, the
        // end of Lagos's 15th: 15T23:00Z. Kiritimati's own first label is the
        // 16th. A write at 15T12:00Z is Lagos's 15th — in Kiritimati it would
        // already be 16T02:00, so reading the bond's new zone files it a day
        // early.
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val calendar = handoffCalendar(from = lagos, to = kiritimati, at = handoff)

        val resolution =
            DayAssignment.resolve(
                submittedAt = Instant.parse("2026-09-15T12:00:00Z"),
                intendedAt = null,
                calendar = calendar,
                isSettled = never,
            )

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
        resolution.bounds.startsAt shouldBe Instant.parse("2026-09-14T23:00:00Z")
        resolution.bounds.endsAt shouldBe handoff
    }

    @Test
    fun `an offline draft from before a handoff resolves in the zone that was effective then`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val calendar = handoffCalendar(from = lagos, to = kiritimati, at = handoff)

        // Written on a flight at 22:00Z on the 15th — Lagos's 15th, 23:00
        // local — and sent three hours later, at 16T01:00Z, by which time
        // Kiritimati decides dates and it is already 16T15:00 there. BR-3a's
        // window (36h) accepts the claim; the calendar is what keeps it on
        // the day it was actually written.
        val resolution =
            DayAssignment.resolve(
                submittedAt = Instant.parse("2026-09-16T01:00:00Z"),
                intendedAt = Instant.parse("2026-09-15T22:00:00Z"),
                calendar = calendar,
                isSettled = never,
            )

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
        resolution.usedIntendedAt shouldBe true
        resolution.bounds shouldBe DayWindow(LocalDate.of(2026, 9, 15), Instant.parse("2026-09-14T23:00:00Z"), handoff)
    }

    @Test
    fun `a merged westward day's forty-eight hours are carried through as the calendar gives them`() {
        // R3: Kiritimati (UTC+14) to Honolulu (UTC-10). Kiritimati's 16th
        // starts at 15T10:00Z; the handoff lands where Honolulu's own 16th
        // would begin again, so the 16th is one day running until Honolulu's
        // midnight at 17T10:00Z — 48 hours. Any midnight-to-midnight
        // computation in either zone gives a 24-hour window instead, and in
        // Honolulu would also give the wrong date: 15T20:00Z is 15T10:00 there.
        val merged =
            DayWindow(LocalDate.of(2026, 9, 16), Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-17T10:00:00Z"))
        val calendar =
            BondCalendar { at ->
                require(!at.isBefore(merged.startsAt) && at.isBefore(merged.endsAt)) { "outside this test's one day: $at" }
                merged
            }
        val askedAbout = mutableListOf<LocalDate>()

        val resolution =
            DayAssignment.resolve(
                submittedAt = Instant.parse("2026-09-17T05:00:00Z"),
                // 33 hours earlier — inside BR-3a's 36-hour window.
                intendedAt = Instant.parse("2026-09-15T20:00:00Z"),
                calendar = calendar,
                isSettled = {
                    askedAbout += it
                    false
                },
            )

        resolution.date shouldBe LocalDate.of(2026, 9, 16)
        resolution.bounds shouldBe merged
        Duration.between(resolution.bounds.startsAt, resolution.bounds.endsAt) shouldBe Duration.ofHours(48)
        // The settled check is asked about the calendar's label, not a recomputed one.
        askedAbout shouldBe listOf(LocalDate.of(2026, 9, 16))
    }

    @Test
    fun `a degenerate window from the calendar is carried through, not rejected or widened`() {
        // An eastward handoff can skip a label entirely; its day is the empty
        // span startsAt == endsAt. DayAssignment has no business second-guessing
        // what the calendar says a day is. No real calendar returns an empty
        // window for an instant (a half-open empty span contains none), so this
        // only pins pass-through; the end-to-end degenerate case is Task 9's.
        val at = Instant.parse("2026-09-15T23:00:00Z")
        val skipped = DayWindow(LocalDate.of(2026, 9, 16), at, at)

        val resolution = DayAssignment.resolve(at, null, { skipped }, never)

        resolution.bounds shouldBe skipped
    }

    // ---- DayWindow's own invariant -----------------------------------------------

    @Test
    fun `a window may be empty but never inverted`() {
        val at = Instant.parse("2026-09-15T23:00:00Z")

        DayWindow(LocalDate.of(2026, 9, 16), at, at).startsAt shouldBe at
        shouldThrow<IllegalArgumentException> { DayWindow(LocalDate.of(2026, 9, 16), at, at.minusNanos(1_000)) }
    }

    // ---- test calendars ------------------------------------------------------------

    /** Midnight to midnight in one zone, forever — the shape every bond had before any zone change. */
    private fun fixed(zone: ZoneId): BondCalendar =
        BondCalendar { at ->
            val date = at.atZone(zone).toLocalDate()
            DayWindow(date, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        }

    /**
     * [from] until [at], then [to] — an eastward handoff with no label
     * collision, each side's day clipped at the handoff instant. Hand-written
     * here rather than borrowed from `bond.domain.AnchorTimeline`, which is
     * `internal` to `bond`; this only has to be right for the instants above.
     */
    private fun handoffCalendar(
        from: ZoneId,
        to: ZoneId,
        at: Instant,
    ): BondCalendar =
        BondCalendar { instant ->
            val zone = if (instant.isBefore(at)) from else to
            val date = instant.atZone(zone).toLocalDate()
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            if (zone == from) DayWindow(date, start, minOf(end, at)) else DayWindow(date, maxOf(start, at), end)
        }
}
