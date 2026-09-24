package com.moyi.bond.service

import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.Membership
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
    private val invites: InviteStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws InviteNotFoundException the invite is already dead, belongs to
     *   another bond, or never existed — one answer for all three, because the
     *   caller is entitled to know about their own bond's invites and nothing
     *   else, and "which of those was it" is not theirs to learn.
     */
    @Transactional
    fun revoke(
        membership: Membership,
        inviteId: InviteId,
    ) {
        if (!invites.revoke(membership.bondId, inviteId, clock.instant())) throw InviteNotFoundException()
        log.info("Invite {} revoked in bond {}", inviteId.value, membership.bondId.value)
    }
}

/** Not this bond's live invite: already spent or revoked, another bond's, or invented. */
internal class InviteNotFoundException : NotFoundException("That invite was not found.")
