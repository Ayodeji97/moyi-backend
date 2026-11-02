package com.moyi.media.api

import java.util.UUID

/**
 * A member's report holds an entry's photo and voice note back from the
 * partner until a person has decided (FR-100, ADR-0053; the owner's ruling
 * Q16). `media` knows the report only by its id: what was reported, by whom
 * and why are `gratitude`'s.
 *
 * `gratitude` calls this from the report's own transaction, and it refuses to
 * be called outside one: a hold that committed apart from its report would
 * hide a photo for a report that never was, or leave a report holding nothing.
 *
 * What the decision then does to the media (given back, deleted, blocked) is
 * the operator's, in the database functions V38 adds; nothing in the
 * application takes a hold off.
 */
interface MediaReports {
    /**
     * Every photo and voice note attached to [entryId], as `media` records it,
     * is held for [reportId]: a ready one is held back from the partner from
     * this moment (the author is shown it not shared, the partner processing,
     * as anything moderation holds back: ADR-0051 decision 4), and one already
     * held back or blocked is recorded as held by this report too. A deleted
     * one is gone and is not held. Returns how many the report holds.
     *
     * Keyed by the entry, as [MediaErasure.releaseAllOf] is: which asset is
     * attached where is `media`'s own record.
     */
    fun holdFor(
        entryId: UUID,
        reportId: UUID,
    ): Int
}
