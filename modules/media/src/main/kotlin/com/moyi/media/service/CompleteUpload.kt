package com.moyi.media.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.media.domain.MediaAsset
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The asset as it stands after a completion, and whether this answer is a replay. */
internal class Completion(
    val asset: MediaAsset,
    val replayed: Boolean,
)

/**
 * `POST /media/{id}/complete` (doc 06:182, spec §4 D1): the owner says the PUT
 * is done; the storage is asked what it holds, and a pending upload whose
 * object is the one declared becomes `UPLOADED` and publishes `MediaUploaded
 * {mediaId}` in the same transaction (ADR-0035). D2's pipeline consumes it.
 *
 * **One transaction holds the key, the row and the event** (`SubmitEntry`'s
 * shape, spec §5.4 of the daily loop): [IdempotentExecution.once] tries the
 * key's advisory lock first, then the asset's row lock is taken, so a
 * completion and a deletion of one asset are serialised. A refusal is thrown
 * and rolls the key back with everything else, so a client that completes too
 * early may complete again under the same key once the PUT has finished.
 *
 * **The HEAD runs inside that transaction**, holding a pooled connection and
 * the row lock for one round trip to the storage, bounded by a 2 s connect and
 * a 5 s read timeout (ADR-0047). Chosen over a HEAD before the transaction,
 * which would need the owner check twice and could race a deletion.
 *
 * **A replay is a read**: the asset is read again by its owner, and an asset
 * deleted since is the same `404` as one that never existed. A second
 * completion under a new key changes nothing and publishes nothing.
 */
@Service
internal class CompleteUpload(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val events: EventPublisher,
    private val execution: IdempotentExecution,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    fun complete(
        owner: UUID,
        mediaId: UUID,
        idempotency: IdempotentRequest,
    ): Completion {
        require(idempotency.userId == owner) { "the Idempotency-Key was prepared for a different caller than the one completing" }
        val completion =
            transactions.execute {
                val outcome =
                    execution.once(idempotency) {
                        val asset = lockedOwn(mediaId, owner)
                        when (asset.completion { headOf(asset) }) {
                            MediaAsset.Completion.Uploaded -> uploaded(asset)
                            MediaAsset.Completion.NotUploaded -> throw MediaNotUploadedException()
                            MediaAsset.Completion.AlreadyComplete -> Unit
                        }
                        IdempotentResult(asset.id, asset.id, ResultKind.MEDIA, OK)
                    }
                Completion(readOwn(outcome.resultId, owner), outcome.wasReplayed)
            }
        return checkNotNull(completion) { "complete's transaction produces a Completion unless it threw" }
    }

    /** The owner's live asset under its row lock; anybody else's, a deleted one or none is the one 404. */
    private fun lockedOwn(
        mediaId: UUID,
        owner: UUID,
    ): MediaAsset = assets.lock(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException()

    /** The asset as its owner may now read it: a replay is a read, and a deletion since is the read's 404. */
    private fun readOwn(
        mediaId: UUID,
        owner: UUID,
    ): MediaAsset = assets.find(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException()

    private fun headOf(asset: MediaAsset) =
        try {
            storage.head(asset.storageKey)
        } catch (unavailable: ObjectStoreUnavailableException) {
            throw MediaStorageUnavailableException(unavailable)
        }

    private fun uploaded(asset: MediaAsset) {
        check(assets.markUploaded(asset.id)) { "a pending upload under its row lock moves to UPLOADED" }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        events.publish(OutboxEvent(AGGREGATE, asset.id, MEDIA_UPLOADED, mapOf(MEDIA_ID to asset.id), now))
    }

    internal companion object {
        const val AGGREGATE = "Media"
        const val MEDIA_UPLOADED = "MediaUploaded"
        const val MEDIA_ID = "mediaId"
        private const val OK = 200
    }
}
