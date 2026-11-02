package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.bond.api.BondAccess
import com.moyi.gratitude.api.EmbeddingRun
import com.moyi.gratitude.api.EmbeddingSweep
import com.moyi.gratitude.infra.database.EntryEmbeddings
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The embedding sweep (ADR-0054, spec §3.3): the entries of every bond with semantic search on
 * that have words and no vector in the provider's current space, embedded a batch per bond.
 *
 * **Three steps, and only the first and last are transactions.** The read of the bond's
 * pending entries is one; the provider call is made with **no transaction open**, so a call of
 * seconds holds neither a connection nor a lock (ADR-0054 decision 1); the write is the other.
 *
 * **The write re-checks under the bond's lock.** It takes [BondAccess.lockClosingViewOf], the
 * same `bonds` row lock `UpdateBond.patch` takes before it turns the setting off, and reads the
 * setting after it. So a turn-off that committed while the provider was called is seen, and
 * one still in progress is waited for; and a turn-off that comes after waits for this write to
 * commit, and its consumer ([ForgetBondEmbeddings]) then deletes what was written. Either way no
 * vector outlives the setting. Every writer of `text_search` holds the same lock, which is what
 * makes [EntryEmbeddings.upsert]'s hash check safe against an edit (its KDoc). A vector refused
 * at the write is counted as skipped: the entry, if it still has words, is pending again.
 *
 * A provider failure ends the run, with what was written kept and the rest pending for the next.
 */
@Component
internal class EmbedPendingEntries(
    private val port: EmbeddingPort,
    private val embeddings: EntryEmbeddings,
    private val access: BondAccess,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    meters: MeterRegistry,
) : EmbeddingSweep {
    private val embedded = meters.counter(ENTRIES, "outcome", "embedded")
    private val skipped = meters.counter(ENTRIES, "outcome", "skipped")

    /** Not transactional: the provider is called with no transaction open (ADR-0054 decision 1). */
    override fun embedPending(budget: Int): EmbeddingRun {
        val space = port.space ?: return EmbeddingRun(0, 0, false)
        val run = Tally(budget)
        var after: UUID? = null
        while (run.goesOn) {
            val bonds = transactions.execute { access.bondsWithSemanticSearch(after, BOND_PAGE) }.orEmpty()
            if (bonds.isEmpty()) break
            bonds.asSequence().takeWhile { run.goesOn }.forEach { embedBatch(space, it, run) }
            after = bonds.last()
        }
        embedded.increment(run.done.toDouble())
        skipped.increment(run.skips.toDouble())
        return EmbeddingRun(run.done, run.skips, run.failed)
    }

    /** One batch of [bondId]'s pending entries: read, embedded with no transaction open, written. */
    private fun embedBatch(
        space: EmbeddingSpace,
        bondId: UUID,
        run: Tally,
    ) {
        val pending = transactions.execute { embeddings.missing(space, bondId, minOf(BATCH, run.left)) }.orEmpty()
        if (pending.isEmpty()) return
        val vectors =
            try {
                port.embed(pending.map { it.text })
            } catch (_: EmbeddingUnavailableException) {
                run.failed = true
                return
            }
        val wrote = write(space, bondId, pending, vectors)
        run.done += wrote
        run.skips += pending.size - wrote
    }

    /** How many of [vectors] were written: none if the setting is off by the time the lock is held. */
    private fun write(
        space: EmbeddingSpace,
        bondId: UUID,
        pending: List<EntryEmbeddings.Pending>,
        vectors: List<FloatArray>,
    ): Int {
        check(vectors.size == pending.size) { "the provider answered ${vectors.size} vectors for ${pending.size} texts" }
        // Truncated as every stored time here is (CLAUDE.md): Postgres would round it.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactions.execute {
            // The lock the turn-off also takes, and the setting read after it.
            val view = access.lockClosingViewOf(bondId)
            if (view?.semanticSearchEnabled != true) {
                0
            } else {
                pending.zip(vectors).count { (entry, vector) ->
                    embeddings.upsert(space, entry.entryId, bondId, vector, EntryEmbeddings.hashOf(entry.text), now)
                }
            }
        } ?: 0
    }

    /** What one run has done so far, against its budget. */
    private class Tally(
        val budget: Int,
    ) {
        var done = 0
        var skips = 0
        var failed = false
        val left get() = budget - done - skips
        val goesOn get() = left > 0 && !failed
    }

    private companion object {
        const val ENTRIES = "gratitude.ai.embedding.entries"

        /** Bonds read per page of the walk. */
        const val BOND_PAGE = 100

        /** Texts sent to the provider in one call, at most. */
        const val BATCH = 64
    }
}
