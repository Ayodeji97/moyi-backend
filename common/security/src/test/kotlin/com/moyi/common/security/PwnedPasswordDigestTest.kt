package com.moyi.common.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

internal class PwnedPasswordDigestTest {
    @Test
    fun `it produces the digest HIBP actually publishes`() {
        // Checked against the live endpoint on 2026-09-22: `password` sits at
        // prefix 5BAA6 with 52,372,427 occurrences. Pinning the literal is what
        // makes this a test of our agreement with *their* corpus rather than of
        // our agreement with ourselves.
        hex(PwnedPasswordDigest.of("password")) shouldBe "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"
        hex(PwnedPasswordDigest.of("123456")) shouldBe "7C4A8D09CA3762AF61E59520943DC26494F8941B"
    }

    @Test
    fun `the same password typed two ways gives one digest`() {
        // The failure this prevents is silent and total: a lookup keyed on a
        // digest computed differently from the corpus misses every time, and
        // the breached-password check is off while reporting that it is on.
        //
        // Escapes, not literal accented characters: the whole point is that
        // the two forms are different byte sequences, and a source file cannot
        // show that difference.
        val precomposed = "caf\u00E9"
        val decomposed = "cafe\u0301"

        precomposed shouldNotBe decomposed
        hex(PwnedPasswordDigest.of(precomposed)) shouldBe hex(PwnedPasswordDigest.of(decomposed))
    }

    @Test
    fun `a digest is twenty bytes, which is what the filter indexes`() {
        PwnedPasswordDigest.of("anything").size shouldBe 20
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
}
