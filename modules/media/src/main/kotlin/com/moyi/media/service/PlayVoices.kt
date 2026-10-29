package com.moyi.media.service

import com.moyi.media.api.VoiceView
import com.moyi.media.api.VoiceViewStatus
import com.moyi.media.api.VoiceViews
import com.moyi.media.domain.MediaAsset
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * [VoiceViews], and what the owner's own `GET /media/{id}` shows of a voice
 * note: the rule is [MediaAsset.heard]'s (no moderation, spec §4 D3), the URL
 * is signed by [HourlyUrls], as a photo's is.
 */
@Service
internal class PlayVoices(
    private val assets: MediaAssetStore,
    private val urls: HourlyUrls,
) : VoiceViews {
    override fun heardBy(
        viewer: UUID,
        voiceIds: Collection<UUID>,
    ): Map<UUID, VoiceView> {
        if (voiceIds.isEmpty()) return emptyMap()
        val signedAt = urls.signingInstant()
        return assets
            .findAll(voiceIds.toSet())
            .filterNot { it.isGone }
            .associate { it.id to view(it, signedAt) }
    }

    /** The owner's own view of their voice note, for `GET /media/{id}`. */
    fun toOwner(asset: MediaAsset): VoiceView = view(asset, urls.signingInstant())

    private fun view(
        asset: MediaAsset,
        signedAt: Instant,
    ): VoiceView =
        when (val heard = asset.heard) {
            MediaAsset.Heard.Processing -> {
                processing(asset)
            }

            is MediaAsset.Heard.Ready -> {
                // No storage configured: nothing a URL could reach, so still processing (as a photo).
                val url = urls.sign(heard.key, signedAt) ?: return processing(asset)
                VoiceView(asset.id, VoiceViewStatus.READY, url, heard.durationMs)
            }
        }

    private fun processing(asset: MediaAsset) = VoiceView(asset.id, VoiceViewStatus.PROCESSING, null, null)
}
