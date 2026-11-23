package com.moyi.bond.service

import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.Proposal
import com.moyi.common.events.OutboxEvent
import java.time.Instant

/**
 * `ProposalClosed {bondId, proposalId, memberId}` (issue #205, ADR-0069): a member answered a
 * two-party proposal (accepted or declined it) or withdrew their own. Published in the answer's
 * own transaction, under the bond's row lock, only after the compare-and-set that closed the row
 * succeeded: [ChangeTimezone.confirm] and [ChangeTimezone.cancel], [RequestDeletion]'s agreement
 * and its withdrawal of a pending ask, [EnableSemanticSearch]'s agreement and cancel.
 *
 * **Never published** by a lapse (no one acted, ADR-0030), by the bond ending (`EndBond`: no
 * member is named and nobody is told, T-09), by a lone member's self-confirming deletion (nobody
 * else exists), or by the `PATCH` that turns search off with a request still live (the `PATCH`'s
 * own [BondSettingsChanged] covers it).
 *
 * `memberId` is the actor's **member** id: it says whom not to tell. How it ended is not carried:
 * the row is the truth ([Proposal.outcome]), read by [NotifyBondMembers].
 */
internal object ProposalClosed {
    const val TYPE = "ProposalClosed"
    const val BOND_ID = "bondId"
    const val PROPOSAL_ID = "proposalId"
    const val MEMBER_ID = "memberId"

    fun of(
        proposal: Proposal,
        memberId: MemberId,
        at: Instant,
    ): OutboxEvent =
        OutboxEvent(
            aggregateType = "BondProposal",
            aggregateId = proposal.id.value,
            eventType = TYPE,
            references =
                mapOf(
                    BOND_ID to proposal.bondId.value,
                    PROPOSAL_ID to proposal.id.value,
                    MEMBER_ID to memberId.value,
                ),
            occurredAt = at,
        )
}
