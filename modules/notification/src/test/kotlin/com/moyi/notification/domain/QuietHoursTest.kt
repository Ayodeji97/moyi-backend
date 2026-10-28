package com.moyi.notification.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Quiet hours as wall-clock arithmetic (spec §4 N2, ADR-0045): the cases by
 * name first, then the properties over random instants, windows and zones,
 * the zones chosen for what makes them awkward. Seeded, so a failure names a
 * seed that fails the same way on any machine (the `StreakPropertiesTest`
 * pattern).
 */
internal class QuietHoursTest {
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val kathmandu = ZoneId.of("Asia/Kathmandu")

    @Test
    fun `a window across midnight holds the late evening and the small hours, and nothing else`() {
        val night = LocalTime.of(22, 0) to LocalTime.of(7, 0)
        inside("2026-07-01T21:59:59Z", london, night) shouldBe true // 22:59 BST
        inside("2026-07-01T20:59:59Z", london, night) shouldBe false // 21:59 BST
        inside("2026-07-01T21:00:00Z", london, night) shouldBe true // 22:00 BST: the start is inside
        inside("2026-07-02T05:59:59Z", london, night) shouldBe true // 06:59 BST
        inside("2026-07-02T06:00:00Z", london, night) shouldBe false // 07:00 BST: the end is not
        inside("2026-07-02T12:00:00Z", london, night) shouldBe false
    }

    @Test
    fun `a window inside one day holds that stretch`() {
        val afternoon = LocalTime.of(13, 0) to LocalTime.of(15, 30)
        inside("2026-01-10T13:00:00Z", london, afternoon) shouldBe true
        inside("2026-01-10T15:29:59Z", london, afternoon) shouldBe true
        inside("2026-01-10T15:30:00Z", london, afternoon) shouldBe false
        inside("2026-01-10T12:59:59Z", london, afternoon) shouldBe false
    }

    @Test
    fun `a window whose start is its end holds nothing`() {
        val same = LocalTime.of(22, 0) to LocalTime.of(22, 0)
        inside("2026-01-10T22:00:00Z", london, same) shouldBe false
        inside("2026-01-10T03:00:00Z", london, same) shouldBe false
    }

    @Test
    fun `a push held at night goes when the member's morning comes, in their zone, not UTC's`() {
        val night = LocalTime.of(22, 0) to LocalTime.of(7, 0)
        // 23:30 in Kathmandu (UTC+5:45) is 17:45 UTC; 07:00 there is 01:15 UTC.
        hold("2026-03-01T17:45:00Z", kathmandu, night) shouldBe Instant.parse("2026-03-02T01:15:00Z")
        // 03:00 there, already past midnight: the same morning.
        hold("2026-03-01T21:15:00Z", kathmandu, night) shouldBe Instant.parse("2026-03-02T01:15:00Z")
        // Outside: not held at all.
        hold("2026-03-01T06:00:00Z", kathmandu, night) shouldBe Instant.parse("2026-03-01T06:00:00Z")
    }

    @Test
    fun `an end the clock skips is reached when the clock jumps past it, not an hour later`() {
        // New York, 2026-03-08: 02:00 EST becomes 03:00 EDT. 02:30 is never on the wall.
        val window = LocalTime.of(22, 0) to LocalTime.of(2, 30)
        // 01:59 EST is 06:59Z, inside. The wall next reads 03:00 EDT, 07:00Z, which is past 02:30.
        hold("2026-03-08T06:59:00Z", newYork, window) shouldBe Instant.parse("2026-03-08T07:00:00Z")
        inside("2026-03-08T07:00:00Z", newYork, window) shouldBe false
    }

    @Test
    fun `a window ending in the hour the clock repeats ends at its first reading, and the repeat is inside again`() {
        // New York, 2026-11-01: 02:00 EDT becomes 01:00 EST, so 01:00 to 02:00 is on the wall twice.
        val window = LocalTime.of(22, 0) to LocalTime.of(1, 30)
        // 01:10 EDT (05:10Z) ends at 01:30 EDT (05:30Z).
        hold("2026-11-01T05:10:00Z", newYork, window) shouldBe Instant.parse("2026-11-01T05:30:00Z")
        // 01:10 EST (06:10Z), the second reading, is inside again and ends at the second 01:30, 06:30Z.
        inside("2026-11-01T06:10:00Z", newYork, window) shouldBe true
        hold("2026-11-01T06:10:00Z", newYork, window) shouldBe Instant.parse("2026-11-01T06:30:00Z")
    }

