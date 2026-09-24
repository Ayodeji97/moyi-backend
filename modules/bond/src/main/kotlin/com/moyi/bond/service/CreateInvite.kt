package com.moyi.bond.service

import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.InviteCodes
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.common.core.IdGenerator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `POST /bonds/{bondId}/invites` (FR-022, FR-023): a fresh code for the
 * person who has not joined yet.
 *
 * **Creating one revokes the outstanding one**, in the same transaction, so
 * there is never more than one live code (doc 06 §3.3). `states.md` §2 asks
 * the screen to say so, because a creator who does not know it would assume
 * the code they already shared still works — and a one-use invite is not
 * recipient-safe, so revoking is a real control rather than a settings
 * nicety.
 *
 * Takes a [Membership]: the guard has already decided the caller belongs
 * here.
 */
@Service
internal class CreateInvite(
    private val bonds: BondStore,
    private val invites: InviteStore,
    private val codes: InviteCodes,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws BondArchivedException the bond has ended (BR-9)
     * @throws BondFullException both members are already in it
     */
    @Transactional
    // Three refusals, each a different thing the caller may need told apart.
    @Suppress("ThrowsCount")
    fun forBond(membership: Membership): Invite {
        val now = clock.instant()
        // Under the bond's row lock, and this is not belt-and-braces: without
        // it, two concurrent creates each revoke the invites *their own
        // snapshot* can see and then each insert a new one, leaving a bond
        // with two live codes — which `states.md` §2 promises a member cannot
        // happen, and which the CAS on accept does not prevent because both
        // codes are genuinely live. Found by `InviteRaceTest`, and it is the
        // same shape as the refresh-token family race on PR #32: a revoke over
        // a READ COMMITTED snapshot cannot see a concurrent insert.
        bonds.lockBond(membership.bondId)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        if (!bond.hasRoom) throw BondFullException()

        invites.revokeLiveOf(bond.id, now)
        val invite = Invite.issue(InviteId(ids.timeOrdered()), bond.id, codes.next(), membership.memberId, now)
        invites.insert(invite)
        // The id, never the code: a live code is a credential, and whoever
        // holds it joins the bond (T-06).
        log.info("Invite {} issued for bond {}", invite.id.value, bond.id.value)
        return invite
    }
}
