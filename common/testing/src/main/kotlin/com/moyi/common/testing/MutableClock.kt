package com.moyi.common.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A [Clock] a test can move. Registered as the `@Primary` clock of a test
 * context, it is what lets a test watch a bucket refill fifteen minutes later
 * without waiting fifteen minutes, or a token expire, or a lock lapse —
 * everything that reads the injected clock moves together (doc 18 §3: no bare
 * `Instant.now()` outside `ClockConfig`, which is what makes this possible).
 *
 * Starts at the real time by default, so anything compared against the
 * database's own `now()` still lines up until the test says otherwise —
 * **truncated to microseconds**, which is load-bearing rather than tidy.
 *
 * Postgres stores `timestamptz` to microsecond precision and **rounds** what
 * it is given. A Linux clock carries nanoseconds (a Mac's does not), so a
 * value of `…123456789` comes back as `…123457` while a test truncating the
 * same instant expects `…123456`. That is a test which passes on a laptop,
 * passes on roughly half of CI runs, and fails on the rest — the worst kind.
 * Truncating at the source means there is nothing left to round, and the
 * instant a test holds is byte-for-byte the instant the database returns.
 *
 * Found on CI 2026-09-24 (PR #37), two slices after the same nanosecond
 * difference was first met and treated by truncating on the assertion side —
 * which is the right answer only if Postgres truncates too, and it does not.
 */
class MutableClock(
    start: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    @Volatile
    private var now: Instant = start

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }

    fun set(to: Instant) {
        now = to
    }
}
