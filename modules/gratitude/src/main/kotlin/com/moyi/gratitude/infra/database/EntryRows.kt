package com.moyi.gratitude.infra.database

/**
 * The row conditions every gated entry read shares, each asked of an `entries` row aliased `e`.
 * One copy of each: the archive, the search and the embedding sweep read them from here, so
 * what "seen", "live" and "a vector of these words" mean cannot come to differ between them.
 */
internal object EntryRows {
    /**
     * BR-1's "ever readable" asked of one entry row `e`: the caller wrote it, or it has been
     * revealed. Its one argument is the caller's member id. The archive lists a day by it
     * (`ArchiveDays.SEEN`); a search asks it of the very row that matched
     * (`ArchiveDays.matching`), never of the day.
     */
    const val SEEN_ROW = "(e.author_member_id = ? OR e.revealed_at IS NOT NULL)"

    /** Neither mark of an erasure, as `Entry.isErased` asks both. No argument. */
    const val LIVE_ROW = "e.deleted_at IS NULL AND e.status <> 'DELETED'"

    /**
     * SHA-256 of the row's `text_search`, as UTF-8: what a vector's `text_hash` must equal while
     * the vector still stands for its entry's words (ADR-0054 decision 3). `EntryEmbeddings.hashOf`
     * computes the same in Kotlin, for what the sweep sent. `NULL` when the entry has no words.
     */
    const val TEXT_HASH = "sha256(convert_to(e.text_search, 'UTF8'))"

    /**
     * **A vector `x` is read only while it is its entry `e`'s words** (final review I2): every
     * read of an embedding table asks it, so a vector a writer left behind (a build from before
     * V41, after a rollback, or a future writer that skips `EntryStore.update`'s delete) matches
     * nothing, until the sweep deletes it. No argument.
     */
    const val VECTOR_OF_ITS_WORDS = "x.text_hash = $TEXT_HASH"

    /** The vector `x` no longer stands for its entry `e`: erased, or its words changed. No argument. */
    const val STALE_VECTOR = "(NOT ($LIVE_ROW) OR x.text_hash IS DISTINCT FROM $TEXT_HASH)"
}
