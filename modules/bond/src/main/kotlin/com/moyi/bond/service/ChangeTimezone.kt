package com.moyi.bond.service

import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalKind
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.ProposalStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `PATCH /bonds/{bondId}/timezone`, `POST …/timezone/confirm` and
 * `DELETE …/timezone` (FR-027, ADR-0030).
 *
 * **Why this one setting needs both members, when B4's `PATCH /bonds/{bondId}`
 * needs neither:** the anchor zone decides which day an entry belongs to, for
 * both of them (doc 04 §6). Moving it re-slices the other person's day
 * boundaries, and doc 04 §8.5 will not recompute a day that has already been
 * opened — so a unilateral change is a change to somebody else's past.
 *
 * Every path takes the bond's row lock first, which is this module's standing
 * rule (ADR-0028 §6a): each of these is a read-then-conditional-write, and a
 * propose, a confirm, a cancel and a leave can all arrive at once.
 *
 * **The 30-day rule is checked twice** — when the change is proposed and again
 * when it is confirmed — because up to seven days pass in between and the window
 * can close while a proposal waits.
 */
@Service
internal class ChangeTimezone(
    private val bonds: BondStore,
    private val proposals: ProposalStore,
    private val views: BondViews,
    private val support: BondSupport,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Proposes a new anchor zone — or applies it at once, if there is nobody to
     * ask (spec §11 decision 6: while `PENDING_MEMBER` the creator is alone, and
     * ADR-0004 expects an onboarding mistake to be correctable).
     *
     * @throws BondArchivedException the bond has ended (BR-9)
     * @throws TimezoneChangeTooSoonException less than 30 days since the last change (FR-027)
     * @throws ProposalPendingException a zone change is already waiting
     */
    @Transactional
    @Suppress("ThrowsCount")
    fun propose(
        membership: Membership,
        zone: RegionZone,
    ): BondView {
        val now = support.clock.instant()
        bonds.lockBond(membership.bondId)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        if (!bond.mayChangeTimezoneAt(now)) {
            throw TimezoneChangeTooSoonException(requireNotNull(bond.nextTimezoneChangeAt), bond.anchorTimezone)
        }

        if (bond.status == BondStatus.PENDING_MEMBER) {
            bonds.update(bond.withAnchorTimezone(zone, now))
            log.info("Bond {} moved its anchor zone directly, having one member", membership.bondId.value)
            return view(membership)
        }

        // A lapsed proposal is invisible to every read and still holds V10's
        // unique slot, because an index cannot ask what time it is. Closing it
        // here is what makes "one live proposal" and "lapsed proposals are
        // ignored" both true at once (ADR-0030).
        proposals.closeLapsed(membership.bondId, ProposalKind.TIMEZONE_CHANGE, now)
        if (proposals.findLive(membership.bondId, ProposalKind.TIMEZONE_CHANGE, now) != null) {
            throw ProposalPendingException()
        }
        proposals.insert(Proposal.timezoneChange(ProposalId(support.ids.timeOrdered()), bond.id, zone, membership.memberId, now))
        log.info("A zone change was proposed on bond {}", membership.bondId.value)
        return view(membership)
    }

    /**
     * The other member agrees, and the zone moves (FR-027, BR-6).
     *
     * @throws ProposalNotFoundException nothing is waiting — never proposed,
     *   already answered, cancelled, or lapsed: one answer for all four, because
     *   which of them it was is not a distinction the caller can act on
     * @throws ProposalNeedsOtherMemberException the caller proposed it themselves
     * @throws TimezoneChangeTooSoonException the window closed while it waited
     */
    @Transactional
    @Suppress("ThrowsCount")
    fun confirm(membership: Membership): BondView {
        val now = support.clock.instant()
        bonds.lockBond(membership.bondId)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        val proposal =
            proposals.findLive(membership.bondId, ProposalKind.TIMEZONE_CHANGE, now) ?: throw ProposalNotFoundException()
        if (proposal.proposedByMemberId == membership.memberId) throw ProposalNeedsOtherMemberException()
        if (!bond.mayChangeTimezoneAt(now)) {
            throw TimezoneChangeTooSoonException(requireNotNull(bond.nextTimezoneChangeAt), bond.anchorTimezone)
        }

        // Compare-and-set: losing this race means somebody else answered it,
        // which from here is indistinguishable from it never having been live.
        if (!proposals.confirm(proposal.id, membership.memberId, now)) throw ProposalNotFoundException()
        bonds.update(bond.withAnchorTimezone(proposal.proposedZone(), now))
        log.info("Bond {} moved its anchor zone by agreement", membership.bondId.value)
        return view(membership)
    }

    /**
     * Either member calls the proposal off (FR-027).
     *
     * No `isOpen` check: cancelling takes nothing away and there is nothing to
     * protect, and `states.md` §8's pending screen may still be offering it on a
     * bond that has since ended.
     *
     * @throws ProposalNotFoundException nothing was waiting
     */
    @Transactional
    fun cancel(membership: Membership) {
        val now = support.clock.instant()
        bonds.lockBond(membership.bondId)
        val proposal =
            proposals.findLive(membership.bondId, ProposalKind.TIMEZONE_CHANGE, now) ?: throw ProposalNotFoundException()
        if (!proposals.cancel(proposal.id, now)) throw ProposalNotFoundException()
        log.info("A zone change was cancelled on bond {}", membership.bondId.value)
    }

    /** Re-read, so the response carries the row's current version as its `ETag` (the B4 lesson). */
    private fun view(membership: Membership): BondView {
        val persisted =
            bonds.findByMember(membership.bondId, membership.userId) ?: error("the bond just written is gone")
        return views.of(persisted, membership.userId)
    }
}
