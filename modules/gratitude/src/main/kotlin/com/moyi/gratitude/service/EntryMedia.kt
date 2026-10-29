package com.moyi.gratitude.service

import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
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

/**
 * The photos of entries a reader may read, as that reader is shown them.
 *
 * **Asked only of what BR-1 answered in full.** A locked entry is never given a
 * photo, nor a flag that it has one (BR-8), and an erased or withdrawn one
 * shows no photo either: [com.moyi.gratitude.domain.DisclosedEntry] carries the
 * photo's id only on a reading in full, so nothing else can be asked about.
 * Which of the photo the reader then gets is `media`'s rule (FR-101): its
 * owner, the entry's author, once it is ready; the partner only once it has
 * also passed moderation, and until D4 brings a verdict, never.
 */
@Service
internal class EntryImages(
    private val images: ImageViews,
) {
    /**
     * The photo of each of [readings] read in full that has one, keyed by entry,
     * as [viewer] (the reader's user id) is shown it.
     */
    fun of(
        viewer: UUID,
        readings: Collection<EntryReading>,
    ): Map<EntryId, EntryImage> {
        // A reading discloses a photo only when it is read in full (DisclosedEntry): nothing else can be asked about.
        val wanted = readings.mapNotNull { reading -> reading.disclosed?.let { entry -> entry.imageMediaId?.let { entry.id to it } } }
        if (wanted.isEmpty()) return emptyMap()
        val shown = images.shownTo(viewer, wanted.map { it.second })
        return wanted
            .mapNotNull { (entry, image) ->
                shown[image]?.let { view ->
                    entry to
                        EntryImage(
                            id = view.id,
                            status = if (view.status == ImageViewStatus.READY) EntryImageStatus.READY else EntryImageStatus.PROCESSING,
                            url = view.url,
                            width = view.width,
                            height = view.height,
                        )
                }
            }.toMap()
    }

    /** [reading]'s photo as [viewer] is shown it, if it has one they may see. */
    fun of(
        viewer: UUID,
        reading: EntryReading,
    ): EntryImage? = reading.disclosed?.id?.let { of(viewer, listOf(reading))[it] }
}
