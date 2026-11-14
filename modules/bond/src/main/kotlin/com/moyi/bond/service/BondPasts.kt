package com.moyi.bond.service

import com.moyi.bond.api.BondPast
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.StrictModeHistory
import com.moyi.bond.infra.database.StrictModeChanges
import com.moyi.bond.infra.database.WritePauses
import com.moyi.identity.api.AccountStanding
import org.springframework.stereotype.Component

/**
 * What the close job may ask about a bond's past ([BondPast]), read from the
 * two records `bond` keeps of it: every change of Strict mode, and every
 * stretch in which the bond refused writes and then took them again; and, from identity,
 * every span any of its members was suspended (A3).
 */
@Component
internal class BondPasts(
    private val strictModeChanges: StrictModeChanges,
    private val writePauses: WritePauses,
    private val standing: AccountStanding,
) {
    fun of(bond: Bond): BondPast {
        val pauses = writePauses.of(bond.id)
        // Every member row, left or not: a member who left still did not write while suspended,
        // and a day they were suspended on is judged by the same bond. Read with no lock.
        val spans = standing.suspensionsOf(bond.members.map { it.userId.value }.distinct())
        return BondPast(
            strictModeBefore = StrictModeHistory(bond.strictMode, strictModeChanges.of(bond.id))::before,
            pausedAt = { instant -> pauses.any { it.covers(instant) } },
            suspendedDuring = { startsAt, endsAt -> spans.any { it.from < endsAt && (it.to == null || startsAt < it.to) } },
            suspendedAt = { instant -> spans.any { it.from <= instant && (it.to == null || instant < it.to) } },
        )
    }
}
