package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalKind
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Proposals, spoken in domain terms — the [InviteStore] precedent, and its own
 * store for the same reason: a proposal has a lifecycle of its own (made,
 * confirmed, cancelled, lapsed) that no invariant of the bond depends on, and
 * the two are written together only in the transaction a service owns.
 *
 * Not transactional itself: the boundary is the caller's (doc 18 §4), and every
 * interesting sequence here runs inside one the service opened under the bond's
 * row lock.
 */
@Component
internal class ProposalStore(
    private val proposals: BondProposalRepository,
) {
    fun insert(proposal: Proposal) {
        proposals.save(proposal.toEntity())
    }

    fun findLive(
        bondId: BondId,
        kind: ProposalKind,
        now: Instant,
    ): Proposal? = proposals.findLive(bondId.value, kind, now)?.toDomain()

    /** Every live proposal of these bonds, keyed by bond — one query however many bonds. */
    fun findAllLiveOf(
        bondIds: Collection<BondId>,
        now: Instant,
    ): Map<BondId, List<Proposal>> {
        if (bondIds.isEmpty()) return emptyMap()
        return proposals
            .findAllLiveOf(bondIds.map { it.value }, now)
            .map { it.toDomain() }
            .groupBy { it.bondId }
    }

    /**
     * The latest ended proposal of each kind, per bond (at most three each), keyed by bond — one
     * query however many bonds. Ended is the complement of [findLive]: see
     * [BondProposalRepository.findLatestEndedOf]. Read each one's [Proposal.outcome].
     */
    fun findLatestEndedOf(
        bondIds: Collection<BondId>,
        now: Instant,
    ): Map<BondId, List<Proposal>> {
        if (bondIds.isEmpty()) return emptyMap()
        return proposals
            .findLatestEndedOf(bondIds.map { it.value }, now)
            .map { it.toDomain() }
            .groupBy { it.bondId }
    }

    /** One proposal of this bond, whatever its state; `null` if it is not this bond's. */
    fun findInBond(
        bondId: BondId,
        id: ProposalId,
    ): Proposal? = proposals.findInBond(bondId.value, id.value)?.toDomain()

    /** @return `true` if a lapsed proposal was in the way and has been closed. See [BondProposalRepository.closeLapsed]. */
    fun closeLapsed(
        bondId: BondId,
        kind: ProposalKind,
        now: Instant,
    ): Boolean = proposals.closeLapsed(bondId.value, kind, now) > 0

    /** @return `false` if somebody else confirmed it, or it was cancelled or lapsed first. */
    fun confirm(
        id: ProposalId,
        memberId: MemberId,
        now: Instant,
    ): Boolean = proposals.confirm(id.value, memberId.value, now) == 1

    /**
     * [by] calls it off: recorded as theirs, so [Proposal.outcome] reads a withdrawal or a refusal
     * (or as a lapse, naming nobody, if it had already lapsed).
     *
     * @return `false` if it was already closed — one answer for every way that can be true.
     */
    fun cancel(
        id: ProposalId,
        by: MemberId,
        now: Instant,
    ): Boolean = proposals.cancel(id.value, by.value, now) == 1

    /**
     * ADR-0028: ending a bond leaves nothing open. Recorded `BOND_ENDED`, **naming no member**,
     * whoever's leave or block it was (T-09). @return how many were cancelled.
     */
    fun cancelLiveOf(
        bondId: BondId,
        now: Instant,
    ): Int = proposals.cancelLiveOf(bondId.value, now)

    /** Cancels the open proposals of one kind only; [cancelLiveOf] stays for ending a bond (ADR-0028). */
    fun cancelLive(
        bondId: BondId,
        kind: ProposalKind,
        by: MemberId,
        now: Instant,
    ): Int = proposals.cancelLive(bondId.value, kind, by.value, now)
}
