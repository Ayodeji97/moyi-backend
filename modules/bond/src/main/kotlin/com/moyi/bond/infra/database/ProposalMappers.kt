package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId

// The proposal half of the translation, in its own file because `BondMappers`
// reached detekt's function limit — the same limit that split `InviteStore` out
// in B2 and `MemberStore` in B4, and the same answer: the boundary it points at
// is real. A proposal is not part of the Bond aggregate; it is a question asked
// about one.

internal fun BondProposalEntity.toDomain(): Proposal =
    Proposal(
        id = ProposalId(getId()),
        bondId = BondId(bondId),
        kind = kind,
        payload = payload,
        proposedByMemberId = MemberId(proposedByMemberId),
        proposedAt = proposedAt,
        expiresAt = expiresAt,
        confirmedByMemberId = confirmedByMemberId?.let(::MemberId),
        confirmedAt = confirmedAt,
        cancelledAt = cancelledAt,
    )

/**
 * Insert-only. A proposal's state changes — confirmed, cancelled, closed —
 * are conditional `UPDATE`s in [BondProposalRepository], never an `applyTo`,
 * because each has to be atomic with the check that the proposal is still open.
 */
internal fun Proposal.toEntity(): BondProposalEntity =
    BondProposalEntity(
        id = id.value,
        bondId = bondId.value,
        kind = kind,
        payload = payload,
        proposedByMemberId = proposedByMemberId.value,
        proposedAt = proposedAt,
        expiresAt = expiresAt,
        confirmedByMemberId = confirmedByMemberId?.value,
        confirmedAt = confirmedAt,
        cancelledAt = cancelledAt,
    )
