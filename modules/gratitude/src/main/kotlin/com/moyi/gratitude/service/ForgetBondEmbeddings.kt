package com.moyi.gratitude.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.gratitude.infra.database.EntryEmbeddings
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Deletes a bond's vectors, in every table, once a member has turned semantic search off
 * (FR-204, ADR-0054, spec §3.4). `bond` publishes `SemanticSearchDisabled` in the turn-off's
 * own transaction, and this is handed it afterwards: an event and not a call, for
 * [WithdrawEntries]'s reason (`gratitude` depends on `bond`, so a call back would be a cycle).
 *
 * From the turn-off's commit no vector of the bond is read (search and remember check the
 * setting) and none is written (the sweep re-checks it under the bond's lock, which the
 * turn-off took first; [EmbedPendingEntries]). This is what makes them gone.
 *
 * **Delivered twice, or for a bond with no vectors, or for a bond that is gone, it deletes
 * nothing and returns normally**, so the delivery is acknowledged rather than retried for ever.
 * It takes no bond lock: a delete of rows the sweep can no longer add needs none.
 */
@Component
internal class ForgetBondEmbeddings(
    private val embeddings: EntryEmbeddings,
) : EventConsumer {
    /** The key the delivery state is kept under: never to be changed. */
    override val id = "gratitude.semantic-search-off"
    override val eventTypes = setOf(EVENT)

    /** An erasure is owed even for an event published before this consumer first registered (as [WithdrawEntries]). */
    override val startFrom = StartFrom.BEGINNING

    /**
     * `MANDATORY`: the deletion and the delivery's acknowledgement commit together or not at
     * all. A missing reference is a fault in what was published: retried and counted, never
     * passed over as delivered. The message names the key, never a value.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        embeddings.deleteForBond(checkNotNull(event.references[BOND_ID]) { "A $EVENT event carries no '$BOND_ID'." })
    }

    private companion object {
        const val EVENT = "SemanticSearchDisabled"
        const val BOND_ID = "bondId"
    }
}
