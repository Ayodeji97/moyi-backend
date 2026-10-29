package com.moyi.media.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.media.domain.MediaKind
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * `media.purge` (spec D-9, §4 D5, ADR-0050): deletes from the storage what an
 * asset no longer keeps, in the outbox's delivery transaction, at least once.
 *
 * - **`MediaReleased`**: the asset is `DELETED` (erased with its entry, reaped,
 *   deleted by its owner, or deleted while it was processed), and every object
 *   it may have is deleted: the upload and, for a photo, each variant's key,
 *   derived whether or not the row records it, so a variant an interrupted
 *   attempt left behind goes too. Then `purged_at` is stamped.
 * - **`MediaDemoted`**: the photo is tier 2, and its full variant is deleted.
 *
 * **Except what is held as evidence** ([MediaAsset.evidenceHeld], D4,
 * ADR-0051): a blocked asset's objects stay, whoever deleted it, until the
 * operator has made the report and deletes it; that delete publishes
 * `MediaReleased` again, with the hold cleared.
 *
 * **It never gives up.** A storage that cannot be reached throws, the delivery
 * is rolled back and backed off (2 s doubling to 15 minutes) and offered again,
 * with no last attempt (ADR-0035): an erasure is a privacy rule, and an
 * object left in the bucket is a photo its author took back. A delivery that
 * finds the objects already gone succeeds: deleting what is not there is not an
 * error, so a purge delivered twice, or after the owner's own best-effort
 * delete, changes nothing.
 *
 * **It subscribes only where there is a storage** ([ObjectStore.configured]),
 * as `media.process` subscribes only where there is a processor. Production
 * runs with the storage `unavailable` until the bucket exists (ADR-0047): there
 * this declares no event type, claims nothing, and so neither fails nor logs on
 * every poll. The first process with a storage subscribes it, and
 * [StartFrom.BEGINNING] hands it every release published before then.
 *
 * It holds no lock on the asset while it reaches the storage (ADR-0047
 * decision 2): it reads the row, deletes, and only then writes `purged_at`.
 */
@Component
internal class PurgeMedia(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val clock: Clock,
) : EventConsumer {
    private val log = LoggerFactory.getLogger(javaClass)

    override val id: String = ID
    override val eventTypes: Set<String> =
        if (storage.configured) setOf(MediaReleases.MEDIA_RELEASED, MediaReleases.MEDIA_DEMOTED) else emptySet()
    override val startFrom: StartFrom = StartFrom.BEGINNING

    /**
     * `MANDATORY`: the stamp and the acknowledgement commit together or not
     * at all. An asset whose row is gone is nothing to do (no path deletes a
     * row today); one released and not `DELETED` cannot be produced, and is
     * thrown, retried and counted among the failing deliveries rather than
     * passed over, as the withdrawal's consumer treats an event it cannot
     * believe.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val mediaId = checkNotNull(event.references[CompleteUpload.MEDIA_ID]) { "A media event carries no mediaId." }
        val asset = assets.findAny(mediaId) ?: return
        when (event.eventType) {
            MediaReleases.MEDIA_RELEASED -> {
                check(asset.isGone) { "A MediaReleased event names an asset that is not deleted." }
                if (asset.evidenceHeld) {
                    // Q16: evidence for the report the takedown runbook makes. The operator's delete clears the hold and
                    // publishes MediaReleased again; then this purges it. Not stamped: media-check counts it as held.
                    log.info("Media {} is held as evidence; its objects stay until the operator deletes it", asset.id)
                    return
                }
                // Each delete throws ObjectStoreUnavailableException when the storage cannot be reached: the
                // delivery then fails, and is retried for ever.
                asset.objectKeys.forEach(storage::delete)
                if (assets.markPurged(asset.id, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
                    // The id, never a key: a key reaches the bytes.
                    log.info("Media {} purged", asset.id)
                }
            }

            MediaReleases.MEDIA_DEMOTED -> {
                val tierTwoPhoto = asset.kind == MediaKind.IMAGE && asset.tier == 2
                check(tierTwoPhoto) { "A MediaDemoted event names an asset that is not a tier-2 photo." }
                storage.delete(asset.demotedKey)
            }

            else -> {
                error("media.purge was handed an event type it does not declare.")
            }
        }
    }

    internal companion object {
        /** Stable for ever: delivery state is kept under it (ADR-0035). */
        const val ID = "media.purge"
    }
}
