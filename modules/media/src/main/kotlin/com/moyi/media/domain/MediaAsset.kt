package com.moyi.media.domain

import java.time.Instant
import java.util.UUID

/**
 * Doc 04:175-182's lifecycle. D1 moves an asset through the first two steps and
 * to [DELETED]; D2 moves a photo on through [PROCESSING] to [READY] or
 * [FAILED]; D3 to D5 add the rest.
 */
internal enum class MediaStatus {
    PENDING_UPLOAD,
    UPLOADED,
    PROCESSING,
    READY,
    QUARANTINED,
    FAILED,
    DELETED,
}

/**
 * Where the bytes are: `in/{uuid}` for an upload (spec §4 D1), `v/{uuid}/…` for
 * what is kept of it (D2). Random, so the key says nothing of its owner, bond
 * or kind (doc 09:18); still never logged, because with the bucket's
 * credentials it reaches the bytes.
 */
@JvmInline
internal value class StorageKey(
    val value: String,
) {
    override fun toString(): String = "StorageKey(<redacted>)"

    companion object {
        private const val UPLOAD_PREFIX = "in/"

        fun forUpload(random: UUID): StorageKey = StorageKey("$UPLOAD_PREFIX$random")

        /**
         * Where [variant] of the photo uploaded under [upload] is kept:
         * `v/{uuid}/{label}.webp`, with the upload key's own random part (spec
         * §4 D2, ADR-0047 decision 4). Derived, so an attempt that is retried
         * writes the same keys again rather than leaving the first attempt's
         * objects behind; and still random and never the asset's id, so the key
         * says nothing of whose it is (doc 09:18).
         */
        fun forVariant(
            upload: StorageKey,
            variant: ImageVariant,
        ): StorageKey {
            check(upload.value.startsWith(UPLOAD_PREFIX)) { "a variant is made from an upload's key" }
            return StorageKey("v/${upload.value.removePrefix(UPLOAD_PREFIX)}/${variant.label}.webp")
        }
    }
}

/** What the storage holds under a key: its length and, if the storage reports it, its SHA-256 in base64. */
internal data class StoredObject(
    val sizeBytes: Long,
    val checksumSha256: String?,
)

/**
 * One kept image of a photo ([ImageVariant]): where it is, its size in pixels,
 * and how many bytes it holds. Its key redacts itself.
 */
internal data class StoredVariant(
    val variant: ImageVariant,
    val key: StorageKey,
    val width: Int,
    val height: Int,
    val bytes: Long,
)

/**
 * One photo or voice note, owned by the person who uploaded it (doc 04:117).
 * Plain, not a `data class`: an asset is an identity, not a value.
 */
