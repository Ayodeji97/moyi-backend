package com.moyi.notification.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A member's quiet hours as arithmetic (FR-081, Q7, spec §4 N2, ADR-0045).
 *
 * **The window is wall-clock time in the member's zone, `[start, end)`.** An
 * instant is inside when the clock on the member's wall reads a time inside
 * it. That one definition answers the awkward cases without a rule each:
 *
 * - **Across midnight** (22:00 to 07:00): inside from 22:00 to midnight and
 *   from midnight to 07:00.
 * - **A DST gap**: the times the clock skips are never read, so they are
 *   neither inside nor outside; an end that falls in the gap is reached when
 *   the clock jumps past it, at the transition itself, not an hour later.
 * - **A DST overlap**: the times the clock reads twice are inside both times.
 *   A window that ends in the repeated hour ends at its first reading, and the
 *   clock falling back into the window makes the next instant inside again.
 * - **Zones 30 or 45 minutes off the hour** (Kathmandu, Chatham, Lord Howe's
 *   half-hour DST) need nothing: the zone's rules turn the wall into instants.
 *
 * **A window whose start equals its end is empty.** Read as "all day" it would
 * hold every push for ever; as empty it holds none, which is what a member who
 * set the two times alike is likelier to have meant than silence.
 */
internal object QuietHours {
    fun contains(
        instant: Instant,
        zone: ZoneId,
        start: LocalTime,
        end: LocalTime,
    ): Boolean {
        if (start == end) return false
        val wall = instant.atZone(zone).toLocalTime()
        return if (start < end) wall >= start && wall < end else wall >= start || wall < end
    }

    /**
     * The first instant from [instant] on that is not inside the window: when
     * a push that arrived at [instant] may go. [instant] itself when it is not
     * inside.
     *
     * The end is looked for on the wall's date and the day after, at every
     * instant the zone gives that wall time: two in an overlap, the
     * transition in a gap. The earliest one after [instant] is the answer.
     */
    fun holdUntil(
        instant: Instant,
        zone: ZoneId,
        start: LocalTime,
        end: LocalTime,
    ): Instant {
        if (!contains(instant, zone, start, end)) return instant
        val date = instant.atZone(zone).toLocalDate()
        return (0L..2L)
            .asSequence()
            .flatMap { days -> instantsOf(LocalDateTime.of(date.plusDays(days), end), zone) }
            .filter { it.isAfter(instant) }
            .first()
    }

    /** Every instant at which the wall in [zone] reads [wall]; for a wall time the clock skips, the instant it skips it. */
    private fun instantsOf(
        wall: LocalDateTime,
        zone: ZoneId,
    ): Sequence<Instant> {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(wall)
        return if (offsets.isEmpty()) {
            sequenceOf(rules.getTransition(wall).instant)
        } else {
            offsets.asSequence().map { wall.toInstant(it) }.sorted()
        }
    }
}
