package com.moyi.bond.service

import com.moyi.bond.domain.Membership
import com.moyi.common.events.OutboxEvent
import java.time.Instant

/**
 * `BondSettingsChanged {bondId, memberId}` (issue #205, ADR-0069): one member changed something
 * both share, alone. Published in the change's own transaction, under the bond's row lock, by
 * `PATCH /bonds/{id}` when the bond after differs from the bond before ([UpdateBond.patch]: a
 * `PATCH` that changes no value publishes nothing), and by calling off a deletion already agreed
 * ([RequestDeletion.cancel] during `PENDING_DELETION`). Never by leaving or blocking (T-09).
 * Read by [NotifyBondMembers], which tells the other member.
 *
 * `memberId` is the actor's **member** id: it says whom not to tell. Which setting changed, and
 * to what, is not carried; the apps read it from the bond (ADR-0035).
 */
internal object BondSettingsChanged {
    const val TYPE = "BondSettingsChanged"
    const val BOND_ID = "bondId"
    const val MEMBER_ID = "memberId"

    /** [actor] is the member who made the change: its bond, and whom not to tell. */
    fun of(
        actor: Membership,
        at: Instant,
    ): OutboxEvent =
        OutboxEvent(
            aggregateType = "Bond",
            aggregateId = actor.bondId.value,
            eventType = TYPE,
            references = mapOf(BOND_ID to actor.bondId.value, MEMBER_ID to actor.memberId.value),
            occurredAt = at,
        )
}
