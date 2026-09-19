package com.moyi.common.core

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The production [IdGenerator]: UUID v7 laid out by hand per RFC 9562 §5.7,
 * UUID v4 from the JDK.
 *
 * **Why hand-rolled rather than a library.** Doc 18 §8 prefers the standard
 * library, then Spring, then a well-maintained library, then writing it.
 * The JDK has no v7 factory, Spring has none either, and the obvious
 * library (`com.fasterxml.uuid:java-uuid-generator`) cannot be told which
 * [Clock] to read — which is the whole point of the port. What is left is
 * forty lines of bit layout. Note that none of it is cryptography: every
 * random bit comes from [SecureRandom], which is exactly the line doc 25
 * D5 draws ("no hand-written crypto, ever").
 *
 * **The layout** (128 bits, most significant first):
 * ```
 *  0..47   unix_ts_ms   milliseconds since the epoch
 * 48..51   version      0b0111
 * 52..63   rand_a       12 bits — used here as a monotonic counter
 * 64..65   variant      0b10
 * 66..127  rand_b       62 random bits  (≈74 bits of entropy with rand_a)
 * ```
 *
 * **Why `rand_a` is a counter.** RFC 9562 §6.2 method 2 allows replacing
 * the leading random bits with an increasing counter so that ids created
 * inside the same millisecond still sort in creation order. Without it,
 * "time-ordered" holds only at millisecond granularity, which is not a
 * property a test — or an index — can rely on. The counter is *seeded*
 * randomly in the lower half of its range on each new millisecond rather
 * than reset to zero, so an id still does not advertise how many ids
 * preceded it.
 */
@Component
class SystemIdGenerator(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) : IdGenerator {
    /** One millisecond and the counter position reached within it. Replaced atomically, never mutated. */
    private data class Tick(
        val timestampMs: Long,
        val counter: Int,
    )

    private val lastTick = AtomicReference(Tick(Long.MIN_VALUE, 0))

    override fun timeOrdered(): UUID {
        val tick = nextTick()
        val mostSignificantBits =
            ((tick.timestampMs and TIMESTAMP_MASK) shl TIMESTAMP_SHIFT) or
                (VERSION_7 shl VERSION_SHIFT) or
                tick.counter.toLong()
        val leastSignificantBits = (random.nextLong() and RAND_B_MASK) or VARIANT_RFC_9562
        return UUID(mostSignificantBits, leastSignificantBits)
    }

    /** [UUID.randomUUID] is already a v4 drawn from a [SecureRandom]; there is nothing here worth writing ourselves. */
    override fun opaque(): UUID = UUID.randomUUID()

    /**
     * Claims the next (millisecond, counter) position, atomically.
     *
     * Three cases, and the two that are not the common one are the
     * interesting ones:
     * - the clock has moved on — start a new millisecond with a fresh seed;
     * - it has not, or has moved *backwards* (an NTP step, a leap-second
     *   smear) — keep the millisecond we already published and increment.
     *   Emitting the earlier timestamp would break ordering for every
     *   consumer that trusted it, so a backwards clock is absorbed here
     *   rather than propagated;
     * - the counter is exhausted — 4096 ids inside one millisecond — so
     *   borrow from the next millisecond, which RFC 9562 §6.2 permits.
     *
     * The compare-and-set loop, rather than `synchronized`: virtual threads
     * are enabled (`application.yml`), and a CAS retry never blocks a
     * carrier thread, so there is no pinning question to reason about.
     */
    private fun nextTick(): Tick {
        while (true) {
            val previous = lastTick.get()
            val now = clock.millis()
            val next =
                when {
                    now > previous.timestampMs -> Tick(now, random.nextInt(COUNTER_SEED_BOUND))
                    previous.counter < MAX_COUNTER -> previous.copy(counter = previous.counter + 1)
                    else -> Tick(previous.timestampMs + 1, 0)
                }
            if (lastTick.compareAndSet(previous, next)) return next
        }
    }

    private companion object {
        const val VERSION_7 = 7L
        const val VERSION_SHIFT = 12
        const val TIMESTAMP_SHIFT = 16
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL

        /** Clears the two variant bits so they can be set to 0b10. */
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL

        /** 0b10 in bits 64..65 — the RFC 9562 variant, the same one v4 uses. */
        const val VARIANT_RFC_9562 = Long.MIN_VALUE

        /** `rand_a` is 12 bits wide. */
        const val MAX_COUNTER = (1 shl 12) - 1

        /** Seed in the lower half, so a new millisecond always has room to count. */
        const val COUNTER_SEED_BOUND = (MAX_COUNTER + 1) / 2
    }
}
