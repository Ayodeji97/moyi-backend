package com.moyi.bond.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.BondStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The port's only implementation: the guard, plus the four bond facts, in one
 * read. `internal`, so the boundary is the interface and not this class.
 *
 * It calls [BondAccessGuard] rather than reimplementing the check, so there is
 * one definition of "is this caller a member" and one 404. The guard's own
 * store read is scoped to the caller, so this is two reads of the same row and
 * not a fetch-then-check anybody could reorder.
 */
@Service
internal class BondAccessAdapter(
    private val guard: BondAccessGuard,
    private val bonds: BondStore,
) : BondAccess {
    @Transactional(readOnly = true)
    override fun membershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership {
        val caller = UserId(userId)
        val id = BondId(bondId)
        val membership = guard.membershipOf(caller, id)
        val bond = bonds.findByMember(id, caller) ?: throw BondNotFoundException()
        return BondMembership(
            bondId = membership.bondId.value,
            memberId = membership.memberId.value,
            userId = membership.userId.value,
            anchorTimezone = bond.anchorTimezone.id,
            revealTimeLocal = bond.revealTimeLocal,
            strictMode = bond.strictMode,
            isOpen = bond.isOpen,
            hasLeft = membership.left,
            awaitingSecondMember = bond.status == BondStatus.PENDING_MEMBER,
        )
    }
}
