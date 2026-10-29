package com.moyi.media.domain

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A HEIC's items, read from its boxes (review of #108). `av1-as-heic.heic` is an
 * AVIF libvips made from a 96 × 64 PNG, with its major brand rewritten to
 * `mif1` and its compatible `avif` to `heic`: every brand check passes, and
 * libvips reports `heif-compression: hevc` for it, then decodes the AV1.
 */
internal class HeifItemsTest {
    @Test
    fun `a HEIC of HEVC images is HEVC only`() {
        val heic = TestResources.bytes("sample.heic")

        HeifItems.itemTypes(heic)!! shouldContain "hvc1"
        HeifItems.isHevcOnly(heic) shouldBe true
    }

    @Test
    fun `an AVIF under HEIC brands lists AV1 images, and is not`() {
        val disguised = TestResources.bytes("av1-as-heic.heic")

        ImageFormat.of(disguised) shouldBe ImageFormat.HEIC
        HeifItems.itemTypes(disguised)!! shouldContain "av01"
        HeifItems.isHevcOnly(disguised) shouldBe false
    }

    @Test
    fun `boxes that cannot be read are not HEVC`() {
        HeifItems.itemTypes(ByteArray(0)).shouldBeNull()
        HeifItems.isHevcOnly("not a box at all".toByteArray()) shouldBe false
        // A box that claims more bytes than the file has.
        HeifItems.isHevcOnly(byteArrayOf(0x7F, 0, 0, 0) + "meta".toByteArray()) shouldBe false
    }
}
