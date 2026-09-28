package com.moyi.bond.service

import com.moyi.bond.domain.Block
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BlockStore
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.bond.infra.database.ProposalStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `POST /bonds/{bondId}/leave` and `POST /bonds/{bondId}/block` (FR-026,
 * FR-029) — the two ways a bond ends.
 *
 * **One service for both, because they are one operation with one difference.**
 * Leave archives the bond; block archives it and records that these two
 * accounts are not to be put in touch again. Everything else — the row lock,
 * the `left_at`, the revoked invite, the silence — is shared, and sharing the
 * *code* is what makes doc 26 §2.1 true by construction rather than by two
 * implementations happening to agree: *from the other side a block must be
 * indistinguishable from a leave.* `DiscreetExitTest` asserts that on the
 * bytes and on the `ETag`; this class is the reason it passes.
 *
 * Nobody is notified by either path (T-09, "the discreet exit"). There is no
 * email, no push, and no field anywhere that says which of the two happened.
 *
 * Ending a bond also **cancels whatever was waiting to be agreed** — the
 * obligation ADR-0028 recorded and could not discharge, because
 * `bond_proposals` did not exist until slice B5.
 *
 * Both take the bond's row lock before reading, the same lock `AcceptInvite`
 * and `CreateInvite` take. Without it a leave and an accept are two
 * transactions over READ COMMITTED snapshots: the accept sees a free seat, the
 * leave sees an open bond, both commit, and somebody has joined a bond that no
 * longer exists (`EndBondRaceTest`).
 */
@Service
internal class EndBond(
    private val bonds: BondStore,
    private val blocks: BlockStore,
    private val invites: InviteStore,
    private val proposals: ProposalStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * FR-026. `204`.
     *
     * @throws BondArchivedException the bond has already ended (BR-9) — the one
     *   thing that distinguishes this from [block]
     * @throws BondNotFoundException the caller's membership went away between
     *   the guard and here
     */
    @Transactional
    fun leave(membership: Membership) {
        val bond = lockAndLoad(membership)
        if (!bond.isOpen) throw BondArchivedException()
        end(bond.leave(membership.memberId, clock.instant()), bond)
        log.info("A member left bond {}", membership.bondId.value)
    }

    /**
     * FR-029. `204`, and the same `204` [leave] gives.
     *
     * Permitted on a bond that has already ended, which is the one place the
     * two differ in what they accept (ADR-0028): the other person leaving first
     * is exactly when a block is needed. Repeating it is permitted too, and
     * writes nothing the second time.
     *
     * A block is recorded per *other member, current or left* — someone who
     * walked away is still someone this account does not want to meet again —
     * and the store's insert is `ON CONFLICT DO NOTHING`, so two of these at
     * once are two `204`s rather than one and a 500.
     *
     * The log line says "ended", not "blocked", and names no user. Doc 18 §5
     * keeps personal data out of logs, and which of two people blocked the
     * other is as personal as this system gets.
     */
    @Transactional
    fun block(membership: Membership) {
        val bond = lockAndLoad(membership)
        val now = clock.instant()
        bond.members
            .map { it.userId }
            .filter { it != membership.userId }
            .distinct()
            .forEach { other -> blocks.insertIfAbsent(Block(membership.userId, other, bond.id, now)) }
        end(bond.end(membership.memberId, now), bond)
        log.info("A member ended bond {}", membership.bondId.value)
    }

    private fun lockAndLoad(membership: Membership): Bond {
        bonds.lockBond(membership.bondId)
        // Re-read under the lock: the guard's copy was loaded without it, and
        // between the two an accept could have added a member or another leave
        // could have archived the bond.
        return bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
    }

    /**
     * Writes the ended aggregate and kills any live code — and does neither
     * when nothing changed.
     *
     * The equality check is load-bearing. A repeat block would otherwise write
     * the same values back and, while Hibernate's dirty check spares the bond
     * row, the invite revocation is an unconditional statement. Doing nothing is
     * also the honest answer, because nothing happened.
     */
    private fun end(
        ended: Bond,
        before: Bond,
    ) {
        if (ended == before) return
        bonds.archive(ended)
        val at = ended.archivedAt ?: clock.instant()
        // After the aggregate, so the bulk update's automatic flush has the
        // member and bond changes to write first. A live invite outliving the
        // bond it belongs to would be a code into a closed room.
        invites.revokeLiveOf(ended.id, at)
        // **ADR-0028's obligation, paid now that `bond_proposals` exists.** A
        // confirmation arriving after this would otherwise try to move the
        // anchor zone of a bond that has ended, or start a cooling-off on one
        // nobody can write to. Both are refused at the endpoint as well; this is
        // the half that stops the pending state being shown at all.
        proposals.cancelLiveOf(ended.id, at)
    }
}
