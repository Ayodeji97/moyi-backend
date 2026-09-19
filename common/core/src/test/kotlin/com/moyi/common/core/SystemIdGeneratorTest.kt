package com.moyi.common.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * These assert the *bit layout*, not just "it returns a UUID". A v7 that is
 * silently a v4 still looks like a UUID at every call site, still inserts,
 * still passes a round-trip test — and quietly costs the index locality the
 * whole choice was made for.
 */
class SystemIdGeneratorTest {
    private val fixedMillis = Instant.parse("2026-09-19T10:15:30.123Z")
    private val generator = SystemIdGenerator(Clock.fixed(fixedMillis, ZoneOffset.UTC))

    @Test
    fun `timeOrdered is a version 7 uuid with the RFC 9562 variant`() {
        val id = generator.timeOrdered()

        id.version() shouldBe 7
        // 2 is what java.util.UUID calls the "IETF RFC 4122" variant — the
        // 0b10 bit pattern, which RFC 9562 inherited unchanged.
        id.variant() shouldBe 2
    }

    @Test
    fun `timeOrdered embeds the clock's millisecond, not the wall clock`() {
        val id = generator.timeOrdered()

        id.timestampMillis() shouldBe fixedMillis.toEpochMilli()
    }

    @Test
    fun `ids made in the same millisecond still sort in creation order`() {
        // The whole claim of "time-ordered" collapses to millisecond
        // granularity without the counter in rand_a — and a millisecond is a
        // long time, easily hundreds of registrations under load.
        val ids = List(SAME_MILLISECOND_SAMPLE) { generator.timeOrdered() }

        ids.map { it.toString() } shouldBe ids.map { it.toString() }.sorted()
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `exhausting a millisecond borrows the next one instead of repeating`() {
        // rand_a is 12 bits, so 4096 ids is the ceiling for one millisecond.
        // Asking for more than that on a stopped clock is the only way to
        // reach the branch, and it is reachable in production too: 4096 ids
        // in a millisecond is a batch import, not a fantasy.
        val ids = List(COUNTER_CAPACITY + 1) { generator.timeOrdered() }

        ids.toSet().size shouldBe ids.size
        ids.map { it.toString() } shouldBe ids.map { it.toString() }.sorted()
        ids.map { it.timestampMillis() }.toSet() shouldBe
            setOf(fixedMillis.toEpochMilli(), fixedMillis.toEpochMilli() + 1)
    }

    @Test
    fun `ids stay ordered when the clock steps backwards`() {
        // NTP corrections move clocks backwards. A generator that trusts the
        // clock emits an id that sorts before ids it already issued, and
        // every consumer that believed "time-ordered" is now wrong.
        val clock = SettableClock(fixedMillis)
        val generator = SystemIdGenerator(clock)

        val before = generator.timeOrdered()
        clock.now = fixedMillis.minusSeconds(1)
        val after = generator.timeOrdered()

        (after.toString() > before.toString()) shouldBe true
        after.timestampMillis() shouldBe fixedMillis.toEpochMilli()
    }

    @Test
    fun `opaque is a version 4 uuid and carries no timestamp`() {
        val ids = List(OPAQUE_SAMPLE) { generator.opaque() }

        ids.map { it.version() }.toSet() shouldBe setOf(4)
        ids.map { it.variant() }.toSet() shouldBe setOf(2)
        // If these were time-ordered they would be sorted already, because
        // the clock is frozen and they were made in one loop.
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `concurrent callers never receive the same id`() {
        // Doc 18 §6 question 2. The counter is shared mutable state read and
        // written by every caller, and virtual threads make "every caller"
        // mean thousands. A lost update here is not a crash — it is two users
        // with the same primary key, discovered by a unique-constraint
        // violation on whichever one inserts second.
        val clock = SettableClock(fixedMillis)
        val generator = SystemIdGenerator(clock)
        val start = CountDownLatch(1)
        val ids = ConcurrentHashMap.newKeySet<UUID>()

        val threads =
            List(CONCURRENT_THREADS) {
                Thread.ofVirtual().unstarted {
                    start.await()
                    repeat(IDS_PER_THREAD) { ids.add(generator.timeOrdered()) }
                }
            }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        ids.size shouldBe CONCURRENT_THREADS * IDS_PER_THREAD
    }

    /** The leading 48 bits of a v7, per RFC 9562 §5.7. */
    private fun UUID.timestampMillis(): Long = mostSignificantBits ushr TIMESTAMP_SHIFT

    private class SettableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private companion object {
        const val TIMESTAMP_SHIFT = 16
        const val COUNTER_CAPACITY = 4096
        const val SAME_MILLISECOND_SAMPLE = 500
        const val OPAQUE_SAMPLE = 100
        const val CONCURRENT_THREADS = 64
        const val IDS_PER_THREAD = 200
    }
}
