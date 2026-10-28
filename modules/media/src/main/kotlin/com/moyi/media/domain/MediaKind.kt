package com.moyi.media.domain

/**
 * What an asset is, and what an upload of it may be: the content types each
 * kind accepts and the largest upload (FR-042, FR-043). The one place either
 * rule is written.
 *
 * - **IMAGE**: JPEG, PNG, HEIC and WebP, at most 10 MB before processing (FR-042).
 * - **VOICE**: at most 5 MB (FR-043). FR-043 names no input format; these are
 *   what the two platforms' recorders produce, the formats D3's magic-byte
 *   check reads (spec §4 D3): MPEG-4 audio (`ftypM4A`), Ogg, AMR, WebM and WAV.
 *
 * "MB" is read as a mebibyte, the larger reading, so that a phone's "10 MB"
 * photo is never refused by a unit. The retained cap after processing (500 KB
 * a variant, doc 06:186) is D2's and D3's, not this upload cap.
 *
 * A declared type is only a claim; D2 and D3 check the bytes (T-07). It is
 * still checked here, so that a request that cannot be media is refused
 * before an upload URL is spent on it.
 */
internal enum class MediaKind(
    val contentTypes: Set<String>,
    val maxUploadBytes: Long,
) {
    IMAGE(setOf("image/jpeg", "image/png", "image/heic", "image/webp"), MEBIBYTES_10),
    VOICE(setOf("audio/mp4", "audio/x-m4a", "audio/ogg", "audio/amr", "audio/webm", "audio/wav", "audio/x-wav"), MEBIBYTES_5),
    ;

    companion object {
        /** The kind named exactly, or null. Case matters: the contract's enum is upper case. */
        fun named(name: String): MediaKind? = entries.firstOrNull { it.name == name }
    }
}

private const val MEBIBYTE = 1024L * 1024L
private const val MEBIBYTES_10 = 10 * MEBIBYTE
private const val MEBIBYTES_5 = 5 * MEBIBYTE
