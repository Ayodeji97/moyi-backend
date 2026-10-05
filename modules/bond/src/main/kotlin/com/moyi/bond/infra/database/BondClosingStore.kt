package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The two reads the close job makes of `bond`'s tables (`BondAccess.closingViewOf`,
 * `BondAccess.bondsToSweep`), kept apart from [BondStore] on purpose: neither
 * carries a membership predicate, because the closer is the system settling
 * its own days and has no caller whose membership could be asked about. Every
 * read in [BondStore] but one is scoped to a member; a store of its own makes
 * a request-handling path that reaches for these stand out by its import.
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

    /** Bonds with a second member row, live or left, after [after] in id order. */
    fun everPairedAfter(
        after: UUID?,
        limit: Int,
    ): List<UUID> = bonds.findEverPairedAfter(after, limit)
}
