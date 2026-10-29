package com.moyi.media.service

import com.moyi.media.api.ImageView
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.domain.MediaAsset
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [ImageViews], and what the owner's own `GET /media/{id}` shows of a photo:
 * the rule is [MediaAsset.shownTo]'s, the URL is signed here.
 *
 * **One URL an hour** (doc 05:169, spec §4 D2): every URL is signed as of the
 * start of the current hour and valid for 25 hours from then, so it works for
 * at least 24 hours from any moment it is handed out, it ends on the hour, and
 * every request in the same hour gets the same URL. A client can cache the
 * bytes by URL, and the archive's `ETag`, a digest of the body, changes at most
 * once an hour on a day with a photo. Signing needs no network.
 */
@Service
internal class ShowImages(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val clock: Clock,
) : ImageViews {
    override fun shownTo(
        viewer: UUID,
        imageIds: Collection<UUID>,
    ): Map<UUID, ImageView> {
        if (imageIds.isEmpty()) return emptyMap()
        val signedAt = signingInstant()
        return assets
            .findAll(imageIds.toSet())
            .filterNot { it.isGone }
            .associate { it.id to view(it, owner = it.ownerUserId == viewer, signedAt) }
    }

    /** The owner's own view of their asset, for `GET /media/{id}`. */
    fun toOwner(asset: MediaAsset): ImageView = view(asset, owner = true, signingInstant())

    @Suppress("SwallowedException") // On purpose: no storage means no URL, which "processing" already says.
    private fun view(
        asset: MediaAsset,
        owner: Boolean,
        signedAt: Instant,
    ): ImageView =
        when (val shown = asset.shownTo(owner)) {
            MediaAsset.Shown.Processing -> {
                processing(asset)
            }

            is MediaAsset.Shown.Ready -> {
                val url =
                    try {
                        storage.presignDownload(shown.variant.key, signedAt, URL_LIFETIME)
                    } catch (unavailable: ObjectStoreUnavailableException) {
                        // No storage configured: there is nothing a URL could reach. Shown as
                        // still processing rather than failing the whole response it is part of.
                        return processing(asset)
                    }
                ImageView(asset.id, ImageViewStatus.READY, url, shown.variant.width, shown.variant.height)
            }
        }

    private fun processing(asset: MediaAsset) = ImageView(asset.id, ImageViewStatus.PROCESSING, null, null, null)

    private fun signingInstant(): Instant = clock.instant().truncatedTo(ChronoUnit.HOURS)

    internal companion object {
        /** From the start of the hour: at least 24 hours from now, ending on the hour (doc 05:169). */
        val URL_LIFETIME: Duration = Duration.ofHours(25)
    }
}
