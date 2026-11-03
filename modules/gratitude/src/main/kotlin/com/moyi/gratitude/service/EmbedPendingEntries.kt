package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.bond.api.BondAccess
import com.moyi.gratitude.api.EmbeddingRun
import com.moyi.gratitude.api.EmbeddingSweep
import com.moyi.gratitude.infra.database.EntryEmbeddings
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
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
 * **The read asks the setting again before it reads any words.** The page of bonds was read
 * before the walk reached this one, and a member may have turned it off since: their words must
 * not be sent. No lock is needed here; one that turns it off during the provider call is the
 * write's to catch. **Nor any word of a member who has withdrawn**: the same read names them,
 * and their entries are left out before the words are read (final review I1); one that
 * withdraws during the provider call is the write's to catch, as a turn-off is.
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
 * **A provider failure skips the bond, and three in a row end the run.** One input the provider
 * always refuses must not stop every bond after it for ever, since each run walks from the start;
 * [MAX_CONSECUTIVE_FAILURES] refusals in a row mean the provider is down, and it is not hammered.
 * What was refused stays pending for the next run; the run reports `failed`.
 *
 * **Each bond visited is also repaired** (final review I2): under the same lock, before the
 * write, its rows whose `text_hash` is no longer their entry's words, or whose entry is erased,
 * are deleted ([EntryEmbeddings.deleteStale]); an unlocked check in the read decides whether a
 * bond with nothing pending needs the lock at all. Reads never use such a row meanwhile.
 *
 * **A model with no max-distance is not embedded for** ([SemanticSearchProperties.maxDistance]):
 * search and remember ask nothing of it, so the run is the empty one a missing provider gives,
 * not a failure; the gauge is stamped as for any quiet run.
 */
@Component
@Suppress("LongParameterList") // What Spring hands the sweep.
internal class EmbedPendingEntries(
    private val port: EmbeddingPort,
    private val embeddings: EntryEmbeddings,
    private val access: BondAccess,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    meters: MeterRegistry,
    private val properties: SemanticSearchProperties,
) : EmbeddingSweep {
    private val log = LoggerFactory.getLogger(javaClass)
    private val embedded = meters.counter(ENTRIES, "outcome", "embedded")
    private val skipped = meters.counter(ENTRIES, "outcome", "skipped")

    /** Not transactional: the provider is called with no transaction open (ADR-0054 decision 1). */
    override fun embedPending(budget: Int): EmbeddingRun {
        // No provider, or a model nobody has measured: search and remember would read none of
        // its vectors, so no word is sent for them (final review M1). An empty run, not failed.
        val space = port.space?.takeIf { it in properties.maxDistance } ?: return EmbeddingRun(0, 0, false)
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
        val (pending, stale) =
            transactions.execute {
                // Turned off since the page was read: no word of this bond is read, or sent.
                // Nor any word of a member who has withdrawn, before their erasure has run.
                val view = access.closingViewOf(bondId)
                if (view?.semanticSearchEnabled == true) {
                    embeddings.missing(space, bondId, minOf(BATCH, run.left), view.withdrawnMemberIds) to embeddings.hasStale(bondId)
                } else {
                    emptyList<EntryEmbeddings.Pending>() to false
                }
            } ?: (emptyList<EntryEmbeddings.Pending>() to false)
        when {
            pending.isNotEmpty() -> embedAndWrite(space, bondId, pending, run)

            // Nothing to send, only rows to repair: the lock, and no provider call.
            stale -> write(space, bondId, emptyList(), emptyList())
        }
    }

    /** [pending] sent to the provider with no transaction open, then written; a refusal is counted and skips the bond. */
    private fun embedAndWrite(
        space: EmbeddingSpace,
        bondId: UUID,
        pending: List<EntryEmbeddings.Pending>,
        run: Tally,
    ) {
        val vectors =
            try {
                port.embed(pending.map { it.text })
            } catch (_: EmbeddingUnavailableException) {
                run.failures++
                run.consecutiveFailures++
                return
            }
        run.consecutiveFailures = 0
        val wrote = write(space, bondId, pending, vectors)
        run.done += wrote
        run.skips += pending.size - wrote
    }

    /**
     * How many of [vectors] were written: none if the setting is off by the time the lock is held,
     * and none of a member who has withdrawn by then (the lock's view holds them; I1).
     */
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
                // Rows an older or careless writer left behind their words (I2): pending again next run.
                val repaired = embeddings.deleteStale(bondId)
                if (repaired > 0) log.info("Deleted {} embeddings of bond {} that no longer matched their entry", repaired, bondId)
                // A withdrawal that committed while the provider was called: its words are not kept.
                pending.zip(vectors).filterNot { (entry, _) -> entry.authorMemberId in view.withdrawnMemberIds }.count { (entry, vector) ->
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
        var failures = 0
        var consecutiveFailures = 0
        val failed get() = failures > 0
        val left get() = budget - done - skips
        val goesOn get() = left > 0 && consecutiveFailures < MAX_CONSECUTIVE_FAILURES
    }

    private companion object {
        const val ENTRIES = "gratitude.ai.embedding.entries"

        /** Bonds read per page of the walk. */
        const val BOND_PAGE = 100

        /** Texts sent to the provider in one call, at most. */
        const val BATCH = 64

        /** Provider calls refused in a row that end the run: the provider is down, not one input. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
