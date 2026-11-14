package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The reads the system's jobs make of `bond`'s tables: the close job's
 * (`BondAccess.closingViewOf`, `BondAccess.bondsToSweep`) and the reminder
 * roster's ([findAll], admin console A3). Kept apart from [BondStore] on purpose:
 * none carries a membership predicate, because a job is the system settling its
 * own work and has no caller whose membership could be asked about. Every read
 * in [BondStore] but one is scoped to a member; a store of its own makes a
 * request-handling path that reaches for these stand out by its import.
 */
@Component
internal class BondClosingStore(
    private val bonds: BondRepository,
    private val members: BondMemberRepository,
) {
    fun find(bondId: BondId): Bond? {
        val rows = members.findAllByBondId(bondId.value)
        return bonds.findById(bondId.value)?.toDomain(rows)
    }

    /**
     * The bonds named by [bondIds], with their member rows: for the reminder roster, which holds
     * the ids from its own read and asks only these whether they are paused. Three queries however
     * many come back; an id naming no bond is skipped.
     */
    fun findAll(bondIds: Collection<UUID>): List<Bond> {
        if (bondIds.isEmpty()) return emptyList()
        val rows = members.findAllByBondIdIn(bondIds).groupBy { it.bondId }
        return bonds.findAllByIdIn(bondIds).map { it.toDomain(rows[it.getId()].orEmpty()) }
    }

    /** Bonds with a second member row, live or left, after [after] in id order. */
    fun everPairedAfter(
        after: UUID?,
        limit: Int,
    ): List<UUID> = bonds.findEverPairedAfter(after, limit)

    /** The embedding sweep's page (ADR-0054): every bond with semantic search on, solo and ended included. */
    fun withSemanticSearchAfter(
        after: UUID?,
        limit: Int,
    ): List<UUID> = bonds.findWithSemanticSearchAfter(after, limit)
}