    @Test
    fun `over random instants, windows and awkward zones, a hold ends at the first instant outside the window`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val zone = ZONES[random.nextInt(ZONES.size)]
            val start = randomTime(random)
            val end = randomTime(random)
            val at = Instant.ofEpochSecond(random.nextLong(FROM.epochSecond, TO.epochSecond))
            withClue("seed $seed: $zone, $start to $end, at $at") {
                val until = QuietHours.holdUntil(at, zone, start, end)
                if (!QuietHours.contains(at, zone, start, end)) {
                    until shouldBe at
                } else {
                    // After it, and outside the window.
                    until.isAfter(at) shouldBe true
                    QuietHours.contains(until, zone, start, end) shouldBe false
                    // And the first such instant: everything before it, from the push on, is inside.
                    QuietHours.contains(until.minusSeconds(1), zone, start, end) shouldBe true
                    var probe = at
                    while (probe.isBefore(until)) {
                        QuietHours.contains(probe, zone, start, end) shouldBe true
                        probe = probe.plus(PROBE_STEP)
                    }
                }
            }
        }
    }

    @Test
    fun `over random instants in awkward zones, inside means the member's wall clock reads a time in the window`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val zone = ZONES[random.nextInt(ZONES.size)]
            val start = randomTime(random)
            val end = randomTime(random)
            val at = Instant.ofEpochSecond(random.nextLong(FROM.epochSecond, TO.epochSecond))
            withClue("seed $seed: $zone, $start to $end, at $at") {
                // Counted in minutes of the day, a different arithmetic from the rule's comparisons.
                val wall = at.atZone(zone).toLocalTime().toSecondOfDay()
                val from = start.toSecondOfDay()
                val length = Math.floorMod(end.toSecondOfDay() - from, SECONDS_PER_DAY)
                QuietHours.contains(at, zone, start, end) shouldBe (Math.floorMod(wall - from, SECONDS_PER_DAY) < length)
            }
        }
    }

    private fun inside(
        at: String,
        zone: ZoneId,
        window: Pair<LocalTime, LocalTime>,
    ) = QuietHours.contains(Instant.parse(at), zone, window.first, window.second)

    private fun hold(
        at: String,
        zone: ZoneId,
        window: Pair<LocalTime, LocalTime>,
    ) = QuietHours.holdUntil(Instant.parse(at), zone, window.first, window.second)

    /** Minute-grained, as the settings are, with some windows ending on the hour a DST change takes or repeats. */
    private fun randomTime(random: Random): LocalTime =
        if (random.nextInt(4) == 0) {
            LocalTime.of(random.nextInt(1, 4), listOf(0, 15, 30, 45)[random.nextInt(4)])
        } else {
            LocalTime.of(random.nextInt(24), random.nextInt(60))
        }

    private companion object {
        const val CASES = 3_000
        const val SECONDS_PER_DAY = 86_400
        val PROBE_STEP: Duration = Duration.ofMinutes(7)

        /** Two years, so every zone's DST changes are crossed more than once. */
        val FROM: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val TO: Instant = Instant.parse("2028-01-01T00:00:00Z")

        /**
         * 45-minute offsets (Kathmandu, Chatham, with DST), a 30-minute DST
         * shift (Lord Howe), DST gaps and overlaps both sides of the
         * equator (New York, London, Santiago, Sydney), a zone whose DST
         * change is at midnight (Havana), and one with none at all.
         */
        val ZONES =
            listOf(
                "Asia/Kathmandu",
                "Pacific/Chatham",
                "Australia/Lord_Howe",
                "America/New_York",
                "Europe/London",
                "America/Santiago",
                "Australia/Sydney",
                "America/Havana",
                "Africa/Lagos",
            ).map(ZoneId::of)
    }
}
