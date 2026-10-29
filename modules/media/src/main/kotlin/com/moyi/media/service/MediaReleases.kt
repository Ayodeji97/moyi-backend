package com.moyi.media.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The two events that hand an asset's objects to `media.purge` (D5, ADR-0050),
 * published in the transaction that made them owed, never after it:
 *
 * - **`MediaReleased`**: the asset is `DELETED` and every object it may have
 *   is to go. Every path that deletes an asset publishes it: an erasure
 *   ([ReleaseErasedMedia]), the reaper ([RunMediaLifecycle]), the owner's own
 *   `DELETE` ([OwnMedia]), and `media.process` finding the photo it just made
 *   deleted under it ([ProcessUploads]).
 * - **`MediaDemoted`**: the photo is tier 2 and its full variant is to go.
 *
 * Each carries the asset's id and nothing else (ADR-0035: payloads name, never
 * hold); the keys are derived again from the row, which outlives the objects.
 */
@Component
internal class MediaReleases(
    private val events: EventPublisher,
) {
    fun released(
        mediaId: UUID,
        at: Instant,
    ) = publish(mediaId, MEDIA_RELEASED, at)

    fun demoted(
        mediaId: UUID,
        at: Instant,
    ) = publish(mediaId, MEDIA_DEMOTED, at)

    private fun publish(
        mediaId: UUID,
        type: String,
        at: Instant,
    ) {
        events.publish(OutboxEvent(CompleteUpload.AGGREGATE, mediaId, type, mapOf(CompleteUpload.MEDIA_ID to mediaId), at))
    }

    internal companion object {
        const val MEDIA_RELEASED = "MediaReleased"
        const val MEDIA_DEMOTED = "MediaDemoted"
    }
}
