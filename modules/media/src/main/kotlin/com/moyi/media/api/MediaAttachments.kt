package com.moyi.media.api

import java.util.UUID

/**
 * FR-044a, for the module that owns entries (spec §4 D2): attaching an asset to
 * an entry, and letting it go. `media` holds the rule; `gratitude` asks it, in
 * the entry's own transaction, and never reads `media_assets` itself.
 *
 * Both run **in the caller's transaction** and refuse to run outside one: a
 * claim that committed apart from its entry would attach an asset to an entry
 * that was then rolled back.
 */
interface MediaAttachments {
    /**
     * Attaches [mediaId] to [entryId] if, and only if, it is [owner]'s, of
     * [kind], `READY`, and attached to no entry: one conditional update, so two
     * entries racing for one asset have one winner (spec §4 D2). `false` for
     * every other case, with no word about which: an id that names nothing, a
     * v7 id (media ids are v4, doc 06:14), another person's asset, one not yet
     * ready, one of the other kind, one attached already. Telling them apart
     * would tell a caller whether somebody else's asset exists.
     */
    fun claim(
        owner: UUID,
        mediaId: UUID,
        kind: AttachableKind,
        entryId: UUID,
    ): Boolean

    /**
     * Detaches [mediaId] from [entryId], if it is [owner]'s and attached there;
     * otherwise nothing. The asset is then the owner's alone again, as it was
     * before the claim, and the reaper deletes it a day from now unless it is
     * attached again (D5). Not an erasure: that is [MediaErasure.releaseAllOf].
     */
    fun release(
        owner: UUID,
        mediaId: UUID,
        entryId: UUID,
    )
}

/** What an entry may carry. D3 adds the voice note. */
enum class AttachableKind {
    IMAGE,
}
