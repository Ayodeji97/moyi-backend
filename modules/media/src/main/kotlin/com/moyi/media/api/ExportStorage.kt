package com.moyi.media.api

import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * What the data export (FR-009, FR-094, ADR-0059) needs of the media storage: the copy of a photo
 * or voice note a person may have in their archive, and a place to keep the archive itself for its
 * 24 hours. `media` owns the storage; `export` never sees a key but the archive's own.
 */
interface ExportStorage {
    /**
     * The copy of [mediaId] the archive carries for [viewer], **by the same rule the app shows and
     * plays it by** (`MediaAsset.shownTo` and `heardBy`): the owner's own once ready, held back or
     * not unless blocked (ADR-0051); anyone else's only once ready and passed moderation. `null` for
     * anything [viewer] would not be shown, and for one deleted.
     *
     * "Original" (FR-094) is the best copy kept: no photo's uploaded bytes are ever kept (ADR-0048
     * decision 3, so no EXIF and no GPS position), so a photo is its full variant while it has one
     * and its display variant after (tier 2); a voice note is its Opus (ADR-0049).
     */
    fun copyOf(
        viewer: UUID,
        mediaId: UUID,
    ): ExportedMediaFile?

    /** Stores the archive at [file] under a new random key, which it returns. */
    fun storeArchive(file: Path): String

    /**
     * A URL that downloads the archive at [key], signed at [signedAt] for [expiresIn]: the same
     * URL every time it is asked with the same instant.
     *
     * @throws IllegalArgumentException [key] is not an archive's key: this signs nothing else.
     */
    fun archiveUrl(
        key: String,
        signedAt: Instant,
        expiresIn: Duration,
    ): URI

    /**
     * Deletes the archive at [key]; deleting one already gone is not an error.
     *
     * @throws IllegalArgumentException [key] is not an archive's key: this deletes nothing else.
     */
    fun deleteArchive(key: String)
}

/** A photo or voice note to copy into an archive. [open] reads it from the storage; `null` if it is no longer there. */
class ExportedMediaFile(
    val mediaId: UUID,
    /** `IMAGE` or `VOICE`. */
    val kind: String,
    /** `webp` or `ogg`. */
    val extension: String,
    val contentType: String,
    private val opener: () -> InputStream?,
) {
    fun open(): InputStream? = opener()

    override fun toString(): String = "ExportedMediaFile(mediaId=$mediaId, kind=$kind)"
}
