package com.moyi.media.domain

import java.time.Duration

/**
 * How long each kind of asset is kept, and the one place each number is
 * written (spec §4 D5, ADR-0050). The statements that act on them are
 * `MediaAssetStore`'s; they take these, and say nothing of their own.
 */
internal object Retention {
    /**
     * An upload that was never completed is deleted this long after its URL
     * stopped working: long enough that a PUT still in flight at the deadline
     * has landed, or has failed, before its asset goes.
     */
    val PENDING_GRACE: Duration = Duration.ofHours(1)

    /**
     * A ready or failed asset on no entry is deleted after this long (doc
     * 06:186, doc 07:355), counted from the latest of its creation, its
     * readiness and the moment an entry let it go: a day to attach it, or to
     * attach it again.
     */
    val UNATTACHED: Duration = Duration.ofHours(UNATTACHED_HOURS)

    /**
     * A photo's full-resolution variant is kept this long after it was made;
     * then only the display variant is (doc 06:188, doc 07:346-347, D-16).
     * Irreversible, and disclosed by the client and the Privacy Policy.
     */
    val FULL_RESOLUTION: Duration = Duration.ofDays(FULL_RESOLUTION_DAYS)
}

private const val UNATTACHED_HOURS = 24L
private const val FULL_RESOLUTION_DAYS = 90L
