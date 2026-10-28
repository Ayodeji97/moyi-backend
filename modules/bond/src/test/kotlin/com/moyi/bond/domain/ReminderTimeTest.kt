package com.moyi.bond.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * The daily reminder's instant (spec §4 N2, ADR-0045): the named cases, then
 * the property the five-minute job rests on, over awkward zones and DST
 * changes. Seeded, as `StreakPropertiesTest` is.
 */
internal class ReminderTimeTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `a reminder falls in the window that holds its wall time in the member's zone`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        // 20:00 in Kathmandu (UTC+5:45) is 14:15 UTC.
        ReminderTime.occurrenceIn(LocalTime.of(20, 0), kathmandu, at("14:10"), at("14:15")) shouldBe at("14:15")
        ReminderTime.occurrenceIn(LocalTime.of(20, 0), kathmandu, at("14:15"), at("14:20")) shouldBe null
        ReminderTime.occurrenceIn(LocalTime.of(20, 0), kathmandu, at("14:05"), at("14:10")) shouldBe null
    }

    @Test
    fun `a reminder time the clock skips falls when the clock jumps past it`() {
        // 2026-03-08 in New York: 02:00 EST becomes 03:00 EDT (07:00Z). 02:30 is never on the wall.
        ReminderTime.occurrenceIn(
            LocalTime.of(2, 30),
            newYork,
            Instant.parse("2026-03-08T06:55:00Z"),
            Instant.parse("2026-03-08T07:00:00Z"),
        ) shouldBe Instant.parse("2026-03-08T07:00:00Z")
    }

    @Test
    fun `a reminder time the clock reads twice falls at its first reading only`() {
        // 2026-11-01 in New York: 01:30 EDT is 05:30Z, and 01:30 EST is 06:30Z.
        ReminderTime.occurrenceIn(
            LocalTime.of(1, 30),
            newYork,
            Instant.parse("2026-11-01T05:25:00Z"),
            Instant.parse("2026-11-01T05:30:00Z"),
        ) shouldBe Instant.parse("2026-11-01T05:30:00Z")
        ReminderTime.occurrenceIn(
            LocalTime.of(1, 30),
            newYork,
            Instant.parse("2026-11-01T06:25:00Z"),
            Instant.parse("2026-11-01T06:30:00Z"),
        ) shouldBe null
    }

    @Test
    fun `walked in five-minute ticks across DST changes, every member is reminded once on every day of theirs`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val zone = ZONES[random.nextInt(ZONES.size)]
            // Minute-grained, as the setting is; one in four in the small hours a DST change takes or repeats.
            val local =
                if (random.nextInt(4) == 0) {
                    LocalTime.of(random.nextInt(0, 4), listOf(0, 15, 30, 45, 59)[random.nextInt(5)])
                } else {
                    LocalTime.of(random.nextInt(24), random.nextInt(60))
                }
            // Six weeks starting anywhere in two years, on the job's five-minute boundaries.
            val start = Instant.ofEpochSecond(FROM.epochSecond + TICK.seconds * random.nextLong(TICKS_IN_TWO_YEARS))
            withClue("seed $seed: $zone at $local from $start") {
                val found =
                    (0 until TICKS_IN_SIX_WEEKS).mapNotNull { tick ->
                        val after = start.plus(TICK.multipliedBy(tick))
                        ReminderTime.occurrenceIn(local, zone, after, after.plus(TICK))
                    }
                val days = found.map { it.atZone(zone).toLocalDate() }
                // Never twice on one day of the member's ...
                days.toSet().size shouldBe days.size
                // ... and on every day, with none missed between the first and the last.
                days.zipWithNext().forEach { (a, b) -> b shouldBe a.plusDays(1) }
                // Forty-two days of ticks, begun at any time of day: 41 to 43 of the member's days touched.
                found.size shouldBeInRange (DAYS - 1)..(DAYS + 1)
                // At the member's wall time, or, on a day the clock skips it, the instant it jumps past.
                found.forEach { instant ->
                    val wall = instant.atZone(zone)
                    val skipped = zone.rules.getValidOffsets(wall.toLocalDate().atTime(local)).isEmpty()
                    if (skipped) {
                        instant shouldBe zone.rules.getTransition(wall.toLocalDate().atTime(local)).instant
                    } else {
                        wall.toLocalTime() shouldBe local
                    }
                }
            }
        }
    }

    private fun at(time: String) = Instant.parse("2026-10-28T$time:00Z")

    private companion object {
        const val CASES = 150
        const val DAYS = 42
        val TICK: Duration = Duration.ofMinutes(5)
        val FROM: Instant = Instant.parse("2026-01-01T00:00:00Z")
        const val TICKS_IN_TWO_YEARS = 2L * 365 * 288
        const val TICKS_IN_SIX_WEEKS = DAYS * 288L

        val ZONES =
            listOf(
                "Asia/Kathmandu",
                "Pacific/Chatham",
                "Australia/Lord_Howe",
                "America/New_York",
                "Europe/London",
                "America/Santiago",
                "America/Havana",
                "Africa/Lagos",
            ).map(ZoneId::of)
    }
}
