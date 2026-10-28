package com.moyi.bond.service

import com.moyi.bond.domain.Proposal
import com.moyi.common.events.OutboxEvent

/**
 * `ProposalOpened {bondId, proposalId, proposerId}` (the owner's Q13,
 * ADR-0046): one member asked for a change the other must agree to, and it
 * waits on them (FR-027, FR-028, ADR-0030). Published in the proposal's own
 * transaction wherever a **two-party** proposal row is made: a zone change
 * ([ChangeTimezone.propose], which makes none while the bond has one member)
 * and a deletion request ([RequestDeletion.request], whose lone-member
 * request confirms itself and asks nobody). `EndBond` makes no proposal; it
 * cancels the live ones. Read by [NotifyBondMembers], which tells the other
 * member, once.
 *
 * `proposerId` is the proposer's **member** id, which is what the proposal
 * row records: it says whom not to tell. What is proposed is not carried; the
 * apps read it from the bond.
 */
internal object ProposalOpened {
    const val TYPE = "ProposalOpened"
    const val BOND_ID = "bondId"
    const val PROPOSAL_ID = "proposalId"
    const val PROPOSER_ID = "proposerId"

    fun of(proposal: Proposal): OutboxEvent =
        OutboxEvent(
            aggregateType = "BondProposal",
            aggregateId = proposal.id.value,
            eventType = TYPE,
            references =
                mapOf(
                    BOND_ID to proposal.bondId.value,
                    PROPOSAL_ID to proposal.id.value,
                    PROPOSER_ID to proposal.proposedByMemberId.value,
                ),
            occurredAt = proposal.proposedAt,
        )
}
