package com.moyi.common.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] a test can move. Registered as the `@Primary` clock of a test
 * context, it is what lets a test watch a bucket refill fifteen minutes later
 * without waiting fifteen minutes, or a token expire, or a lock lapse —
 * everything that reads the injected clock moves together (doc 18 §3: no bare
 * `Instant.now()` outside `ClockConfig`, which is what makes this possible).
 *
 * Starts at the real time by default, so anything compared against the
 * database's own `now()` still lines up until the test says otherwise.
 */
class MutableClock(
    start: Instant = Instant.now(),
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
