package com.moyi.media.domain

import java.time.Instant
import java.util.UUID

/**
 * Doc 04:175-182's lifecycle. D1 moves an asset through the first two steps and
 * to [DELETED]; D2 to D5 add the rest.
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
 * Where the bytes are: `in/{uuid}` for an upload (spec §4 D1). Random, so the
 * key says nothing of its owner, bond or kind (doc 09:18); still never logged,
 * because with the bucket's credentials it reaches the bytes.
 */
@JvmInline
internal value class StorageKey(
    val value: String,
) {
    override fun toString(): String = "StorageKey(<redacted>)"

    companion object {
        fun forUpload(random: UUID): StorageKey = StorageKey("in/$random")
    }
}

/** What the storage holds under a key: its length and, if the storage reports it, its SHA-256 in base64. */
internal data class StoredObject(
    val sizeBytes: Long,
    val checksumSha256: String?,
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
     * entry (D5's erasure). Nothing attaches before D2, so in D1 every live
     * asset may be deleted.
     */
    val isDeletable: Boolean get() = attachedEntryId == null

    override fun toString(): String = "MediaAsset(id=$id, kind=$kind, status=$status)"

    sealed interface Completion {
        data object Uploaded : Completion

        data object NotUploaded : Completion

        data object AlreadyComplete : Completion
    }
}
