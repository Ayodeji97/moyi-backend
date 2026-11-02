package com.moyi.gratitude.infra.database

/**
 * The row conditions every gated entry read shares, each asked of an `entries` row aliased `e`.
 * One copy of each: the archive, the search and the embedding sweep read them from here, so
 * what "seen" and "live" mean cannot come to differ between them.
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
}
