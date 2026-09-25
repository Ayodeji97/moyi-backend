package com.moyi.common.testing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One invariant, and it exists because breaking it produced a test that
 * passed on a Mac and failed on roughly half of CI runs: the clock a test
 * holds must be at the resolution the database stores, so that a round trip
 * through `timestamptz` changes nothing.
 */
internal class MutableClockTest {
    @Test
    fun `the default start carries no precision Postgres would round away`() {
        // Postgres keeps microseconds and *rounds* what it is given. A Linux
        // clock carries nanoseconds, so an untruncated start makes every
        // assertion about a stored instant a coin flip.
        val clock = MutableClock()

        clock.instant() shouldBe clock.instant().truncatedTo(ChronoUnit.MICROS)
    }

    @Test
    fun `advancing keeps that resolution`() {
        val clock = MutableClock()
        val start = clock.instant()

        clock.advance(Duration.ofHours(2))

        clock.instant() shouldBe start.plus(Duration.ofHours(2))
        clock.instant() shouldBe clock.instant().truncatedTo(ChronoUnit.MICROS)
    }

    @Test
    fun `an explicitly set instant is honoured exactly, nanoseconds and all`() {
        // The default is a convenience, not a constraint: a test that means to
        // examine sub-microsecond behaviour can still say so.
        val precise = Instant.parse("2026-09-24T20:00:00.123456789Z")

        MutableClock(start = precise).instant() shouldBe precise
    }
}
