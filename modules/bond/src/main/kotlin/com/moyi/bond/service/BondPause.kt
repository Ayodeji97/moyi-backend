package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.UserId
import com.moyi.identity.api.AccountStanding
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Where `bond` asks identity who is suspended, so that every read of a bond's pause (the
 * membership, the view, an invite, the admin lookup, the reminder roster) asks the same question
 * of the same rule, [Bond.isPausedFor]. One `suspendedAmong` for however many bonds, over every
 * member of each: which of them count (open bond, member not left) is the rule's to say, not
 * this class's. No lock, in any caller: identity's port takes none (A3 spec §8).
 */
@Component
internal class BondPause(
    private val standing: AccountStanding,
) {
    fun isPaused(bond: Bond): Boolean = pausedAmong(listOf(bond)).getValue(bond.id.value)

    /** Each of [bonds], by its id, with whether it is paused. */
    fun pausedAmong(bonds: List<Bond>): Map<UUID, Boolean> {
        val suspended =
            standing
                .suspendedAmong(bonds.flatMap { bond -> bond.members.map { it.userId.value } }.distinct())
                .map(::UserId)
                .toSet()
        return bonds.associate { it.id.value to it.isPausedFor(suspended) }
    }
}
