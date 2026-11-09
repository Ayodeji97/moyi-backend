package com.moyi.media.service

import com.moyi.media.api.ExportStorage
import com.moyi.media.api.ExportedMediaFile
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.StorageKey
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [ExportStorage]. Which copy a person gets is [MediaAsset.shownTo] and [MediaAsset.heardBy], the
 * rule every route that shows media asks; this restates none of it (ADR-0059 decision 6).
 */
@Service
internal class ExportFiles(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
) : ExportStorage {
    override fun copyOf(
        viewer: UUID,
        mediaId: UUID,
    ): ExportedMediaFile? {
        val asset = assets.findAll(listOf(mediaId)).singleOrNull()?.takeUnless { it.isGone } ?: return null
        return keyFor(asset, owner = asset.ownerUserId == viewer)?.let { key ->
            when (asset.kind) {
                MediaKind.IMAGE -> ExportedMediaFile(asset.id, asset.kind.name, "webp", "image/webp") { storage.open(key) }
                MediaKind.VOICE -> ExportedMediaFile(asset.id, asset.kind.name, "ogg", "audio/ogg") { storage.open(key) }
            }
        }
    }

    /** The object [MediaAsset.shownTo] or [MediaAsset.heardBy] would give this viewer, or null. */
    private fun keyFor(
        asset: MediaAsset,
        owner: Boolean,
    ): StorageKey? =
        when (asset.kind) {
            MediaKind.IMAGE -> {
                when (val shown = asset.shownTo(owner)) {
                    is MediaAsset.Shown.Ready -> shown.variant.key
                    is MediaAsset.Shown.NotShared -> shown.variant?.key
                    MediaAsset.Shown.Processing -> null
                }
            }

            MediaKind.VOICE -> {
                when (val heard = asset.heardBy(owner)) {
                    is MediaAsset.Heard.Ready -> heard.key
                    is MediaAsset.Heard.NotShared -> heard.key
                    MediaAsset.Heard.Processing -> null
                }
            }
        }

    override fun storeArchive(file: Path): String {
        val key = StorageKey.forArchive(UUID.randomUUID())
        storage.put(key, file, ARCHIVE_CONTENT_TYPE)
        return key.value
    }

    override fun archiveUrl(
        key: String,
        signedAt: Instant,
        expiresIn: Duration,
    ): URI = storage.presignDownload(StorageKey.ofArchive(key), signedAt, expiresIn)

    override fun deleteArchive(key: String) {
        storage.delete(StorageKey.ofArchive(key))
    }

    private companion object {
        const val ARCHIVE_CONTENT_TYPE = "application/zip"
    }
}
