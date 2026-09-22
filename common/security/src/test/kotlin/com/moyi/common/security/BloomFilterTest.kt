package com.moyi.common.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.random.Random

internal class BloomFilterTest {
    @Test
    fun `every member it was given is found again`() {
        // The property the whole control rests on: a Bloom filter has no
        // false negatives, so `false` from mightContain means genuinely
        // absent. A bug in the index derivation — a sign-extended byte, a
        // signed remainder — shows up here and nowhere else, because it
        // degrades recall rather than throwing.
        val filter = newFilter(expected = MEMBERS)
        val members = digests(0 until MEMBERS)

        members.forEach(filter::add)

        members.count(filter::mightContain) shouldBe MEMBERS
    }

    @Test
    fun `the false-positive rate is close to the one it was sized for`() {
        val filter = newFilter(expected = MEMBERS, falsePositiveRate = TARGET_FPR)
        digests(0 until MEMBERS).forEach(filter::add)

        val absent = digests(MEMBERS until MEMBERS + PROBES)
        val observed = absent.count(filter::mightContain).toDouble() / PROBES

        // Three times the target, not the target itself: the observed rate is
        // a binomial sample and a test that asserts the nominal value is a
        // test that fails on a bad afternoon. A broken implementation misses
        // this bound by orders of magnitude, not by a factor of three — the
        // bound is here to catch "k is wrong" and "m is wrong", which land
        // near 1.0, not to measure the constant.
        observed shouldBeLessThan TARGET_FPR * 3
    }

    @Test
    fun `sizing follows the standard formulas`() {
        // n = 10M at p = 0.001 is the case ADR-0012 costs at ~17 MB and
        // `10` §2.1 budgets heap for. Asserting it here means a change to the
        // formula shows up as a failing test rather than as an image that is
        // quietly 60 MB bigger.
        val bits = BloomFilter.optimalBitCount(TEN_MILLION, TARGET_FPR)
        val bytes = bits / Byte.SIZE_BITS

        (bytes / 1024 / 1024) shouldBe 17L
        BloomFilter.optimalHashCount(bits, TEN_MILLION) shouldBe 10
    }

    @Test
    fun `a filter survives a round trip through its own format`() {
        val original = newFilter(expected = SMALL)
        val members = digests(0 until SMALL)
        members.forEach(original::add)

        val restored = roundTrip(original)

        restored.bitCount shouldBe original.bitCount
        restored.hashCount shouldBe original.hashCount
        restored.insertedCount shouldBe SMALL.toLong()
        restored.metadata shouldBe original.metadata
        members.count(restored::mightContain) shouldBe SMALL
        restored.sizeInBytes shouldBeGreaterThan 0L
    }

    @Test
    fun `a file that is not one of ours is refused`() {
        // The failure being prevented is silent: a filter read from the wrong
        // bytes answers "not breached" to everything and goes on looking like
        // a running control.
        val notOurs = ByteArrayInputStream(ByteArray(64) { 0 })

        shouldThrow<IllegalArgumentException> { BloomFilter.readFrom(notOurs) }
    }

    @Test
    fun `a file from a future format version is refused rather than misread`() {
        val bytes = roundTripBytes(newFilter(expected = SMALL))
        // Byte 4..7 is the format version, immediately after the magic.
        ByteBuffer.wrap(bytes).putInt(Int.SIZE_BYTES, BloomFilter.FORMAT_VERSION + 1)

        val rejected = shouldThrow<IllegalArgumentException> { BloomFilter.readFrom(ByteArrayInputStream(bytes)) }

        rejected.message shouldBe "unsupported format version ${BloomFilter.FORMAT_VERSION + 1}, expected ${BloomFilter.FORMAT_VERSION}"
    }

    @Test
    fun `a truncated file fails rather than reading as a filter with a zeroed tail`() {
        // A short read would leave the end of the bit array zeroed, which is
        // indistinguishable from "not breached" for every password whose bits
        // land there — a partial control that reports as a whole one.
        val bytes = roundTripBytes(newFilter(expected = SMALL))

        shouldThrow<EOFException> { BloomFilter.readFrom(ByteArrayInputStream(bytes.copyOf(bytes.size - Long.SIZE_BYTES))) }
    }

    @Test
    fun `a digest too short to derive two hashes from is rejected`() {
        val filter = newFilter(expected = SMALL)

        shouldThrow<IllegalArgumentException> { filter.mightContain(ByteArray(8)) }
    }

    @Test
    fun `sizing rejects arguments that have no filter`() {
        shouldThrow<IllegalArgumentException> { BloomFilter.optimalBitCount(0, TARGET_FPR) }
        shouldThrow<IllegalArgumentException> { BloomFilter.optimalBitCount(SMALL.toLong(), 0.0) }
        shouldThrow<IllegalArgumentException> { BloomFilter.optimalBitCount(SMALL.toLong(), 1.0) }
        shouldThrow<IllegalArgumentException> { BloomFilter.optimalHashCount(SMALL.toLong(), 0) }
    }

    @Test
    fun `an empty filter contains nothing`() {
        val filter = newFilter(expected = SMALL)

        digests(0 until SMALL).count(filter::mightContain) shouldBe 0
        filter.insertedCount shouldBe 0L
    }

    private fun newFilter(
        expected: Int,
        falsePositiveRate: Double = TARGET_FPR,
    ) = BloomFilter.create(
        expectedInsertions = expected.toLong(),
        falsePositiveRate = falsePositiveRate,
        source = "test",
        builtAt = Instant.parse("2026-09-22T00:00:00Z"),
    )

    private fun roundTripBytes(filter: BloomFilter): ByteArray = ByteArrayOutputStream().also(filter::writeTo).toByteArray()

    private fun roundTrip(filter: BloomFilter): BloomFilter = BloomFilter.readFrom(ByteArrayInputStream(roundTripBytes(filter)))

    /**
     * Deterministic 20-byte values standing in for SHA-1 digests. A fixed seed
     * because a false-positive measurement that changes run to run is a test
     * that fails for reasons unrelated to the change being made.
     */
    private fun digests(range: IntRange): List<ByteArray> {
        val random = Random(SEED)
        return (0..range.last).map { ByteArray(DIGEST_BYTES).also(random::nextBytes) }.slice(range)
    }

    private companion object {
        const val DIGEST_BYTES = 20
        const val SEED = 20260922L
        const val MEMBERS = 50_000
        const val PROBES = 200_000
        const val SMALL = 500
        const val TARGET_FPR = 0.001
        const val TEN_MILLION = 10_000_000L
    }
}
