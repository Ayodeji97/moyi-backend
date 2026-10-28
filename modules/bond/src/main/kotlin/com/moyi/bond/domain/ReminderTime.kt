package com.moyi.bond.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * When a member's daily reminder falls (FR-081, spec §4 N2, ADR-0045): their
 * `reminder_time_local`, on their own wall clock in their `reminder_timezone`,
 * once a day.
 *
 * **Once a day, whatever the clock does.** A reminder time the clock skips
 * (02:30 on the night New York springs forward) falls when the clock jumps
 * past it; a time the clock reads twice (01:30 on the night it falls back)
 * falls at its first reading only. So a member is reminded every day and
 * never twice in one.
 */
internal object ReminderTime {
    /**
     * The instant [local] fell at in [zone] within `(after, upTo]`, or `null`
     * if it did not. A window shorter than a day holds at most one.
     */
    fun occurrenceIn(
        local: LocalTime,
        zone: ZoneId,
        after: Instant,
        upTo: Instant,
    ): Instant? {
        require(after.isBefore(upTo)) { "a window ends after it begins" }
        val first = after.atZone(zone).toLocalDate().minusDays(1)
        val last = upTo.atZone(zone).toLocalDate().plusDays(1)
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .map { occurrenceOn(LocalDateTime.of(it, local), zone) }
            .firstOrNull { it.isAfter(after) && !it.isAfter(upTo) }
    }

    /** The one instant the wall reads [wall]: the first reading in an overlap, the jump past it in a gap. */
    private fun occurrenceOn(
        wall: LocalDateTime,
        zone: ZoneId,
    ): Instant {
        val offsets = zone.rules.getValidOffsets(wall)
        return if (offsets.isEmpty()) zone.rules.getTransition(wall).instant else offsets.minOf { wall.toInstant(it) }
    }
}
