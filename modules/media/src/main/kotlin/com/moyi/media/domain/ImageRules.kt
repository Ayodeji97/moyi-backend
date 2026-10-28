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
     * bomb (30,000 × 30,000 in a few kilobytes) does not. Decoding is what
     * costs memory, and the worker's 768 MB (Q5) holds one decode of this size
     * with room to spare: about 200 MB of 8-bit RGB.
     */
    const val MAX_PIXELS: Long = 64L * 1024 * 1024

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

    /** [edge] scaled by [fraction], never below one pixel. */
    fun shrunk(
        edge: Int,
        fraction: Double,
    ): Int = maxOf(1, (edge * fraction).toInt())
}

/**
 * Why a photo cannot be made into its variants. Every one is final: the same
 * bytes would fail the same way again, so the asset becomes `FAILED` and the
 * delivery is not retried (spec §4 D2: "Unrecoverable input → FAILED"). A
 * storage or database that cannot be reached is not one of these; that is
 * thrown, and the outbox tries again.
 */
internal enum class ImageRefusal {
    /** Nothing is stored under the upload's key any more. */
    SOURCE_MISSING,

    /** The storage holds more bytes than were declared and signed. */
    LONGER_THAN_DECLARED,

    /** The bytes do not have the SHA-256 that was declared and signed. */
    CHECKSUM_MISMATCH,

    /** The first bytes are none of JPEG, PNG, WebP and HEIC ([ImageFormat]). */
    NOT_AN_ALLOWED_FORMAT,

    /** The first bytes are an allowed format, but not the one the upload declared. */
    NOT_THE_DECLARED_FORMAT,

    /** The header says more than [ImageRules.MAX_PIXELS], or no size at all. */
    TOO_MANY_PIXELS,

    /** The decoder could not read it, or stopped at a limit (memory, CPU time). */
    UNREADABLE,

    /** No variant fitted its byte cap at any quality or size tried. */
    CANNOT_FIT,

    /** The work did not finish within its time limit. */
    TIMED_OUT,
}

private const val KIBIBYTE = 1024L
private const val FULL_EDGE = 2048
private const val DISPLAY_EDGE = 1280
private const val FULL_BYTES = 350 * KIBIBYTE
private const val DISPLAY_BYTES = 150 * KIBIBYTE
