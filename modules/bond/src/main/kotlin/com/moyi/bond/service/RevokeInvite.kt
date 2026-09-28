package com.moyi.bond.service

import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.common.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `DELETE /bonds/{bondId}/invites/{inviteId}` (FR-023).
 *
 * The control `states.md` §2 calls "Cancel this invite" — the domain keeps
 * the word *revoke* (`04`'s `revokedAt`, `06`'s `DELETE`), and only the
 * button a member reads was softened. That mismatch is deliberate; do not
 * reconcile them.
 *
 * It matters more than a settings nicety: an invite is one-use but not
 * recipient-safe, so whoever opens the link first claims the bond. Being able
 * to kill a code that went to the wrong place is the only remedy.
 */
@Service
internal class RevokeInvite(
    private val bonds: BondStore,
    private val invites: InviteStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws BondArchivedException the bond has ended (BR-9). Ending a bond
     *   revokes its live invite already, so the alternative answer would be the
     *   404 a dead invite gives — true, but less than the member is entitled to,
     *   and different from what every other write on an archived bond says. The
     *   design's §6.3 makes it one rule: every bond-scoped write on an archived
     *   bond is this (ADR-0028).
     * @throws InviteNotFoundException the invite is already dead, belongs to
     *   another bond, or never existed — one answer for all three, because the
     *   caller is entitled to know about their own bond's invites and nothing
     *   else, and "which of those was it" is not theirs to learn.
     */
    @Transactional
    @Suppress("ThrowsCount")
    fun revoke(
        membership: Membership,
        inviteId: InviteId,
    ) {
        // Under the bond's row lock, for the reason the Codex bot gave on PR #39:
        // without it, a revoke that reads the bond as open can be overtaken by a
        // leave, which archives the bond *and* revokes its live invite — and the
        // conditional UPDATE below then matches nothing, so the caller gets a
        // `404` that neither serial ordering produces (revoke first is `204`,
        // leave first is `409 BOND_ARCHIVED`). The lock makes the two orderings
        // the only outcomes, which is what ADR-0028's archived rule promises.
        bonds.lockBond(membership.bondId)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        if (!invites.revoke(membership.bondId, inviteId, clock.instant())) throw InviteNotFoundException()
        log.info("Invite {} revoked in bond {}", inviteId.value, membership.bondId.value)
    }
}

/** Not this bond's live invite: already spent or revoked, another bond's, or invented. */
internal class InviteNotFoundException : NotFoundException("That invite was not found.")
