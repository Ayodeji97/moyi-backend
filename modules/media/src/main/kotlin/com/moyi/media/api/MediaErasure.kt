package com.moyi.media.api

import java.util.UUID

/**
 * An erased entry's media leaves with it (spec D-9, §4 D5, ADR-0050): not only
 * the entry's reference to it, which is `gratitude`'s, but the asset and, after
 * the commit, its objects in the storage.
 *
 * `gratitude` calls this from the one routine every erasure goes through (an
 * author's `DELETE`, a withdrawal, and whatever erases next, account deletion
 * included), **in the erasure's own transaction**, and refuses to be called
 * outside one: a release that committed apart from its erasure could delete a
 * photo whose entry was then rolled back, or the reverse.
 */
interface MediaErasure {
    /**
     * Every asset attached to [entryId] becomes `DELETED` and is let go: it is
     * gone to its owner too (`GET /media/{id}` answers `404`), it is shown on
     * no entry, and its objects are `media.purge`'s to delete, a delivery that
     * is retried until it succeeds, for ever, because an erasure must never be
     * given up on. Returns how many it released; none the second time.
     *
     * Keyed by the entry and not by the ids the entry held: which asset is
     * attached where is `media`'s own record, so nothing attached to an erased
     * entry can be missed by a caller that forgot to name it.
     */
    fun releaseAllOf(entryId: UUID): Int
}
