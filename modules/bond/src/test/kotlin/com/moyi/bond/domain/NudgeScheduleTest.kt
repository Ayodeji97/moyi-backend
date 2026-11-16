package com.moyi.bond.domain

import com.moyi.bond.api.ReminderRoster
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The nudge schedule (C13): reminder time plus elapsed hours, each index once. */
internal class NudgeScheduleTest {
    private val tick = Duration.ofMinutes(5)

    private fun dueIn(
        local: LocalTime,
        zone: ZoneId,
        after: Instant,
        upTo: Instant,
    ) = NudgeSchedule.dueIn(
        local,
        zone,
        after,
        upTo,
        ReminderRoster.NUDGES_PER_BOND_DAY,
        ReminderRoster.NUDGE_EVERY,
    )

    private fun walk(
        local: LocalTime,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<NudgeTime> =
        generateSequence(from) { it.plus(tick) }
            .takeWhile { it.isBefore(to) }
            .flatMap { dueIn(local, zone, it, it.plus(tick)) }
            .toList()

    @Test
    fun `08 00 in London falls at 08 00, 11 00 and 14 00, each once`() {
        val london = ZoneId.of("Europe/London")
        val found = walk(LocalTime.of(8, 0), london, Instant.parse("2027-01-10T00:00:00Z"), Instant.parse("2027-01-11T00:00:00Z"))
        found.map { it.index to it.at } shouldBe
            listOf(
                1 to Instant.parse("2027-01-10T08:00:00Z"),
                2 to Instant.parse("2027-01-10T11:00:00Z"),
                3 to Instant.parse("2027-01-10T14:00:00Z"),
            )
    }

    @Test
    fun `nudges are three elapsed hours apart across spring forward`() {
        // 2027-03-14 New York: 00:30 EST is 05:30Z; the clock springs at 02:00, so +3 h is 04:30 EDT, +6 h 07:30 EDT.
        val found =
            walk(
                LocalTime.of(0, 30),
                ZoneId.of("America/New_York"),
                Instant.parse("2027-03-14T00:00:00Z"),
                Instant.parse("2027-03-15T00:00:00Z"),
            )
        found.map { it.at } shouldBe
            listOf(
                Instant.parse("2027-03-14T05:30:00Z"),
                Instant.parse("2027-03-14T08:30:00Z"),
                Instant.parse("2027-03-14T11:30:00Z"),
            )
    }

    @Test
    fun `the fall-back night gives each index once`() {
        val found =
            walk(
                LocalTime.of(0, 30),
                ZoneId.of("America/New_York"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-02T00:00:00Z"),
            )
        found.map { it.index } shouldBe listOf(1, 2, 3)
        found.map { it.at.epochSecond - it.first.epochSecond } shouldBe listOf(0L, 10800L, 21600L)
    }

    @Test
    fun `half-hour and quarter-hour zones give each index once per local date over six weeks`() {
        listOf("Australia/Lord_Howe", "Asia/Kathmandu").forEach { id ->
            val zone = ZoneId.of(id)
            val local = LocalTime.of(2, 15)
            val found =
                walk(local, zone, Instant.parse("2026-09-25T00:00:00Z"), Instant.parse("2026-11-06T00:00:00Z"))
            found.forEach {
                withClue("$id $it") {
                    (it.at.epochSecond - it.first.epochSecond) shouldBe (it.index - 1) * 10800L
                }
            }
            // The window's first and last local dates are cut by its edges; every date between is whole.
            val perDay = found.groupBy { it.first.atZone(zone).toLocalDate() }.toSortedMap()
            perDay.remove(perDay.firstKey())
            perDay.remove(perDay.lastKey())
            (perDay.size >= 38) shouldBe true
            perDay.forEach { (date: LocalDate, list) ->
                withClue("$id $date") { list.map { it.index } shouldBe listOf(1, 2, 3) }
            }
        }
    }
}
