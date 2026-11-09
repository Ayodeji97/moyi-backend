package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.UserId
import org.springframework.stereotype.Component

/**
 * An account's `bond_members` rows, by id, for account deletion (ADR-0058): `bond` ends the bonds
 * still held, and `gratitude` erases in all of them. Apart from [BondStore], which reads bonds
 * whole for a caller the guard has checked; this reads ids for work that has no caller.
 */
@Component
internal class MembershipRows(
    private val members: BondMemberRepository,
) {
    fun of(userId: UserId): List<MembershipRow> =
        members.findAllByUserId(userId.value).map { MembershipRow(BondId(it.bondId), MemberId(it.getId()), held = it.leftAt == null) }
}

/** One `bond_members` row of an account, by its ids, and whether the person is still in that bond. */
internal data class MembershipRow(
    val bondId: BondId,
    val memberId: MemberId,
    val held: Boolean,
)
