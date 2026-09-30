package com.moyi.bond.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class AnchorTimelineTest {
    private val lagos = ZoneId.of("Africa/Lagos") // UTC+1 all year
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14
    private val honolulu = ZoneId.of("Pacific/Honolulu") // UTC-10

    private fun timelineOf(vararg parts: AnchorInterval) = AnchorTimeline(parts.toList())

    @Test
    fun `a single open interval answers the date in its own zone`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 9, 1)))

        // 23:30Z on the 14th is 00:30 on the 15th in Lagos (UTC+1).
        timeline.dateAt(Instant.parse("2026-09-14T23:30:00Z")) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `the day's bounds are the zone's own midnights, in UTC`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 9, 1)))

        val bounds = timeline.dayBoundsAt(Instant.parse("2026-09-15T12:00:00Z"))

        bounds.startsAt shouldBe Instant.parse("2026-09-14T23:00:00Z")
        bounds.endsAt shouldBe Instant.parse("2026-09-15T23:00:00Z")
    }

    @Test
    fun `an instant inside a closed interval reads that interval's zone, never the later one`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), handoff, LocalDate.of(2026, 9, 1)),
                AnchorInterval(kiritimati, handoff, null, LocalDate.of(2026, 9, 16)),
            )

        // One second before the handoff the bond is still on Lagos time.
        timeline.zoneAt(handoff.minusSeconds(1)) shouldBe lagos
        timeline.zoneAt(handoff) shouldBe kiritimati
    }

    @Test
    fun `a day is clipped to its interval, so bounds never straddle a handoff`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), handoff, LocalDate.of(2026, 9, 1)),
                AnchorInterval(kiritimati, handoff, null, LocalDate.of(2026, 9, 16)),
            )

        // Kiritimati's own 16th began at 2026-09-15T10:00Z, before the handoff.
        // The day the bond actually lives starts at the handoff.
        val bounds = timeline.dayBoundsAt(Instant.parse("2026-09-16T00:00:00Z"))

        bounds.startsAt shouldBe handoff
        bounds.endsAt shouldBe Instant.parse("2026-09-16T10:00:00Z")
    }

    @Test
    fun `a westward handoff merges the transitional day rather than repeating a label`() {
        val handoff = Instant.parse("2026-09-16T10:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(kiritimati, Instant.parse("2026-09-01T00:00:00Z"), handoff, LocalDate.of(2026, 9, 1)),
                AnchorInterval(honolulu, handoff, null, LocalDate.of(2026, 9, 16)),
            )

        // Kiritimati's own clock would open the 16th at 2026-09-15T10:00Z, a
        // full day before the handoff — but Honolulu's first day is also the
        // 16th. Reopening that label here would violate bond_days' unique
        // (bond_id, date) (review round 1, Important #2), so this stretch
        // keeps the 15th's label and runs all the way to the handoff,
        // merging two zone-days into one ~48-hour one.
        val merged = timeline.dayBoundsAt(Instant.parse("2026-09-15T20:00:00Z"))
        merged.date shouldBe LocalDate.of(2026, 9, 15)
        merged.endsAt shouldBe handoff
        Duration.between(merged.startsAt, merged.endsAt).toHours() shouldBe 48L

        // And the day immediately after the handoff opens fresh, under
        // Honolulu, at exactly the label the merge protected.
        val next = timeline.dayBoundsAt(handoff)
        next.date shouldBe LocalDate.of(2026, 9, 16)
        next.startsAt shouldBe handoff
    }

    @Test
    fun `walking a timeline day by day across a westward handoff never repeats a label`() {
        val handoff = Instant.parse("2026-09-16T10:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(kiritimati, Instant.parse("2026-09-01T00:00:00Z"), handoff, LocalDate.of(2026, 9, 1)),
                AnchorInterval(honolulu, handoff, null, LocalDate.of(2026, 9, 16)),
            )

        // Starting a few days before the handoff and walking forward using
        // each day's own `endsAt` as the next query instant is exactly how a
        // day-opener traverses this timeline. No date this walk visits
        // repeats — the merge above is what guarantees it.
        var at = Instant.parse("2026-09-13T00:00:00Z")
        val labels = mutableListOf<LocalDate>()
        repeat(6) {
            val day = timeline.dayBoundsAt(at)
            labels += day.date
            at = day.endsAt
        }

        labels shouldBe labels.distinct()
        labels.zipWithNext().forEach { (earlier, later) -> later.isAfter(earlier) shouldBe true }
    }

    @Test
    fun `an eastward-skipped label's day is degenerate`() {
        // A label an eastward handoff skips over entirely (doc 04 §8.5) has
        // no instant that maps to it — this is the shape its DayBounds take.
        val skipped = Instant.parse("2026-09-16T10:00:00Z")
        val day = DayBounds(date = LocalDate.of(2026, 9, 16), startsAt = skipped, endsAt = skipped)

        day.isDegenerate shouldBe true
    }

    @Test
    fun `moving east defers to the end of the current logical day and names the skipped labels`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 9, 1)))

        // Confirmed at midday on the 15th, Lagos time.
        val handoff = timeline.handoffFor(Instant.parse("2026-09-15T11:00:00Z"), kiritimati, usedLabels = emptySet())

        // The current day (Lagos 15th) runs to its own midnight and no further.
        handoff.at shouldBe Instant.parse("2026-09-15T23:00:00Z")
        // At that instant Kiritimati is already on the 16th at 13:00, so the
        // first day of the new interval is the 16th — no label is lost here.
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 16)
        handoff.skippedLabels shouldBe emptyList()
    }

    @Test
    fun `moving east far enough to clear a whole label marks it skipped`() {
        // Lagos day ends 23:00Z; at that instant Kiritimati is on the 16th.
        // A bond whose last used label is the 15th loses nothing. A bond that
        // confirmed just after its own midnight loses the intervening label.
        val timeline = timelineOf(AnchorInterval(honolulu, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 8, 31)))

        // Honolulu is UTC-10; its 15th ends at 2026-09-16T10:00Z. At that
        // instant Kiritimati (UTC+14) is on the 17th at 00:00 — the 16th
        // never occurs for this bond.
        val handoff = timeline.handoffFor(Instant.parse("2026-09-15T20:00:00Z"), kiritimati, usedLabels = emptySet())

        handoff.at shouldBe Instant.parse("2026-09-16T10:00:00Z")
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 17)
        handoff.skippedLabels shouldBe listOf(LocalDate.of(2026, 9, 16))
    }

    @Test
    fun `moving west into today's own label extends the day by one day`() {
        val timeline = timelineOf(AnchorInterval(kiritimati, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 9, 1)))

        // Kiritimati's 15th ends at 2026-09-15T10:00Z. At that instant
        // Honolulu is also on the 15th — and `usedLabelsUpTo(now)` (the
        // default this test exercises, by not naming `usedLabels`) reports
        // the 15th as used, because `now` falls inside it. So the handoff is
        // pushed to Honolulu's next midnight instead, extending the current
        // day by exactly one day.
        val handoff = timeline.handoffFor(now = Instant.parse("2026-09-15T00:00:00Z"), newZone = honolulu)

        handoff.at shouldBe Instant.parse("2026-09-16T10:00:00Z")
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 16)
        handoff.skippedLabels shouldBe emptyList()
    }

    @Test
    fun `moving west past several already-used labels keeps pushing until one is free`() {
        val timeline = timelineOf(AnchorInterval(kiritimati, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 9, 1)))

        // `usedLabelsUpTo(now)` can never produce a set containing 09-16 here
        // — it only ever reports labels through *today's*, and `now` is still
        // on the 15th. This is a direct exercise of `handoffFor` with a
        // caller-supplied `usedLabels`, to prove the loop keeps pushing past
        // more than one already-used label in a single call; no production
        // caller passes `usedLabels` explicitly like this.
        val handoff =
            timeline.handoffFor(
                now = Instant.parse("2026-09-15T00:00:00Z"),
                newZone = honolulu,
                usedLabels = setOf(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16)),
            )

        handoff.at shouldBe Instant.parse("2026-09-17T10:00:00Z")
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 17)
        handoff.skippedLabels shouldBe emptyList()
    }

    @Test
    fun `a DST spring-forward day is twenty-three hours and a fall-back day is twenty-five`() {
        val london = ZoneId.of("Europe/London")
        val timeline = timelineOf(AnchorInterval(london, Instant.parse("2026-01-01T00:00:00Z"), null, LocalDate.of(2026, 1, 1)))

        val spring = timeline.dayBoundsAt(Instant.parse("2026-03-29T12:00:00Z"))
        Duration.between(spring.startsAt, spring.endsAt).toHours() shouldBe 23L

        val autumn = timeline.dayBoundsAt(Instant.parse("2026-10-25T12:00:00Z"))
        Duration.between(autumn.startsAt, autumn.endsAt).toHours() shouldBe 25L
    }

    @Test
    fun `used labels are the run from the bond's first day through today's`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null, LocalDate.of(2026, 9, 1)))

        val used = timeline.usedLabelsUpTo(Instant.parse("2026-09-05T12:00:00Z"))

        used shouldBe
            setOf(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 3),
                LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 9, 5),
            )
    }
}
