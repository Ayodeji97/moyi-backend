package com.moyi.bond.service

import com.moyi.bond.api.BondPast
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.StrictModeHistory
import com.moyi.bond.infra.database.StrictModeChanges
import com.moyi.bond.infra.database.WritePauses
import org.springframework.stereotype.Component

/**
 * What the close job may ask about a bond's past ([BondPast]), read from the
 * two records `bond` keeps of it: every change of Strict mode, and every
 * stretch in which the bond refused writes and then took them again.
 */
@Component
internal class BondPasts(
    private val strictModeChanges: StrictModeChanges,
    private val writePauses: WritePauses,
) {
    fun of(bond: Bond): BondPast {
        val pauses = writePauses.of(bond.id)
        return BondPast(
            strictModeBefore = StrictModeHistory(bond.strictMode, strictModeChanges.of(bond.id))::before,
            pausedAt = { instant -> pauses.any { it.covers(instant) } },
        )
    }
}
