package com.moyi.media.api

import java.net.URI
import java.util.UUID

/**
 * How a photo on an entry is shown to one person (spec §4 D2, FR-101): to its
 * owner once it is ready, to anybody else only once it is ready **and** has
 * passed moderation; to everyone else, until then, it is still processing.
 *
 * `gratitude` asks only about entries the reader may already read (BR-1 has
 * answered before this is reached); this decides only what of the photo that
 * reader gets. A locked entry is never rendered with a photo at all (BR-8), and
 * that is `gratitude`'s rule, not this one's.
 */
interface ImageViews {
    /**
     * Each of [imageIds] as [viewer] (a user id) is shown it, keyed by id. An id
     * that names no asset, or a deleted one, has no entry in the answer.
     */
    fun shownTo(
        viewer: UUID,
        imageIds: Collection<UUID>,
    ): Map<UUID, ImageView>
}

/**
 * A photo as one person is shown it: `{id, status, url?, width?, height?}`.
 * [url] and the size are present exactly when [status] is [ImageViewStatus.READY].
 *
 * [url] is a signed GET that works for at least 24 hours and ends on the hour
 * (doc 05:169), and is the same URL for every request in the same hour, so a
 * client may cache by it. It is a bearer credential for the photo: never
 * logged, and redacted from [toString].
 */
class ImageView(
    val id: UUID,
    val status: ImageViewStatus,
    val url: URI?,
    val width: Int?,
    val height: Int?,
) {
    override fun toString(): String = "ImageView(id=$id, status=$status, url=${url?.let { "<redacted>" }}, width=$width, height=$height)"
}

enum class ImageViewStatus {
    /** Shown: [ImageView.url] reaches it. */
    READY,

    /** Not shown yet, to this viewer, for whatever reason: never which. */
    PROCESSING,
}
