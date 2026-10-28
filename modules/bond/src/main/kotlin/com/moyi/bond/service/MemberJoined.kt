package com.moyi.bond.service

import com.moyi.bond.domain.Member
import com.moyi.common.events.OutboxEvent
import java.time.Instant

/**
 * `MemberJoined {bondId, memberId}` (spec D-13, ADR-0046): the second member
 * accepted an invite, and the Bond is two people's. Published by
 * [AcceptInvite] in the join's own transaction, so it exists exactly when
 * the join does; read by [NotifyBondMembers], which tells the member who was
 * already there (FR-080, "partner joined").
 *
 * Ids only, as every outbox event (ADR-0035): the joiner's member id says
 * whom **not** to tell, and nothing else about them.
 */
internal object MemberJoined {
    const val TYPE = "MemberJoined"
    const val BOND_ID = "bondId"
    const val MEMBER_ID = "memberId"

    fun of(
        member: Member,
        at: Instant,
    ): OutboxEvent =
        OutboxEvent(
            aggregateType = "Bond",
            aggregateId = member.bondId.value,
            eventType = TYPE,
            references = mapOf(BOND_ID to member.bondId.value, MEMBER_ID to member.id.value),
            occurredAt = at,
        )
}
