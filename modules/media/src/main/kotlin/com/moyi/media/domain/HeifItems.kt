package com.moyi.media.domain

/**
 * What a HEIF file says it holds, read from its boxes rather than its brands:
 * the item types its `iinf` box lists (ISO/IEC 23008-12). A HEIC photo holds
 * HEVC images (`hvc1`), perhaps tiled (`grid`), and their metadata.
 *
 * **Why not the brands, and why not libvips' `heif-compression`.** An AVIF is
 * a HEIF of AV1 images (`av01`). Rename its brands to HEIC's and every brand
 * check passes, and libvips 8.15 to 8.18, which reads `heif-compression` from
 * the brands, reports `hevc` too, then decodes the AV1 (seen, review of
 * #108). The item types are what the decoder actually decodes.
 */
internal object HeifItems {
    /** Item types a HEIC photo may hold: its HEVC images, grids and derivations of them, and metadata. */
    private val ALLOWED = setOf("hvc1", "grid", "iden", "iovl", "Exif", "mime", "uri ")
    private const val HEVC = "hvc1"

    /** Whether [file] (a HEIF's bytes) lists HEVC images and nothing coded in another codec. */
    fun isHevcOnly(file: ByteArray): Boolean {
        val types = itemTypes(file) ?: return false
        return HEVC in types && types.all { it in ALLOWED }
    }

    /** The item types of the file's `meta`/`iinf` box, or null when the boxes cannot be read. */
    @Suppress("ReturnCount") // Each box that is not there ends the reading; nesting them would say less.
    fun itemTypes(file: ByteArray): List<String>? {
        val meta = boxes(file, 0, file.size).firstOrNull { it.type == "meta" } ?: return null
        // `meta` is a full box: version and flags before its children.
        val iinf = boxes(file, meta.content + FULL_BOX, meta.end).firstOrNull { it.type == "iinf" } ?: return null
        val version = file[iinf.content].toInt()
        val entries = iinf.content + FULL_BOX + if (version == 0) U16 else U32
        return boxes(file, entries, iinf.end).filter { it.type == "infe" }.mapNotNull { itemType(file, it) }
    }

    /** An `infe` of version 2 or 3: id, protection index, then the four-character item type. */
    private fun itemType(
        file: ByteArray,
        infe: Box,
    ): String? {
        val version = file[infe.content].toInt()
        if (version < 2) return null
        val at = infe.content + FULL_BOX + (if (version == 2) U16 else U32) + U16
        return if (at + U32 <= infe.end) String(file, at, U32, Charsets.US_ASCII) else null
    }

    private class Box(
        val type: String,
        val content: Int,
        val end: Int,
    )

    /** The boxes from [start] to [end]; stops at the first that does not fit. */
    @Suppress("ReturnCount") // A box that does not fit ends the list, from either of two places in the header.
    private fun boxes(
        file: ByteArray,
        start: Int,
        end: Int,
    ): List<Box> {
        val found = mutableListOf<Box>()
        var at = start
        while (at + HEADER <= end) {
            val size = u32(file, at)
            val type = String(file, at + U32, U32, Charsets.US_ASCII)
            val from = at.toLong()
            val (content, boxEnd) =
                when {
                    size == 0L -> (from + HEADER) to end.toLong()
                    size == 1L && at + HEADER + U64 <= end -> (from + HEADER + U64) to (from + u64(file, at + HEADER))
                    size == 1L -> return found
                    else -> (from + HEADER) to (from + size)
                }
            if (boxEnd > end || boxEnd < content) return found
            found += Box(type, content.toInt(), boxEnd.toInt())
            at = boxEnd.toInt()
        }
        return found
    }

    private fun u32(
        file: ByteArray,
        at: Int,
    ): Long = (0 until U32).fold(0L) { value, i -> (value shl BITS) or (file[at + i].toLong() and BYTE) }

    private fun u64(
        file: ByteArray,
        at: Int,
    ): Long = (0 until U64).fold(0L) { value, i -> (value shl BITS) or (file[at + i].toLong() and BYTE) }

    private const val U16 = 2
    private const val U32 = 4
    private const val U64 = 8
    private const val HEADER = 8
    private const val FULL_BOX = 4
    private const val BITS = 8
    private const val BYTE = 0xFFL
}
