package com.moyi.media.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The allow-list of image formats, read from the bytes and never from a name or a declared type (T-07). */
internal class ImageFormatTest {
    @Test
    fun `each allowed format is known by its first bytes`() {
        ImageFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)) shouldBe ImageFormat.JPEG
        ImageFormat.of(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)) shouldBe ImageFormat.PNG
        ImageFormat.of("RIFF".ascii() + bytes(0x24, 0, 0, 0) + "WEBPVP8 ".ascii()) shouldBe ImageFormat.WEBP
        for (brand in listOf("heic", "heix")) {
            ImageFormat.of(ftyp(brand, "mif1", "miaf")) shouldBe ImageFormat.HEIC
        }
        // `mif1` says only "an image file"; a compatible brand must name HEVC.
        ImageFormat.of(ftyp("mif1", "mif1", "heic")) shouldBe ImageFormat.HEIC
        ImageFormat.of(ftyp("mif1", "mif1", "hevc")) shouldBe ImageFormat.HEIC
        ImageFormat.of(TestResources.bytes("sample.heic")) shouldBe ImageFormat.HEIC
    }

    @Test
    fun `an AVIF is not a HEIC, by its major brand or by a mif1 one with no HEVC brand beside it`() {
        ImageFormat.of(ftyp("avif", "mif1", "avif", "miaf")).shouldBeNull()
        ImageFormat.of(ftyp("mif1", "mif1", "avif", "miaf")).shouldBeNull()
        ImageFormat.of(ftyp("mif1", "mif1", "miaf")).shouldBeNull()
    }

    /** An `ftyp` box: size, type, major brand, minor version 0, compatible brands. */
    private fun ftyp(
        major: String,
        vararg compatible: String,
    ): ByteArray {
        val size = 16 + 4 * compatible.size
        return bytes(0, 0, 0, size) + "ftyp$major".ascii() + bytes(0, 0, 0, 0) + compatible.joinToString("").ascii()
    }

    @Test
    fun `everything else is refused, however it is named`() {
        // GIF, TIFF (both byte orders), BMP, an SVG, a PDF, a RIFF that is a WAV,
        // an ISO box that is an MP4 or an AVIF, and the empty file.
        val others =
            listOf(
                "GIF89a".ascii(),
                bytes(0x49, 0x49, 0x2A, 0),
                bytes(0x4D, 0x4D, 0, 0x2A),
                "BM".ascii() + bytes(0, 0, 0, 0),
                "<svg xmlns=".ascii(),
                "%PDF-1.7".ascii(),
                "RIFF".ascii() + bytes(0x24, 0, 0, 0) + "WAVEfmt ".ascii(),
                bytes(0, 0, 0, 0x10) + "ftypisom".ascii() + bytes(0, 0, 0, 0),
                bytes(0, 0, 0, 0x10) + "ftypavif".ascii() + bytes(0, 0, 0, 0),
                ByteArray(0),
            )
        for (head in others) ImageFormat.of(head).shouldBeNull()
    }

    @Test
    fun `a prefix too short to hold the magic is no format`() {
        ImageFormat.of(bytes(0xFF, 0xD8)).shouldBeNull()
        ImageFormat.of("RIFF".ascii() + bytes(0, 0, 0, 0) + "WEB".ascii()).shouldBeNull()
        ImageFormat.of(bytes(0, 0, 0, 0x18) + "ftyphei".ascii()).shouldBeNull()
        ImageFormat.of(bytes(0, 0, 0, 0x18) + "ftypmif1".ascii()).shouldBeNull()
    }

    @Test
    fun `a format agrees only with its own declared type`() {
        ImageFormat.PNG.agreesWith("image/png") shouldBe true
        ImageFormat.PNG.agreesWith("image/jpeg") shouldBe false
        ImageFormat.HEIC.agreesWith("image/heic") shouldBe true
        ImageFormat.entries.map { it.contentType }.toSet() shouldBe MediaKind.IMAGE.contentTypes
    }

    private fun String.ascii() = toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
