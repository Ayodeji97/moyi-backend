package com.moyi.media.infra.storage

import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredObject
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * No storage. Asked for by name only (`moyi.media.storage.provider: unavailable`),
 * never the default: production runs on it until the owner has made the
 * `moyi-media` bucket and its token (ADR-0047), so that a deploy without media
 * credentials starts and serves everything but media.
 *
 * Every call refuses, and each route that needs the storage answers
 * `503 MEDIA_STORAGE_UNAVAILABLE`. Nothing is ever created, so no asset waits on
 * a storage that will not come.
 */
internal class UnavailableObjectStore : ObjectStore {
    override fun presignUpload(
        key: StorageKey,
        contentType: String,
        sizeBytes: Long,
        checksumSha256: String,
        expiresIn: Duration,
    ): PresignedUpload = refuse()

    override fun presignDownload(
        key: StorageKey,
        signedAt: Instant,
        expiresIn: Duration,
    ): URI = refuse()

    override fun head(key: StorageKey): StoredObject? = refuse()

    override fun open(key: StorageKey): InputStream? = refuse()

    override fun put(
        key: StorageKey,
        file: Path,
        contentType: String,
    ): Unit = refuse()

    override fun delete(key: StorageKey): Unit = refuse()

    private fun refuse(): Nothing = throw ObjectStoreUnavailableException("No media storage is configured (provider UNAVAILABLE).")
}
