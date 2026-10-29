package com.moyi.media.service

import com.moyi.media.api.ImageView
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.domain.MediaAsset
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * [ImageViews], and what the owner's own `GET /media/{id}` shows of a photo:
 * the rule is [MediaAsset.shownTo]'s, the URL is signed by [HourlyUrls], one
 * URL an hour (doc 05:169, spec §4 D2).
 */
@Service
internal class ShowImages(
    private val assets: MediaAssetStore,
    private val urls: HourlyUrls,
) : ImageViews {
    override fun shownTo(
        viewer: UUID,
        imageIds: Collection<UUID>,
    ): Map<UUID, ImageView> {
        if (imageIds.isEmpty()) return emptyMap()
        val signedAt = urls.signingInstant()
        return assets
            .findAll(imageIds.toSet())
            .filterNot { it.isGone }
            .associate { it.id to view(it, owner = it.ownerUserId == viewer, signedAt) }
    }

    /** The owner's own view of their asset, for `GET /media/{id}`. */
    fun toOwner(asset: MediaAsset): ImageView = view(asset, owner = true, urls.signingInstant())

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
                // No storage configured: there is nothing a URL could reach. Shown as still
                // processing rather than failing the whole response it is part of.
                val url = urls.sign(shown.variant.key, signedAt) ?: return processing(asset)
                ImageView(asset.id, ImageViewStatus.READY, url, shown.variant.width, shown.variant.height)
            }
        }

    private fun processing(asset: MediaAsset) = ImageView(asset.id, ImageViewStatus.PROCESSING, null, null, null)
}
