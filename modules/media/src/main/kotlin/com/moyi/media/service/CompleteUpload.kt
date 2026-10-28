package com.moyi.media.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.StoredObject
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
 * In order (ADR-0047 decision 2):
 *
 * 1. **The asset is read and authorised, with no transaction open.** Another
 *    person's, a deleted one or none is the one `404`, before the storage is
 *    asked anything.
 * 2. **The storage is asked, with no transaction open**: a HEAD, and only for
 *    a pending upload, bounded by the storage client's 3 s per call (at most
 *    two attempts of 1.5 s). A storage that does not answer costs this
 *    request those seconds and holds no pooled connection, no key and no row
 *    lock while it does; the answer is `503`, and nothing was recorded.
 * 3. **One transaction holds the key, the row and the event**
 *    (`SubmitEntry`'s shape): [IdempotentExecution.once] tries the key's
 *    advisory lock, then the asset's row is locked and **its status read
 *    again**. Only an asset still pending moves, on what step 2 saw; one that
 *    another completion moved meanwhile is already complete, and one deleted
 *    meanwhile is the `404`. The re-check under the lock is what makes asking
 *    the storage outside it safe. A refusal rolls the key back with
 *    everything else, so a client that completes too early may complete
 *    again under the same key once the PUT has finished. A row lock not had
 *    within `lock_timeout` is `409 MEDIA_BUSY` ([busyIfLockRefused]).
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
        val seen = readOwn(mediaId, owner)
        val headed = if (seen.status == MediaStatus.PENDING_UPLOAD) Headed(headOf(seen)) else null
        val completion =
            busyIfLockRefused {
                transactions.execute {
                    val outcome = execution.once(idempotency) { decide(lockedOwn(mediaId, owner), headed) }
                    Completion(readOwn(outcome.resultId, owner), outcome.wasReplayed)
                }
            }
        return checkNotNull(completion) { "complete's transaction produces a Completion unless it threw" }
    }

    /** Under the row lock: the status as it is now, judged on what the storage said before the lock. */
    private fun decide(
        asset: MediaAsset,
        headed: Headed?,
    ): IdempotentResult<UUID> {
        val decided = asset.completion { checkNotNull(headed) { "an asset seen past PENDING_UPLOAD is pending again" }.stored }
        when (decided) {
            MediaAsset.Completion.Uploaded -> uploaded(asset)
            MediaAsset.Completion.NotUploaded -> throw MediaNotUploadedException()
            MediaAsset.Completion.AlreadyComplete -> Unit
        }
        return IdempotentResult(asset.id, asset.id, ResultKind.MEDIA, OK)
    }

    /** What the storage answered, asked once before the transaction; a null [stored] is "nothing there". */
    private class Headed(
        val stored: StoredObject?,
    )

    /** The owner's live asset under its row lock; anybody else's, a deleted one or none is the one 404. */
    private fun lockedOwn(
        mediaId: UUID,
        owner: UUID,
    ): MediaAsset = assets.lock(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException()

    /** The owner's live asset as it stands: before the storage is asked, and again for the answer (a replay is a read). */
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
