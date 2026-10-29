package com.moyi.media.api

import java.time.Instant

/**
 * The two lifecycle jobs' work (spec §4 D5, ADR-0050), for `scheduling`, which
 * knows when and holds the lock, and never what. Each call is one transaction
 * of its own, touches the database alone (the objects are `media.purge`'s,
 * after the commit), and changes nothing when it is run again at once.
 */
interface MediaLifecycle {
    /**
     * Deletes up to [limit] assets attached to nothing and past their keeping
     * as of [now]: an upload never completed, an hour after its URL stopped
     * working; a ready or failed asset after a day on no entry (doc 06:186).
     * An attached asset is never reaped.
     */
    fun reap(
        now: Instant,
        limit: Int,
    ): LifecycleRun

    /**
     * Demotes up to [limit] attached photos whose full-resolution variant is
     * more than ninety days old as of [now] (D-16): only the display variant
     * is kept from then on. Irreversible.
     */
    fun demote(
        now: Instant,
        limit: Int,
    ): LifecycleRun
}

/** What one call did: how many assets it changed, and whether it stopped at its limit with more waiting. */
data class LifecycleRun(
    val changed: Int,
    val more: Boolean,
)
