package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.UserId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Invites (FR-022, FR-023).
 *
 * Separate from [BondStore] because an invite has a lifecycle of its own —
 * issued, replaced, revoked, spent — that no invariant of the bond depends
 * on. The two are written together only at creation, and it is the service's
 * transaction that makes that atomic rather than a shared class.
 *
 * **Three of these five methods are compare-and-set statements**, and that is
 * the shape of the whole slice: every question about an invite ("is it still
 * live?") is asked in the same statement that acts on the answer, because
 * asking first and acting second is a race with another person holding the
 * same code. Not transactional itself — the boundary is the caller's
 * (doc 18 §4).
 */
@Component
internal class InviteStore(
    private val invites: BondInviteRepository,
) {
    fun insert(invite: Invite) {
        invites.save(invite.toEntity())
    }

    /** The bond this code opens, if the code is still usable. `null` for every way it might not be (FR-024). */
    fun findLiveByCode(
        code: InviteCode,
        now: Instant,
    ): Invite? = invites.findLiveByCode(code.value, now)?.toDomain()

    /** The live invite of each of [bondIds], keyed by bond; absent where there is none. */
    fun findLiveOf(
        bondIds: Collection<BondId>,
        now: Instant,
    ): Map<BondId, Invite> {
        if (bondIds.isEmpty()) return emptyMap()
        return invites
            .findAllLiveByBondIdIn(bondIds.map { it.value }, now)
            .map { it.toDomain() }
            .associateBy { it.bondId }
    }

    /** @return how many live invites were revoked, so a second one would be visible rather than silent. */
    fun revokeLiveOf(
        bondId: BondId,
        now: Instant,
    ): Int = invites.revokeLiveOf(bondId.value, now)

    /** @return `false` for an invite already dead, belonging to another bond, or never issued — one answer for all three. */
    fun revoke(
        bondId: BondId,
        inviteId: InviteId,
        now: Instant,
    ): Boolean = invites.revokeOf(bondId.value, inviteId.value, now) == 1

    /** @return `false` if somebody else spent it first, or it died meanwhile. See [BondInviteRepository.consume]. */
    fun consume(
        inviteId: InviteId,
        userId: UserId,
        now: Instant,
    ): Boolean = invites.consume(inviteId.value, userId.value, now) == 1
}
