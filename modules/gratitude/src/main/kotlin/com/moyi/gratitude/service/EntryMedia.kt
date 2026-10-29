package com.moyi.gratitude.service

import com.moyi.gratitude.domain.DisclosedEntry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.media.api.ImageView
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.VoiceView
import com.moyi.media.api.VoiceViewStatus
import com.moyi.media.api.VoiceViews
import org.springframework.stereotype.Service
import java.net.URI
import java.util.UUID

/** Whether a photo on an entry is shown to its reader yet (spec §4 D2). */
internal enum class EntryImageStatus {
    /** Shown: [EntryImage.url] reaches it. */
    READY,

    /** Not shown to this reader yet. Never says why: not ready, not moderated, or kept from them. */
    PROCESSING,
}

/**
 * A photo on an entry as one reader is shown it: `{id, status, url?, width?,
 * height?}` (spec §4 D2, doc 06:138). [url] and the size are present exactly
 * when [status] is [EntryImageStatus.READY]. The URL is a bearer credential for
 * the photo, so [toString] leaves it out.
 */
internal data class EntryImage(
    val id: UUID,
    val status: EntryImageStatus,
    val url: URI?,
    val width: Int?,
    val height: Int?,
) {
    override fun toString(): String = "EntryImage(id=$id, status=$status, url=${url?.let { "<redacted>" }}, width=$width, height=$height)"
}

/** Whether a voice note on an entry is playable yet (spec §4 D3). */
internal enum class EntryVoiceStatus {
    /** Playable: [EntryVoice.url] reaches it. */
    READY,

    /** Not playable yet. Never says why: not transcoded, or failed. */
    PROCESSING,
}

/**
 * A voice note on an entry as one reader is played it: `{id, status, url?,
 * durationMs?}` (spec §4 D3). [url] and [durationMs] are present exactly when
 * [status] is [EntryVoiceStatus.READY]. The URL is a bearer credential for the
 * voice note, so [toString] leaves it out.
 */
internal data class EntryVoice(
    val id: UUID,
    val status: EntryVoiceStatus,
    val url: URI?,
    val durationMs: Int?,
) {
    override fun toString(): String = "EntryVoice(id=$id, status=$status, url=${url?.let { "<redacted>" }}, durationMs=$durationMs)"
}

/** An entry's photo and voice note as one reader is shown them; either may be absent. */
internal data class ShownMedia(
    val image: EntryImage?,
    val voice: EntryVoice?,
)

/**
 * The photos and voice notes of entries a reader may read, as that reader is
 * shown them. Named `EntryImages` in D2; D3 gave it the voice note.
 *
 * **Asked only of what BR-1 answered in full.** A locked entry is never given a
 * photo or a voice note, nor a flag that it has one (BR-8), and an erased or
 * withdrawn one shows neither: [DisclosedEntry] carries their ids only on a
 * reading in full, so nothing else can be asked about. Which of each the
 * reader then gets is `media`'s rule: a photo to its owner, the entry's
 * author, once it is ready, and to the partner only once it has also passed
 * moderation (FR-101), which until D4 is never; a voice note to both once it
 * is ready, since voice is not moderated (ADR-0049).
 */
@Service
internal class EntryMedia(
    private val images: ImageViews,
    private val voices: VoiceViews,
) {
    /**
     * The media of each of [readings] read in full that has any, keyed by
     * entry, as [viewer] (the reader's user id) is shown it.
     */
    fun of(
        viewer: UUID,
        readings: Collection<EntryReading>,
    ): Map<EntryId, ShownMedia> {
        // A reading discloses media only when it is read in full (DisclosedEntry): nothing else can be asked about.
        val disclosed = readings.mapNotNull { it.disclosed }.filter { it.imageMediaId != null || it.voiceMediaId != null }
        if (disclosed.isEmpty()) return emptyMap()
        val shownImages = images.shownTo(viewer, disclosed.mapNotNull { it.imageMediaId })
        val heardVoices = voices.heardBy(viewer, disclosed.mapNotNull { it.voiceMediaId })
        return disclosed
            .mapNotNull { entry ->
                val image = entry.imageMediaId?.let { shownImages[it] }?.let(::image)
                val voice = entry.voiceMediaId?.let { heardVoices[it] }?.let(::voice)
                if (image == null && voice == null) null else entry.id to ShownMedia(image, voice)
            }.toMap()
    }

    /** [reading]'s media as [viewer] is shown it, if it has any they may see. */
    fun of(
        viewer: UUID,
        reading: EntryReading,
    ): ShownMedia? = reading.disclosed?.id?.let { of(viewer, listOf(reading))[it] }

    private fun image(view: ImageView) =
        EntryImage(
            id = view.id,
            status = if (view.status == ImageViewStatus.READY) EntryImageStatus.READY else EntryImageStatus.PROCESSING,
            url = view.url,
            width = view.width,
            height = view.height,
        )

    private fun voice(view: VoiceView) =
        EntryVoice(
            id = view.id,
            status = if (view.status == VoiceViewStatus.READY) EntryVoiceStatus.READY else EntryVoiceStatus.PROCESSING,
            url = view.url,
            durationMs = view.durationMs,
        )
}
