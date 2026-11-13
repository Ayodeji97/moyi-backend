package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.UserId
import com.moyi.identity.api.AccountStanding
import org.springframework.stereotype.Component

/**
 * Where `bond` asks identity who is suspended, so that every read of a bond's pause (the
 * membership, the view, an invite, the admin lookup) asks the same question of the same rule,
 * [Bond.isPausedFor]. One `suspendedAmong` for however many bonds, and none for bonds that
 * have ended, which are not paused whatever their members' standing. No lock, in any caller:
 * identity's port takes none (A3 spec §8).
 */
@Component
internal class BondPause(
    private val standing: AccountStanding,
) {
    fun isPaused(bond: Bond): Boolean = pausedAmong(listOf(bond)).getValue(bond.id.value)

    /** Each of [bonds], by its id, with whether it is paused. */
    fun pausedAmong(bonds: List<Bond>): Map<java.util.UUID, Boolean> {
        val open = bonds.filter(Bond::isOpen)
        val suspended =
            standing
                .suspendedAmong(open.flatMap { bond -> bond.activeMembers.map { it.userId.value } }.distinct())
                .map(::UserId)
                .toSet()
        return bonds.associate { it.id.value to it.isPausedFor(suspended) }
    }
}
