package com.moyi.media.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The pixel ceiling and the variant targets (spec §4 D2, owner's ruling Q2). */
internal class ImageRulesTest {
    @Test
    fun `a 48-megapixel photo and a long panorama may be decoded`() {
        ImageRules.mayDecode(8064, 6048) shouldBe true
        ImageRules.mayDecode(16384, 4096) shouldBe true
    }

    @Test
    fun `a decompression bomb, one pixel over, and a header with no size may not`() {
        ImageRules.mayDecode(30_000, 30_000) shouldBe false
        ImageRules.mayDecode(16384, 4097) shouldBe false
        ImageRules.mayDecode(Int.MAX_VALUE, Int.MAX_VALUE) shouldBe false
        ImageRules.mayDecode(0, 100) shouldBe false
        ImageRules.mayDecode(100, -1) shouldBe false
    }

    @Test
    fun `the two variants are the ruling's, and stay inside doc 06's 500 KB a photo`() {
        ImageVariant.FULL.maxEdge shouldBe 2048
        ImageVariant.FULL.maxBytes shouldBe 350L * 1024
        ImageVariant.DISPLAY.maxEdge shouldBe 1280
        ImageVariant.DISPLAY.maxBytes shouldBe 150L * 1024
        (ImageVariant.entries.sumOf { it.maxBytes } <= 500L * 1024) shouldBe true
    }

    @Test
    fun `quality only ever goes down, and a shrunk edge is never zero`() {
        ImageRules.QUALITIES shouldBe ImageRules.QUALITIES.sortedDescending()
        ImageRules.shrunk(2048, 0.75) shouldBe 1536
        ImageRules.shrunk(1, 0.5) shouldBe 1
    }
}
