package com.moyi.media.domain

/**
 * The two images kept of a photo (owner's ruling Q2, spec §5): WebP, a *full*
 * one no larger than 2048 px on its longer edge and 350 KiB, and a *display*
 * one no larger than 1280 px and 150 KiB. Together they stay within doc
 * 06:186's 500 KB retained per image. There is no server thumbnail; tier 2
 * (D5) keeps [DISPLAY] alone.
 *
 * A KB is 1024 bytes here, as a MB is a mebibyte in `MediaKind` (ADR-0047).
 */
internal enum class ImageVariant(
    /** The name stored in `variants` and the last part of the object key. */
    val label: String,
    val maxEdge: Int,
    val maxBytes: Long,
) {
    FULL("full", FULL_EDGE, FULL_BYTES),
    DISPLAY("display", DISPLAY_EDGE, DISPLAY_BYTES),
}

/**
 * The rules the image pipeline applies to bytes it did not make (T-07), each
 * written once.
 */
internal object ImageRules {
    /**
     * The most pixels a photo may have, read from its header **before it is
     * decoded** (spec §4 D2, T-07). 64 Mi pixels: a 48-megapixel phone photo
     * (8064 × 6048) fits, and so does a 16384 × 4096 panorama; a decompression
     * bomb (30,000 × 30,000 in a few kilobytes) does not. **It does not bound
     * memory on its own**: a pixel can be 1 byte or 8 (16-bit RGBA), and some
     * formats are decoded whole. [MAX_DECODED_BYTES] is the memory bound.
     */
    const val MAX_PIXELS: Long = 64L * 1024 * 1024

    /**
     * The most bytes a photo may decode to, read from its header before it is
     * decoded: width × height × bands × bytes per sample (review of #108).
     * 100 MiB, chosen by measurement (ADR-0048 §3): just under it, on libvips
     * 8.15.1 under the worker's 640 MiB address-space limit, the decode's peak
     * resident memory was 283 MiB for a HEIC (the worst format: libheif keeps
     * its own planes besides libvips' image), 151 MiB for an interlaced 16-bit
     * RGBA PNG, 144 MiB for an interlaced 8-bit PNG, 127 MiB for a WebP and
     * 71 MiB for a JPEG (shrunk as it is decoded). An 8-bit RGB photo up to
     * about 35 megapixels fits (a 24-megapixel phone photo does; a 48-megapixel
     * one, at 139 MiB, does not); 16-bit RGBA up to about 13.
     */
    const val MAX_DECODED_BYTES: Long = 100L * 1024 * 1024

    /**
     * WebP quality, tried highest first until a variant fits its byte cap
     * (spec §4 D2: "lowering quality until they fit").
     */
    @Suppress("MagicNumber") // The ladder is the constant; naming each rung would say nothing more.
    val QUALITIES: List<Int> = listOf(80, 70, 60, 50, 40)

    /**
     * When even the lowest quality does not fit, the variant is made smaller and
     * the qualities tried again, at these fractions of its edge. A photo never
     * needs this; a picture of noise can.
     */
    @Suppress("MagicNumber") // As QUALITIES: the list is the constant.
    val SHRINK_STEPS: List<Double> = listOf(1.0, 0.75, 0.5)

    /** Whether an image this size may be decoded at all. A header that says zero or less is no image. */
    fun mayDecode(
        width: Int,
        height: Int,
    ): Boolean = width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS

    /**
     * Why an image whose header says these things may not be decoded, or null
     * when it may: the pixel ceiling, then the decoded-bytes ceiling. A header
     * with no bands, or a sample format this rule does not know, is unreadable.
     */
    fun headerRefusal(
        width: Int,
        height: Int,
        bands: Int,
        format: String,
    ): MediaRefusal? {
        val sample = bytesPerSample(format)
        return when {
            !mayDecode(width, height) -> MediaRefusal.TOO_MANY_PIXELS
            bands <= 0 || sample == null -> MediaRefusal.UNREADABLE
            width.toLong() * height * bands * sample > MAX_DECODED_BYTES -> MediaRefusal.TOO_LARGE_DECODED
            else -> null
        }
    }

    /** Bytes per sample of libvips' band formats, by the name `vipsheader` prints. */
    @Suppress("MagicNumber") // The widths of the formats are the table.
    fun bytesPerSample(format: String): Int? =
        when (format) {
            "uchar", "char" -> 1
            "ushort", "short" -> 2
            "uint", "int", "float" -> 4
            "double", "complex" -> 8
            "dpcomplex" -> 16
            else -> null
        }

    /** [edge] scaled by [fraction], never below one pixel. */
    fun shrunk(
        edge: Int,
        fraction: Double,
    ): Int = maxOf(1, (edge * fraction).toInt())
}

private const val KIBIBYTE = 1024L
private const val FULL_EDGE = 2048
private const val DISPLAY_EDGE = 1280
private const val FULL_BYTES = 350 * KIBIBYTE
private const val DISPLAY_BYTES = 150 * KIBIBYTE
