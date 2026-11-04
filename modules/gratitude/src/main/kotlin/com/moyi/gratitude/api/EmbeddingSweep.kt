package com.moyi.gratitude.api

/**
 * The embedding sweep (ADR-0054, spec §3.3), asked for by `scheduling` on a timer. That module
 * knows when; this one knows which entries still have no vector, and whether their bond's
 * setting allows one.
 */
interface EmbeddingSweep {
    /**
     * Embeds up to [budget] entries of bonds with semantic search on that have words and no
     * vector in the provider's current space. Nothing is sent when no provider is configured.
     * The provider is called with no transaction open; each vector is written under the bond's
     * lock, after the setting is read again under it, and only while the entry still holds the
     * words that were embedded.
     */
    fun embedPending(budget: Int): EmbeddingRun
}

/**
 * What one sweep did: vectors written, vectors refused at the write (the setting turned off, or
 * the words changed or gone, since they were read), and whether any provider call failed. A
 * failed call skips its bond; three in a row end the run. Entries not reached, or refused, stay
 * pending for the next run. A run with [failed] and anything embedded or skipped had at least
 * one provider call succeed.
 */
data class EmbeddingRun(
    val embedded: Int,
    val skipped: Int,
    val failed: Boolean,
)
