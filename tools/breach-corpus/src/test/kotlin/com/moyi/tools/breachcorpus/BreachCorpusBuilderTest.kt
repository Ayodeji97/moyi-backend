package com.moyi.tools.breachcorpus

import com.moyi.common.security.BloomFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * The builder driven against a fake range source, so the parts that decide
 * whether the resulting file can be trusted are exercised without a million
 * HTTP requests.
 */
internal class BreachCorpusBuilderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the filter holds every digest above the threshold and none below it`() {
        val report = build(minCount = 700)

        report.insertedCount shouldBe 2 * RANGES
        val filter = BloomFilter.readFrom(output.inputStream())
        prevalentDigests().count(filter::mightContain) shouldBe 2 * RANGES
        // Not an assertion about false positives — a rare digest is simply not
        // a member, and at this size the filter is far too sparse to claim one.
        rareDigests().count(filter::mightContain) shouldBe 0
    }

    @Test
    fun `the filter is sized from what was actually collected, not from the target`() {
        // ADR-0012 asks for ~10M members at a 0.1% false-positive rate. The
        // real count comes out of a threshold, not a plan, so sizing from the
        // plan would put the rate somewhere nobody chose.
        val report = build(minCount = 700)
        val filter = BloomFilter.readFrom(output.inputStream())

        filter.insertedCount shouldBe report.insertedCount
        filter.bitCount shouldBe BloomFilter.optimalBitCount(report.insertedCount, FPR)
        filter.metadata.falsePositiveRate shouldBe FPR
        filter.metadata.source shouldContain "minCount=700"
        report.fileSizeBytes shouldBeGreaterThan 0L
    }

    @Test
    fun `a range that cannot be fetched aborts the build instead of shrinking the corpus`() {
        // The whole point. A partial corpus produces a perfectly well-formed
        // file whose missing members are undetectable downstream, so the only
        // place this can be caught is here.
        val failing =
            RangeSource { prefix ->
                if (prefix == "00003") throw IOException("range unavailable") else body(prefix)
            }

        val failure = shouldThrow<IOException> { builder(minCount = 700, source = failing).build() }

        failure.message shouldContain "did not complete"
    }

    @Test
    fun `a threshold nothing meets is treated as a bug, not as an empty corpus`() {
        val failure = shouldThrow<IllegalStateException> { build(minCount = 1_000_000) }

        failure.message shouldContain "check the parser before the threshold"
    }

    @Test
    fun `malformed lines are counted into the report rather than absorbed`() {
        // A run that quietly parsed nothing and a run that parsed everything
        // both end with a valid file. The malformed count is the only thing
        // that tells them apart, so it has to reach the caller.
        val halfBroken = RangeSource { prefix -> body(prefix) + "\nnot-an-entry\nalso-not-an-entry" }

        val report = builder(minCount = 700, source = halfBroken).build()

        report.malformedLines shouldBe 2L * RANGES
        report.entriesScanned shouldBe 6L * RANGES
        report.insertedCount shouldBe 2L * RANGES
    }

    private val output: Path get() = directory.resolve("pwned.bloom")

    private fun build(minCount: Int) = builder(minCount).build()

    private fun builder(
        minCount: Int,
        source: RangeSource = RangeSource(::body),
    ) = BreachCorpusBuilder(
        options =
            BuildOptions(
                output = output,
                minCount = minCount,
                falsePositiveRate = FPR,
                concurrency = CONCURRENCY,
                prefixLimit = RANGES,
            ),
        client = source,
        log = { },
    )

    /** Two prevalent entries and two rare ones per range, so the threshold has something to do. */
    private fun body(prefix: String) =
        """
        |${suffix(prefix, 'A')}:9999
        |${suffix(prefix, 'B')}:700
        |${suffix(prefix, 'C')}:699
        |${suffix(prefix, 'D')}:1
        """.trimMargin()

    private fun suffix(
        prefix: String,
        marker: Char,
    ) = (marker.toString() + prefix).padEnd(PwnedRange.SUFFIX_LENGTH, '0').take(PwnedRange.SUFFIX_LENGTH)

    private fun prevalentDigests() = digestsMarked('A') + digestsMarked('B')

    private fun rareDigests() = digestsMarked('C') + digestsMarked('D')

    private fun digestsMarked(marker: Char) =
        PwnedRange
            .allPrefixes()
            .take(RANGES)
            .map { PwnedRange.hexToBytes(it + suffix(it, marker))!! }
            .toList()

    private companion object {
        const val RANGES = 16
        const val CONCURRENCY = 4
        const val FPR = 0.01
    }
}
