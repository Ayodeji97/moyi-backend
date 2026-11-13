package com.moyi.identity.infra

import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * How many backends are waiting for this user's sessions advisory lock, `(1, hashtext(id))` as
 * `RefreshTokenRepository.lockSessions` takes it: in `pg_locks` the two-int form is `classid` 1,
 * `objid` the hash as an oid, `objsubid` 2. Scoped to the key, so a waiter on another user's
 * lock, or on anything else in the database, does not count. The one copy of the formula.
 */
internal fun JdbcTemplate.sessionsLockWaitersOf(userId: UUID): Int =
    queryForObject(
        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = 1 " +
            "AND objsubid = 2 AND objid::bigint = (hashtext(CAST(? AS text))::bigint & 4294967295)",
        Int::class.java,
        userId,
    )!!

/**
 * A one-shot stop a test arms inside the code under test: the next call through it waits until
 * released, after saying it has arrived. Unarmed, it lets every call through. Both waits have a
 * deadline that fails, so a stop nobody releases cannot hang the build.
 */
internal class Gate {
    private val armed = AtomicReference<Stop?>(null)

    fun arm(): Stop = Stop().also { armed.set(it) }

    fun disarm() {
        armed.getAndSet(null)?.release()
    }

    /** Called by the wrapped bean: blocks only the first call after [arm]. */
    fun pass() {
        val stop = armed.getAndSet(null) ?: return
        stop.reached.countDown()
        check(stop.released.await(DEADLINE.seconds, TimeUnit.SECONDS)) { "the gate was never released" }
    }

    class Stop {
        internal val reached = CountDownLatch(1)
        internal val released = CountDownLatch(1)

        fun awaitReached() = check(reached.await(DEADLINE.seconds, TimeUnit.SECONDS)) { "nothing reached the gate" }

        fun release() = released.countDown()
    }

    private companion object {
        val DEADLINE: Duration = Duration.ofSeconds(10)
    }
}
