package com.moyi.media.domain

import java.time.Instant
import java.util.UUID

/**
 * Doc 04:175-182's lifecycle. D1 moves an asset through the first two steps and
 * to [DELETED]; D2 moves a photo on through [PROCESSING] to [READY] or
 * [FAILED], and D3 a voice note; D4 and D5 add the rest.
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

        /**
         * Where the Opus made of the voice note uploaded under [upload] is
         * kept: `v/{uuid}/voice.ogg`, derived as a photo's variants are
         * ([forVariant]) and for the same reasons (ADR-0049).
         */
        fun forVoice(upload: StorageKey): StorageKey {
            check(upload.value.startsWith(UPLOAD_PREFIX)) { "a voice note is made from an upload's key" }
            return StorageKey("v/${upload.value.removePrefix(UPLOAD_PREFIX)}/voice.ogg")
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
    /** Moderation's verdict (D4, ADR-0051); none until it is given. */
    val moderationVerdict: ModerationVerdict? = null,
    /** How many attempts of `media.process` have begun on it, counted whatever became of them. */
    val processingAttempts: Int = 0,
    /** A ready voice note's length in milliseconds (D3); on the asset only, never on the entry (spec D-10). */
    val durationMs: Int? = null,
    /** 1 while a photo keeps its full-resolution variant, 2 once only the display variant is kept (D-16, D5). */
    val tier: Int = 1,
    /** Moderation found adult nudity (Q16): passed, and blurred until tapped. Kept whatever the verdict. */
    val sensitive: Boolean = false,
    /**
     * Child sexual abuse material kept as evidence (Q16, ADR-0051): whoever
     * deletes the asset, its objects stay until the operator has reported it
     * and deletes it. No best-effort delete and no purge touches them.
     */
    val evidenceHeld: Boolean = false,
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
     * The one key a photo's demotion gives up (D-16, D5): its full variant's,
     * derived as [objectKeys] are, so `media.purge` finds it whether or not
     * the row still records it. A released asset gives up all of [objectKeys].
     */
    val demotedKey: StorageKey
        get() {
            check(kind == MediaKind.IMAGE) { "only a photo is demoted" }
            return StorageKey.forVariant(storageKey, ImageVariant.FULL)
        }

    /**
     * Every key the storage may hold for this asset: its upload and, for a
     * photo, each variant's key, for a voice note its Opus, **whether or not
     * the row records it**. The keys are derived from the upload's
     * ([StorageKey.forVariant], [StorageKey.forVoice]), so an attempt that
     * wrote an object and then failed, before anything was recorded, leaves
     * nothing a deletion cannot find (review of #108).
     */
    val objectKeys: List<StorageKey>
        get() =
            listOf(storageKey) +
                when (kind) {
                    MediaKind.IMAGE -> ImageVariant.entries.map { StorageKey.forVariant(storageKey, it) }
                    MediaKind.VOICE -> listOf(StorageKey.forVoice(storageKey))
                }

    /**
     * What `media.process` does with this asset when a `MediaUploaded` names it
     * (spec §4 D2, D3). Asked of the row as it is now, never of the event,
     * which may be older: ADR-0047 owed it that **a deleted asset is skipped**,
     * since an upload can be deleted after its event was published.
     *
     * - A photo or a voice note that is [MediaStatus.UPLOADED], or
     *   [MediaStatus.PROCESSING] after an attempt that did not finish, is
     *   processed, **unless [MAX_ATTEMPTS] decodes have already begun on
     *   it**: then it is given up ([Processing.GIVE_UP], `FAILED`), so that
     *   an upload whose decode never finishes (it kills the process, or a bug
     *   after it throws) is not retried for ever (review of #108). Only a
     *   decode counts, and a voice note's transcode is its decode: an outage
     *   before it is never the upload's fault, and the upload waits it out
     *   (the owner's ruling).
     * - Anything else has nothing left to do: deleted, ready, failed, or never
     *   completed.
     */
    val processing: Processing
        get() =
            when {
                status != MediaStatus.UPLOADED && status != MediaStatus.PROCESSING -> Processing.NOTHING_TO_DO
                processingAttempts >= MAX_ATTEMPTS -> Processing.GIVE_UP
                else -> Processing.PROCESS
            }

    /**
     * What a person is shown of this photo (spec §4 D2, D4; FR-101, Q10, Q16).
     *
     * - **Its owner** (on an entry, its author: only an owner can attach) sees
     *   it once it is [MediaStatus.READY], and a [MediaStatus.QUARANTINED] one
     *   marked "not shared" ([Shown.NotShared], Q10: no reason given), still
     *   with the image, **unless it is [ModerationVerdict.BLOCKED]**: then not
     *   even its owner is given the image (Q16, ADR-0051 decision 4).
     * - **Anyone else** sees it only once it is ready and has
     *   [ModerationVerdict.PASSED]; before that, and for ever if it is held
     *   back, it is still processing to them (Q10: never "removed").
     *
     * The image shown is the largest kept: [ImageVariant.FULL] while there is
     * one, then [ImageVariant.DISPLAY] (tier 2, D5). It is sensitive only
     * once it has passed with nudity found.
     */
    fun shownTo(owner: Boolean): Shown {
        val best =
            variants.firstOrNull { it.variant == ImageVariant.FULL }
                ?: variants.firstOrNull { it.variant == ImageVariant.DISPLAY }
        return when {
            kind != MediaKind.IMAGE || best == null -> Shown.Processing
            status == MediaStatus.READY && (owner || partnersMaySeeIt) -> Shown.Ready(best, sensitive = passedSensitive)
            status == MediaStatus.QUARANTINED && owner -> Shown.NotShared(best.takeUnless { isBlocked })
            else -> Shown.Processing
        }
    }

    /**
     * What a person is played of this voice note (spec §4 D3, D4), by the rule
     * of [shownTo]: **its owner** hears it once it is [MediaStatus.READY], and
     * is told a held-back one is "not shared" (with the note, unless it is
     * [ModerationVerdict.BLOCKED]); **anyone else** hears it only once it is
     * ready and [partnersMaySeeIt], and until then it is still processing to
     * them, with no URL and no length (ADR-0049 decision 7).
     */
    fun heardBy(owner: Boolean): Heard {
        val key = StorageKey.forVoice(storageKey)
        return when {
            kind != MediaKind.VOICE || durationMs == null -> Heard.Processing
            status == MediaStatus.READY && (owner || partnersMaySeeIt) -> Heard.Ready(key, durationMs, sensitive = passedSensitive)
            status == MediaStatus.QUARANTINED && owner -> if (isBlocked) Heard.NotShared(null, null) else Heard.NotShared(key, durationMs)
            else -> Heard.Processing
        }
    }

    /**
     * **Who besides its owner sees a photo or hears a voice note: only once it
     * has passed moderation** (FR-101; the owner's ruling on FR-061,
     * 2026-10-29: "media" includes voice notes). The one place the rule is
     * written, for both kinds (ADR-0049 decision 7, ADR-0051).
     */
    private val partnersMaySeeIt: Boolean get() = moderationVerdict == ModerationVerdict.PASSED

    /** Blurred for whoever is shown it: passed, with adult nudity found (Q16). */
    private val passedSensitive: Boolean get() = sensitive && moderationVerdict == ModerationVerdict.PASSED

    /** Child sexual abuse material (Q16): served to nobody, its owner included, and never released. */
    val isBlocked: Boolean get() = moderationVerdict == ModerationVerdict.BLOCKED

    /**
     * Whether `media.moderate` has anything to do with this asset (spec §4 D4):
     * only a [MediaStatus.READY] asset with no verdict yet. Asked of the row as
     * it is now, never of the event: one deleted, already moderated, or
     * released by the operator is left alone.
     */
    val needsModeration: Boolean get() = status == MediaStatus.READY && moderationVerdict == null

    /**
     * The one object moderation reads (spec §4 D4): a photo's display variant
     * ("never ships the original, never the full variant, fewer bytes leave
     * the box"), which a tier-2 photo still has (ADR-0050); a voice note's
     * Opus. Derived, as [objectKeys] are.
     */
    val moderatedKey: StorageKey
        get() =
            when (kind) {
                MediaKind.IMAGE -> StorageKey.forVariant(storageKey, ImageVariant.DISPLAY)
                MediaKind.VOICE -> StorageKey.forVoice(storageKey)
            }

    override fun toString(): String = "MediaAsset(id=$id, kind=$kind, status=$status, tier=$tier)"

    enum class Processing { PROCESS, GIVE_UP, NOTHING_TO_DO }

    sealed interface Heard {
        data object Processing : Heard

        data class Ready(
            val key: StorageKey,
            val durationMs: Int,
            val sensitive: Boolean = false,
        ) : Heard

        /** To its owner only: held back from the partner (Q10). Without the note itself when it is blocked. */
        data class NotShared(
            val key: StorageKey?,
            val durationMs: Int?,
        ) : Heard
    }

    sealed interface Shown {
        data object Processing : Shown

        data class Ready(
            val variant: StoredVariant,
            val sensitive: Boolean = false,
        ) : Shown

        /** To its owner only: held back from the partner (Q10). Without the image when it is blocked. */
        data class NotShared(
            val variant: StoredVariant?,
        ) : Shown
    }

    sealed interface Completion {
        data object Uploaded : Completion

        data object NotUploaded : Completion

        data object AlreadyComplete : Completion
    }

    internal companion object {
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
