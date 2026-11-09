package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

/**
 * An account's `bond_members` rows, by id, for account deletion (ADR-0058): `bond` ends the bonds
 * still held, and `gratitude` erases in all of them. Apart from [BondStore], which reads bonds
 * whole for a caller the guard has checked; this reads ids for work that has no caller.
 */
@Component
internal class MembershipRows(
    private val members: BondMemberRepository,
    private val entityManager: EntityManager,
) {
    /**
     * The name [userId] gave the other member, in every bond they were in, emptied (ADR-0058
     * decision 7): it is text the person typed, and it goes with their account. Run before the
     * bonds are loaded, which read their rows again under the lock (Hibernate flushes first, and
     * the members are refreshed), so no entity in memory writes the old name back. Twice is the
     * same as once.
     */
    fun forgetNicknamesOf(userId: UserId) {
        entityManager
            .createNativeQuery("UPDATE bond_members SET nickname_for_other = NULL WHERE user_id = ?1 AND nickname_for_other IS NOT NULL")
            .setParameter(1, userId.value)
            .executeUpdate()
    }

    fun of(userId: UserId): List<MembershipRow> =
        members.findAllByUserId(userId.value).map { MembershipRow(BondId(it.bondId), MemberId(it.getId()), held = it.leftAt == null) }
}

/** One `bond_members` row of an account, by its ids, and whether the person is still in that bond. */
internal data class MembershipRow(
    val bondId: BondId,
    val memberId: MemberId,
    val held: Boolean,
)
