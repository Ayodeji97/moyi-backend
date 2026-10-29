package com.moyi.media.domain

/**
 * The four image formats an upload may be (FR-042), each known by its first
 * bytes (spec §4 D2, T-07): JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`,
 * WebP `RIFF····WEBP`, HEIC an ISO `ftyp` box whose major brand is `heic`,
 * `heix` or `mif1` **and** whose brands (the major and the compatible ones)
 * name HEVC: `heic`, `heix` or `hevc`. `mif1` alone is only "an image file
 * format" and is AVIF's major brand too (review of #108); the processor then
 * also asks libvips which codec the file holds (`heif-compression`), since a
 * brand list is a claim the file makes about itself.
 *
 * **The allow-list is this enum and nothing else.** [of] answers `null` for
 * every other file, a GIF, a TIFF, an SVG or a PDF included, and the pipeline
 * refuses those before any decoder is started: the decoder is never asked to
 * decide what a file is. The bytes decide, never the declared type: a file
 * whose bytes are one format and whose declaration names another is refused
 * too ([agreesWith]).
 */
internal enum class ImageFormat(
    /** The upload content type this format is declared as (MediaKind.IMAGE's list). */
    val contentType: String,
) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    HEIC("image/heic"),
    ;

    /** Whether an upload declared as [declaredType] may be these bytes. */
    fun agreesWith(declaredType: String): Boolean = declaredType == contentType

    companion object {
        /** How many leading bytes [of] reads: enough for an `ftyp` box and its compatible brands. */
        const val SNIFF_BYTES = 64

        private val JPEG_MAGIC = bytes(0xFF, 0xD8, 0xFF)
        private val PNG_MAGIC = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
        private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
        private val HEIC_MAJOR_BRANDS = setOf("heic", "heix", "mif1")
        private val HEVC_BRANDS = setOf("heic", "heix", "hevc")
        private const val WEBP_TAG_AT = 8
        private const val BOX_TYPE_AT = 4
        private const val BRAND_AT = 8
        private const val COMPATIBLE_AT = 16
        private const val BRAND_LENGTH = 4

        /** The format [head] (a file's first bytes) is, or `null` for anything not on the list. */
        fun of(head: ByteArray): ImageFormat? =
            when {
                head.startsWith(JPEG_MAGIC) -> JPEG
                head.startsWith(PNG_MAGIC) -> PNG
                head.startsWith(RIFF) && head.startsWith(WEBP_MAGIC, at = WEBP_TAG_AT) -> WEBP
                head.startsWith(FTYP, at = BOX_TYPE_AT) && isHevcHeif(head) -> HEIC
                else -> null
            }

        /** The `ftyp` box's major brand is a HEIF one, and some brand of it names HEVC. */
        private fun isHevcHeif(head: ByteArray): Boolean {
            val major = head.brandAt(BRAND_AT) ?: return false
            val boxSize = (0 until BRAND_LENGTH).fold(0) { size, i -> (size shl BITS) or (head[i].toInt() and BYTE) }
            val end = minOf(boxSize, head.size)
            val compatible = (COMPATIBLE_AT until end step BRAND_LENGTH).mapNotNull { head.brandAt(it) }
            return major in HEIC_MAJOR_BRANDS && (compatible + major).any { it in HEVC_BRANDS }
        }

        private fun ByteArray.startsWith(
            prefix: ByteArray,
            at: Int = 0,
        ): Boolean = size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

        private fun ByteArray.brandAt(at: Int): String? =
            if (size < at + BRAND_LENGTH) null else String(this, at, BRAND_LENGTH, Charsets.US_ASCII)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        private const val BYTE = 0xFF
        private const val BITS = 8
    }
}
