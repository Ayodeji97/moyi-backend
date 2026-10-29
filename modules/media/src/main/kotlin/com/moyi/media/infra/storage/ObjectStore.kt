package com.moyi.media.infra.storage

import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredObject
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The object storage, as this module needs it (spec §4 D1, D2): presign a PUT
 * and a GET, read an object's length, read its bytes, write a variant, delete
 * an object. Internal: nothing
 * storage-shaped leaves `media` (`media.api` exposes none of it), so the
 * vendor is replaceable inside one module.
 *
 * Implemented by [R2ObjectStore] (the S3 API; R2 in production, MinIO in the
 * tests and locally) and by [UnavailableObjectStore], the stand-in production
 * runs until the bucket exists (ADR-0047). Every failure to reach the storage is
 * an [ObjectStoreUnavailableException]; nothing vendor-typed escapes.
 *
 * D1 uses the PUT presign, the HEAD and the DELETE; D2 the GET presign
 * (rendering), the stream and the PUT of a variant (the pipeline).
 */
internal interface ObjectStore {
    /**
     * A URL that accepts exactly one body under [key] until [expiresIn] has
     * passed: of [contentType], [sizeBytes] long, whose SHA-256 is
     * [checksumSha256]. Each of the three is signed, so the storage refuses a
     * request that sends another value.
     */
    fun presignUpload(
        key: StorageKey,
        contentType: String,
        sizeBytes: Long,
        checksumSha256: String,
        expiresIn: Duration,
    ): PresignedUpload

    /**
     * A URL that reads the object under [key], signed as of [signedAt] and
     * valid for [expiresIn] from then. The same three values give the same URL,
     * which is what lets a client cache by it (doc 05:169, ADR-0048).
     */
    fun presignDownload(
        key: StorageKey,
        signedAt: Instant,
        expiresIn: Duration,
    ): URI

    /** What is stored under [key], or null when nothing is. */
    fun head(key: StorageKey): StoredObject?

    /** The bytes under [key]; the caller closes the stream. Null when nothing is stored there. */
    fun open(key: StorageKey): InputStream?

    /** Stores the file at [file] under [key] as [contentType], replacing whatever was there. */
    fun put(
        key: StorageKey,
        file: Path,
        contentType: String,
    )

    /** Removes the object under [key]. Removing what is not there is not an error. */
    fun delete(key: StorageKey)
}

/**
 * A signed upload: the URL, and every header the client must send with
 * exactly these values, because the signature covers them. The URL is a
 * bearer credential for one upload and is never logged.
 */
internal class PresignedUpload(
    val url: URI,
    val requiredHeaders: Map<String, String>,
) {
    override fun toString(): String = "PresignedUpload(url=<redacted>, requiredHeaders=${requiredHeaders.keys})"
}

/** The storage could not be reached, refused our credentials, or is not configured. Never carries a key or a URL. */
internal class ObjectStoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
