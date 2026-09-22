package com.moyi.tools.breachcorpus

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class PwnedRangeTest {
    @Test
    fun `only entries at or above the threshold are kept`() {
        val parsed = PwnedRange.qualifyingDigests(PREFIX, BODY, minCount = 700)

        parsed.digests shouldHaveSize 2
        parsed.entriesSeen shouldBe 4
        parsed.malformedLines shouldBe 0
    }

    @Test
    fun `the threshold is inclusive`() {
        // 700 means "appears at least 700 times". Off by one here moves the
        // corpus size by the width of one bucket in a very long tail, which is
        // not visible in any output the job produces.
        PwnedRange.qualifyingDigests(PREFIX, "$SUFFIX_A:700", minCount = 700).digests shouldHaveSize 1
        PwnedRange.qualifyingDigests(PREFIX, "$SUFFIX_A:699", minCount = 700).digests shouldHaveSize 0
    }

    @Test
    fun `the prefix is put back on, because the response does not carry it`() {
        // k-anonymity: HIBP returns only the 35-character suffix, so the full
        // digest exists only once the caller re-attaches the prefix it asked
        // with. Getting this wrong produces 20 valid-looking bytes that match
        // no password in existence — a filter that is empty in effect and
        // full by every measure the job reports.
        val digest = PwnedRange.qualifyingDigests(PREFIX, "$SUFFIX_A:9999", minCount = 1).digests.single()

        digest.toHex() shouldBe (PREFIX + SUFFIX_A)
        digest shouldHaveSize 20
    }

    @Test
    fun `a lowercase prefix still produces the uppercase digest`() {
        val digest = PwnedRange.qualifyingDigests(PREFIX.lowercase(), "${SUFFIX_A.lowercase()}:9999", minCount = 1).digests.single()

        digest.toHex() shouldBe (PREFIX + SUFFIX_A)
    }

    @Test
    fun `malformed lines are counted, not fatal, and not silently dropped`() {
        // Skipping a bad line keeps one oddity from discarding two hours of
        // work; counting it is what stops "skipped three" and "skipped every
        // line because the format changed" looking identical in the output.
        val body =
            """
            |$SUFFIX_A:9999
            |no-colon-here
            |$SUFFIX_A:notanumber
            |TOOSHORT:9999
            |
            """.trimMargin()

        val parsed = PwnedRange.qualifyingDigests(PREFIX, body, minCount = 1)

        parsed.digests shouldHaveSize 1
        parsed.entriesSeen shouldBe 4
        parsed.malformedLines shouldBe 3
    }

    @Test
    fun `blank lines are not entries`() {
        val parsed = PwnedRange.qualifyingDigests(PREFIX, "\n\n$SUFFIX_A:9999\n\n", minCount = 1)

        parsed.entriesSeen shouldBe 1
        parsed.malformedLines shouldBe 0
    }

    @Test
    fun `a prefix of the wrong length is a programming error, not bad input`() {
        shouldThrow<IllegalArgumentException> { PwnedRange.qualifyingDigests("ABC", BODY, minCount = 1) }
    }

    @Test
    fun `hex parsing rejects the wrong length and non-hex characters`() {
        PwnedRange.hexToBytes("AB").shouldBeNull()
        PwnedRange.hexToBytes("Z".repeat(40)).shouldBeNull()
        PwnedRange.hexToBytes("0".repeat(40))!! shouldHaveSize 20
    }

    @Test
    fun `hex parsing does not sign-extend a high byte`() {
        // `Byte` is signed in Kotlin; FF read carelessly becomes -1 and then
        // corrupts every index derived from the digest.
        PwnedRange.hexToBytes("FF".repeat(20))!!.toHex() shouldBe "FF".repeat(20)
    }

    @Test
    fun `there is one prefix for every five hex digits`() {
        val prefixes = PwnedRange.allPrefixes()

        PwnedRange.TOTAL_PREFIXES shouldBe 1_048_576
        prefixes.first() shouldBe "00000"
        prefixes.take(PwnedRange.TOTAL_PREFIXES).last() shouldBe "FFFFF"
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    private companion object {
        const val PREFIX = "00000"

        /** A real entry from `GET /range/00000`, minus its prefix — 35 characters, as the endpoint returns. */
        const val SUFFIX_A = "0005AD76BD555C1D6D771DE417A4B87E4B4"
        val BODY =
            """
            |${SUFFIX_A}:58
            |${SUFFIX_A}:1469
            |${SUFFIX_A}:700
            |${SUFFIX_A}:699
            |
            """.trimMargin()
    }
}
