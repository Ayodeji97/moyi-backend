package com.moyi.media.infra

import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredObject
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.PresignedUpload
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory [ObjectStore] that records every key it is asked to [open]: for a test that
 * must show a refusal never reached the storage. Presigning is not this fake's business.
 */
internal class RecordingObjectStore : ObjectStore {
    private val objects = ConcurrentHashMap<String, ByteArray>()

    /** Every key [open] was asked for, in order, found or not. */
    val opened: MutableList<String> = CopyOnWriteArrayList()

    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        objects[key] = bytes
    }

    fun reset() {
        objects.clear()
        opened.clear()
    }

    override fun presignUpload(
        key: StorageKey,
        contentType: String,
        sizeBytes: Long,
        checksumSha256: String,
        expiresIn: Duration,
    ): PresignedUpload = throw UnsupportedOperationException("not this fake's business")

    override fun presignDownload(
        key: StorageKey,
        signedAt: Instant,
        expiresIn: Duration,
    ): URI = throw UnsupportedOperationException("not this fake's business")

    override fun head(key: StorageKey): StoredObject? = objects[key.value]?.let { StoredObject(it.size.toLong(), null) }

    override fun open(key: StorageKey): InputStream? {
        opened += key.value
        return objects[key.value]?.inputStream()
    }

    override fun put(
        key: StorageKey,
        file: Path,
        contentType: String,
    ) {
        objects[key.value] = Files.readAllBytes(file)
    }

    override fun delete(key: StorageKey) {
        objects.remove(key.value)
    }
}
