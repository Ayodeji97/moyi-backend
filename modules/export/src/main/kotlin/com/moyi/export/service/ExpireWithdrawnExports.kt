package com.moyi.export.service

import com.moyi.bond.api.BondExports
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.export.infra.database.DataExports
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * A withdrawal makes every export of the bond's members stale (FR-029a, BR-10a, T-20, ADR-0059
 * decision 12). The words withdrawn on a leave or a block are gone for both people from the commit
 * that withdrew them; an archive built before then holds them, and so may one being built while it
 * committed. So, for every account in the bond (the withdrawer's and the partner's):
 *
 * - each READY export is EXPIRED and **its archive deleted**, so no URL to the words is honoured;
 * - each PREPARING one loses its claim and its attempts, so it is built again from what may be read
 *   now, and a build in flight can no longer make it READY (`DataExports.ready` asks for its claim);
 *   that build deletes what it stored.
 *
 * The person asks again for a READY one that went; the app shows EXPIRED. Delivered at least once,
 * and idempotent: a second delivery finds nothing READY, and restarts what is PREPARING once more.
 * The keys are owed in the delivery's transaction and the objects deleted once it commits
 * ([ArchiveDisposal], ADR-0059 decision 13): a delivery that rolls back deletes nothing, and an
 * object the storage refuses stays owed and is deleted by the next build run.
 */
@Component
internal class ExpireWithdrawnExports(
    private val bonds: BondExports,
    private val exports: DataExports,
    private val archives: ArchiveDisposal,
    private val clock: Clock,
) : EventConsumer {
    override val id = "export.withdrawal"
    override val eventTypes = setOf(BondExports.ENTRIES_WITHDRAWN)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val bondId = checkNotNull(event.references[BondExports.BOND_ID]) { "An ${BondExports.ENTRIES_WITHDRAWN} event carries no bond." }
        val accounts = bonds.accountsInBond(bondId)
        archives.deleteAfterCommit(exports.invalidateFor(accounts, clock.instant().truncatedTo(ChronoUnit.MICROS)))
    }
}
