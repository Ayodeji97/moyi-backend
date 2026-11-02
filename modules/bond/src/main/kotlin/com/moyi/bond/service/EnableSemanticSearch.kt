package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalKind
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.ProposalStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Turning semantic search back on (FR-204, ADR-0054): both members, because it
 * sends what both of them wrote to an embedding provider.
 *
 * The deletion request's shape (`RequestDeletion`): one route asks and the other
 * member's call to the same route agrees; there is no payload, so no proposal id.
 * A bond still waiting for its second member has nobody else to ask, and applies
 * the change at once. Turning it **off** is one member's `PATCH` (`UpdateBond`).
 */
@Service
internal class EnableSemanticSearch(
    private val bonds: BondStore,
    private val proposals: ProposalStore,
    private val views: BondViews,
    private val support: BondSupport,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun request(membership: Membership): BondView {
        bonds.lockBond(membership.bondId)
        val now = support.clock.instant().truncatedTo(ChronoUnit.MICROS)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        when {
            // Already on: nothing to ask for, and the answer is the bond as it is.
            bond.semanticSearchEnabled -> {
                Unit
            }

            bond.status == BondStatus.PENDING_MEMBER -> {
                bonds.update(bond.withSemanticSearch(true))
                log.info("Semantic search was turned on for bond {} by its only member", bond.id.value)
            }

            else -> {
                proposeOrAgree(bond, membership, now)
            }
        }
        return view(membership)
    }

    /** Either member calls the request off. Nothing pending is `404`, as for the deletion request. */
    @Transactional
    fun cancel(membership: Membership) {
        bonds.lockBond(membership.bondId)
        val now = support.clock.instant().truncatedTo(ChronoUnit.MICROS)
        bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        val pending = proposals.findLive(membership.bondId, ProposalKind.SEMANTIC_SEARCH_ON, now)
        // Under the lock nothing can cancel or confirm between the read and this; `cancel`'s CAS is the backstop.
        if (pending == null || !proposals.cancel(pending.id, now)) throw ProposalNotFoundException()
        log.info("A request for semantic search was called off on bond {}", membership.bondId.value)
    }

    private fun proposeOrAgree(
        bond: Bond,
        membership: Membership,
        now: Instant,
    ) {
        proposals.closeLapsed(membership.bondId, ProposalKind.SEMANTIC_SEARCH_ON, now)
        val existing = proposals.findLive(membership.bondId, ProposalKind.SEMANTIC_SEARCH_ON, now)
        when {
            existing == null -> {
                open(bond, membership, now)
            }

            existing.proposedByMemberId == membership.memberId -> {
                log.info("A request for semantic search was repeated on bond {}", membership.bondId.value)
            }

            else -> {
                agree(bond, existing, membership, now)
            }
        }
    }

    private fun open(
        bond: Bond,
        membership: Membership,
        now: Instant,
    ) {
        val proposal = Proposal.semanticSearchOn(ProposalId(support.ids.timeOrdered()), bond.id, membership.memberId, now)
        proposals.insert(proposal)
        support.events.publish(ProposalOpened.of(proposal))
        log.info("Semantic search was requested on bond {}", bond.id.value)
    }

    private fun agree(
        bond: Bond,
        proposal: Proposal,
        membership: Membership,
        now: Instant,
    ) {
        if (proposals.confirm(proposal.id, membership.memberId, now)) {
            bonds.update(bond.withSemanticSearch(true))
            log.info("Semantic search was turned on for bond {} by agreement", bond.id.value)
        }
    }

    private fun view(membership: Membership): BondView {
        val persisted =
            bonds.findByMember(membership.bondId, membership.userId) ?: error("the bond just written is gone")
        return views.of(persisted, membership.userId)
    }
}
