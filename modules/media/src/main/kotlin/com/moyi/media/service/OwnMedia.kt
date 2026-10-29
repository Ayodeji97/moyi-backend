package com.moyi.media.service

import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.VoiceViewStatus
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * An owner's asset as `GET /media/{id}` and a completion show it: the asset, and
 * once it is a ready photo the signed URL of the largest image kept and that
 * image's size, or once it is a ready voice note the signed URL of its Opus and
 * its length. The URL is a bearer credential: [toString] leaves it out.
 */
internal class OwnAsset(
    val asset: MediaAsset,
    val url: URI?,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
) {
    override fun toString(): String =
        "OwnAsset(asset=$asset, url=${url?.let { "<redacted>" }}, width=$width, height=$height, durationMs=$durationMs)"
}

/**
 * `GET` and `DELETE /media/{id}` (doc 06:183-184): what an owner may do with
 * their own asset. Owner only: a partner reaches media through an entry
 * (BR-1), never by its id, so every other caller is answered `404`, as for an
 * id that names nothing (CLAUDE.md: never 403).
 */
@Service
internal class OwnMedia(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
    private val images: ShowImages,
    private val voices: PlayVoices,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The owner's asset, with its signed URL once it is ready; a deleted one is not found. */
    fun get(
        owner: UUID,
        mediaId: UUID,
    ): OwnAsset = viewOf(assets.find(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException())

    /**
     * [asset] as its owner is shown it, once it is ready: a photo's URL and the
     * size of the largest image kept, or a voice note's URL and its length.
     */
    fun viewOf(asset: MediaAsset): OwnAsset =
        when (asset.kind) {
            MediaKind.IMAGE -> {
                val shown = images.toOwner(asset).takeIf { it.status == ImageViewStatus.READY }
                OwnAsset(asset, shown?.url, width = shown?.width, height = shown?.height)
            }

            MediaKind.VOICE -> {
                val heard = voices.toOwner(asset).takeIf { it.status == VoiceViewStatus.READY }
                OwnAsset(asset, heard?.url, durationMs = heard?.durationMs)
            }
        }

    /**
     * Deletes the owner's unattached asset: `DELETED` under its row lock, then,
     * after the commit, its objects. Deleting it twice is `404` the second time:
     * it is gone. An asset attached to an entry is `409 MEDIA_ATTACHED`: it is
     * the entry's now, and leaves with it (D5's erasure).
     *
     * **The objects go after the commit, and on a best-effort basis**: the
     * upload under `in/` and, for one already processed, what was kept of it
     * under `v/` (a photo's variants, a voice note's Opus). If the storage cannot be reached, the bucket's lifecycle rule
     * removes the upload within two days (ADR-0047); the variants have no such
     * rule, and D5's `media.purge` is where an erasure that must not be given up
     * on lives (spec D-9). Deleting before the commit would lose the bytes of an
     * asset whose deletion then rolled back. A photo being processed while it is
     * deleted is `media.process`'s to clean up: it finds the asset deleted under
     * the row lock and removes what it made.
     */
    @Suppress("SwallowedException") // On purpose: the deletion has committed, and the lifecycle rule is the fallback (ADR-0047).
    fun delete(
        owner: UUID,
        mediaId: UUID,
    ) {
        val deleted =
            busyIfLockRefused {
                transactions.execute {
                    val asset = assets.lock(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException()
                    if (!asset.isDeletable) throw MediaAttachedException()
                    check(assets.markDeleted(asset.id, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
                        "an unattached live asset under its row lock is deleted"
                    }
                    asset
                }
            }
        val asset = checkNotNull(deleted) { "delete's transaction produces the asset unless it threw" }
        for (key in asset.objectKeys) {
            try {
                storage.delete(key)
            } catch (unavailable: ObjectStoreUnavailableException) {
                // The id, never the key: the key reaches the bytes.
                log.warn("Media {} is deleted; an object of it is left to the storage's own clean-up", asset.id)
            }
        }
    }
}
