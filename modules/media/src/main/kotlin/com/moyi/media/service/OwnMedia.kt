package com.moyi.media.service

import com.moyi.media.domain.MediaAsset
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `GET` and `DELETE /media/{id}` (doc 06:183-184): what an owner may do with
 * their own asset. Owner only: a partner reaches media through an entry
 * (BR-1), never by its id, so every other caller is answered `404`, as for an
 * id that names nothing (CLAUDE.md: never 403).
 */
@Service
internal class OwnMedia(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The owner's asset; a deleted one is not found. */
    fun get(
        owner: UUID,
        mediaId: UUID,
    ): MediaAsset = assets.find(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException()

    /**
     * Deletes the owner's unattached asset: `DELETED` under its row lock, then,
     * after the commit, its object. Deleting it twice is `404` the second time:
     * it is gone.
     *
     * **The object goes after the commit, and on a best-effort basis.** An
     * upload is only ever under `in/` in this slice, so if the storage cannot
     * be reached the bucket's lifecycle rule removes it within two days
     * (ADR-0047), and D5's `media.purge` is where an erasure that must not be
     * given up on lives (spec D-9). Deleting before the commit would lose the
     * bytes of an asset whose deletion then rolled back.
     *
     * Nothing attaches an asset before D2, so the attached case cannot arise
     * here; D2 gives it its refusal along with the claim that makes it real.
     */
    @Suppress("SwallowedException") // On purpose: the deletion has committed, and the lifecycle rule is the fallback (ADR-0047).
    fun delete(
        owner: UUID,
        mediaId: UUID,
    ) {
        val deleted =
            transactions.execute {
                val asset = assets.lock(mediaId, owner)?.takeUnless { it.isGone } ?: throw MediaNotFoundException()
                check(asset.isDeletable) { "an attached asset is refused by D2's claim, which does not exist yet" }
                check(assets.markDeleted(asset.id, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
                    "an unattached live asset under its row lock is deleted"
                }
                asset
            }
        val asset = checkNotNull(deleted) { "delete's transaction produces the asset unless it threw" }
        try {
            storage.delete(asset.storageKey)
        } catch (unavailable: ObjectStoreUnavailableException) {
            // The id, never the key: the key reaches the bytes.
            log.warn("Media {} is deleted; its upload is left to the bucket's lifecycle rule", asset.id)
        }
    }
}
