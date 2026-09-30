package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalKind
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.bond.infra.database.ProposalStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `POST /bonds/{bondId}/deletion-request` and `DELETE …/deletion-request`
 * (FR-028, `states.md` §9's "close the box", ADR-0030).
 *
 * **One endpoint asks and confirms.** The first member's `POST` records the
 * request; the second member's `POST` *is* the agreement. A separate confirm
 * route would be a second way to say the same thing, and `states.md` §9 draws
 * one button for it.
 *
 * `202` throughout, because nothing is destroyed here: the bond enters a 30-day
 * cooling-off that either member can cancel, and Phase 5's job is what reads
 * `PENDING_DELETION` and acts. A `200` would claim the work was done.
 *
 * **Refused on any bond that has already ended**, whichever way it ended. Spec
 * §6.4 refused it only on a bond ended by a *block*, and that is an oracle: the
 * blocked member would learn which happened by trying it once, which is exactly
 * what doc 26 §2.1 forbids and what the review of B3 caught in another form.
 * Daniel took that decision before this slice was written; ADR-0030 carries it
 * and `DiscreetExitTest` holds it.
 */
@Service
internal class RequestDeletion(
    private val bonds: BondStore,
    private val proposals: ProposalStore,
    private val invites: InviteStore,
    private val views: BondViews,
    private val support: BondSupport,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Asks for the bond to be destroyed, or agrees to somebody else's ask.
     *
     * Idempotent for the same member: asking twice is `202` and changes nothing,
     * because a person tapping a button twice has not consented twice.
     *
     * @throws BondArchivedException the bond has ended, or a deletion is already
     *   pending — see the class KDoc for why those are one answer
     */
    @Transactional
    fun request(membership: Membership): BondView {
        bonds.lockBond(membership.bondId)
        val now = support.clock.instant().truncatedTo(ChronoUnit.MICROS)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        // Already counting down: both have asked, and asking again changes
        // nothing. Deliberately *not* an error — `states.md` §9's screen shows
        // the date, and a second tap should leave it there.
        if (bond.status == BondStatus.PENDING_DELETION) return view(membership)
        if (!bond.isOpen) throw BondArchivedException()

        proposals.closeLapsed(membership.bondId, ProposalKind.DELETION, now)
        val existing = proposals.findLive(membership.bondId, ProposalKind.DELETION, now)
        when {
            existing == null -> {
                open(bond, membership, now)
            }

            existing.proposedByMemberId == membership.memberId -> {
                log.info("A deletion request was repeated on bond {}", membership.bondId.value)
            }

            else -> {
                agree(bond, existing, membership, now)
            }
        }
        return view(membership)
    }

    /**
     * Either member calls the deletion off (FR-028's escape hatch).
     *
     * **No `isOpen` check, and that is the point**: `PENDING_DELETION` is not
     * open, so if this consulted it the cooling-off could never be cancelled —
     * which is the one thing a cooling-off exists for.
     *
     * **This path rechecks active membership under the bond lock.** The
     * guard's [Membership.left] is only a snapshot: a concurrent leave can
     * commit before cancellation acquires its lock. Without the current check,
     * a member could agree to deletion, walk out, then revoke that agreement
     * and strand the remaining member in an archived bond that cannot be
     * deleted. Walking away must not undo what both agreed.
     *
     * The refusal is [ProposalNotFoundException]'s one answer, not a new code:
     * "never made, already answered, cancelled, lapsed" gains a fifth cause
     * that reads identically, and a member who has left has nothing pending in
     * any sense they can act on.
     *
     * @throws ProposalNotFoundException nothing was pending, or the caller has left
     */
    @Transactional
    fun cancel(membership: Membership) {
        bonds.lockBond(membership.bondId)
        val now = support.clock.instant().truncatedTo(ChronoUnit.MICROS)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        val pending = proposals.findLive(membership.bondId, ProposalKind.DELETION, now)
        val cancelled =
            when {
                // A member who has left has nothing to call off. First, so that
                // re-read under the lock: membership.left was captured by the
                // guard before a concurrent leave could commit. Inside this `when`
                // rather than as an early throw, so that every "nothing to
                // cancel" leaves by the one door below.
                bond.memberOf(membership.userId)?.isActive != true -> {
                    false
                }

                // An unanswered ask: withdraw it, and the bond never changed status.
                pending != null -> {
                    proposals.cancel(pending.id, now)
                }

                // Already counting down: this is the escape hatch itself.
                bond.status == BondStatus.PENDING_DELETION -> {
                    bonds.update(bond.cancelDeletion(now))
                    true
                }

                else -> {
                    false
                }
            }
        if (!cancelled) throw ProposalNotFoundException()
        log.info("A deletion was called off on bond {}", membership.bondId.value)
    }

    /**
     * The first ask.
     *
     * On a bond with one member there is nobody to agree, so the request
     * confirms itself (spec §11 decision 6) — and the proposal is still written,
     * confirmed by the same member, because a bond in `PENDING_DELETION` with no
     * record of who asked would be a state nobody could explain later.
     */
    private fun open(
        bond: Bond,
        membership: Membership,
        now: Instant,
    ) {
        val proposal = Proposal.deletion(ProposalId(support.ids.timeOrdered()), bond.id, membership.memberId, now)
        proposals.insert(proposal)
        if (bond.activeMembers.size == 1) {
            proposals.confirm(proposal.id, membership.memberId, now)
            schedule(bond, now)
            log.info("Bond {} was scheduled for deletion by its only member", bond.id.value)
        } else {
            log.info("A deletion was requested on bond {}", bond.id.value)
        }
    }

    /** The second member agrees, and the cooling-off starts. */
    private fun agree(
        bond: Bond,
        proposal: Proposal,
        membership: Membership,
        now: Instant,
    ) {
        // Compare-and-set: if somebody else answered it in the meantime, the
        // bond is already counting down and there is nothing left to do.
        if (proposals.confirm(proposal.id, membership.memberId, now)) {
            schedule(bond, now)
            log.info("Bond {} was scheduled for deletion by agreement", bond.id.value)
        }
    }

    /**
     * Starts the cooling-off, and **revokes any live invite**.
     *
     * A bond counting down to deletion refuses every join — `hasRoom` requires
     * `PENDING_MEMBER` — so a code it went on advertising would be a code that
     * cannot work, offered for thirty days. `EndBond` revokes for the same
     * reason when a bond ends ("a code into a closed room"); this path had been
     * missed, and the review of PR #41 found it.
     */
    private fun schedule(
        bond: Bond,
        now: Instant,
    ) {
        bonds.update(bond.requestDeletion(now))
        invites.revokeLiveOf(bond.id, now)
    }

    private fun view(membership: Membership): BondView {
        val persisted =
            bonds.findByMember(membership.bondId, membership.userId) ?: error("the bond just written is gone")
        return views.of(persisted, membership.userId)
    }
}