@Suppress("LongParameterList")
internal class MediaAsset(
    val id: UUID,
    val ownerUserId: UUID,
    val kind: MediaKind,
    val storageKey: StorageKey,
    val contentType: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val status: MediaStatus,
    val attachedEntryId: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    /** The images kept of a photo once it is [MediaStatus.READY] (D2); none before. */
    val variants: List<StoredVariant> = emptyList(),
    /** D4's verdict on a photo, [PASSED] or `QUARANTINED`; none until D4 exists. */
    val moderationVerdict: String? = null,
    /** How many attempts of `media.process` have begun on it, counted whatever became of them. */
    val processingAttempts: Int = 0,
) {
    /** A deleted asset is gone to its owner too: every route answers it as one that never existed. */
    val isGone: Boolean get() = status == MediaStatus.DELETED

    /**
     * What `POST /media/{id}/complete` does, given what the storage holds under
     * this asset's key (null: nothing). [stored] is asked only of a pending
     * upload: an asset already past it needs no trip to the storage. Only a
     * pending upload moves, and only
     * to [MediaStatus.UPLOADED], and only when the object is the one declared:
     * the same length, and the same SHA-256 when the storage reports one. The
     * upload URL was signed for both, so a mismatch means the bytes did not
     * arrive by that URL; it is refused, and nothing changes.
     *
     * Any later status is already complete, and completing it again changes
     * nothing (doc 06:182: "Idempotent").
     */
    fun completion(stored: () -> StoredObject?): Completion {
        if (status != MediaStatus.PENDING_UPLOAD) return Completion.AlreadyComplete
        val found = stored()
        return when {
            found == null -> Completion.NotUploaded
            found.sizeBytes != sizeBytes -> Completion.NotUploaded
            found.checksumSha256 != null && found.checksumSha256 != checksumSha256 -> Completion.NotUploaded
            else -> Completion.Uploaded
        }
    }

    /**
     * Whether its owner may delete it (doc 06:184: "Owner only, and only if
     * unattached"). An attached asset is the entry's, and leaves with the
     * entry (D5's erasure); deleting it is `409 MEDIA_ATTACHED`.
     */
    val isDeletable: Boolean get() = attachedEntryId == null

    /**
     * Every key the storage may hold for this asset: its upload and, for a
     * photo, each variant's key, **whether or not the row records it**. The
     * keys are derived from the upload's ([StorageKey.forVariant]), so an
     * attempt that wrote one variant and then failed, before anything was
     * recorded, leaves nothing a deletion cannot find (review of #108).
     */
    val objectKeys: List<StorageKey>
        get() =
            listOf(storageKey) +
                if (kind == MediaKind.IMAGE) ImageVariant.entries.map { StorageKey.forVariant(storageKey, it) } else emptyList()

    /**
     * What `media.process` does with this asset when a `MediaUploaded` names it
     * (spec §4 D2). Asked of the row as it is now, never of the event, which
     * may be older: ADR-0047 owed it that **a deleted asset is skipped**, since
     * an upload can be deleted after its event was published.
     *
     * - A photo that is [MediaStatus.UPLOADED], or [MediaStatus.PROCESSING]
     *   after an attempt that did not finish, is processed, **unless
     *   [MAX_ATTEMPTS] decodes have already begun on it**: then it is given
     *   up ([Processing.GIVE_UP], `FAILED`), so that a photo whose decode
     *   never finishes (it kills the process, or a bug after it throws) is
     *   not retried for ever (review of #108). Only a decode counts: an
     *   outage before it is never the photo's fault, and the photo waits it
     *   out (the owner's ruling).
     * - A voice note is left exactly as it is: transcoding is D3's.
     * - Anything else has nothing left to do: deleted, ready, failed, or never
     *   completed.
     */
    val processing: Processing
        get() =
            when {
                kind != MediaKind.IMAGE -> Processing.NOT_THIS_KIND
                status != MediaStatus.UPLOADED && status != MediaStatus.PROCESSING -> Processing.NOTHING_TO_DO
                processingAttempts >= MAX_ATTEMPTS -> Processing.GIVE_UP
                else -> Processing.PROCESS
            }

    /**
     * What a person is shown of this photo (spec §4 D2, FR-101). **Its owner**
     * (on an entry, its author: only an owner can attach) sees it once it is
     * [MediaStatus.READY]. **Anyone else** sees it only once it is ready and
     * has passed moderation; until then it is still processing to them,
     * whatever the reason. D4 brings the verdict, so until then a partner is
     * shown "processing": safe by construction. The image shown is the largest
     * kept: [ImageVariant.FULL] while there is one, then [ImageVariant.DISPLAY]
     * (tier 2, D5).
     */
    fun shownTo(owner: Boolean): Shown {
        val best =
            variants.firstOrNull { it.variant == ImageVariant.FULL }
                ?: variants.firstOrNull { it.variant == ImageVariant.DISPLAY }
        return when {
            status != MediaStatus.READY || best == null -> Shown.Processing
            !owner && moderationVerdict != PASSED -> Shown.Processing
            else -> Shown.Ready(best)
        }
    }

    override fun toString(): String = "MediaAsset(id=$id, kind=$kind, status=$status)"

    enum class Processing { PROCESS, GIVE_UP, NOT_THIS_KIND, NOTHING_TO_DO }

    sealed interface Shown {
        data object Processing : Shown

        data class Ready(
            val variant: StoredVariant,
        ) : Shown
    }

    sealed interface Completion {
        data object Uploaded : Completion

        data object NotUploaded : Completion

        data object AlreadyComplete : Completion
    }

    internal companion object {
        /** D4's verdict that lets a photo reach someone other than its owner (FR-101). */
        const val PASSED = "PASSED"

        /**
         * Decodes `media.process` may begin on one photo
         * (`processing_attempts`, counted just before libvips runs). A storage
         * outage before the decode counts nothing, so it can last as long as
         * it lasts; five decodes that never finish are a photo that never
         * will.
         */
        const val MAX_ATTEMPTS = 5
    }
}
