package com.moyi.bond.domain

import java.time.Duration
import java.time.Instant

/**
 * A single-use, seven-day, revocable invitation to a bond (FR-022, FR-023).
 *
 * Part of the Bond aggregate (doc 04 §2) but stored and loaded separately:
 * no invariant of a bond depends on its invites except "at most one live one",
 * which slice B2 enforces where it can be enforced — in the transaction that
 * issues the next one.
 *
 * The four ways an invite stops working — spent, revoked, expired, or for a
 * bond that is full — are one indistinguishable answer to a caller (FR-024,
 * `states.md` §2). [isLive] covers the first three; the fourth belongs to the
 * bond, not to this row.
 */
internal data class Invite(
    val id: InviteId,
    val bondId: BondId,
    val code: InviteCode,
    val createdByMemberId: MemberId,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val usedByUserId: UserId?,
    val revokedAt: Instant?,
) {
    fun isLive(now: Instant): Boolean = usedAt == null && revokedAt == null && expiresAt.isAfter(now)

    companion object {
        /** FR-023: seven days. Not configurable — a shorter one strands the couple, a longer one is a live credential nobody remembers. */
        val TTL: Duration = Duration.ofDays(7)

        fun issue(
            id: InviteId,
            bondId: BondId,
            code: InviteCode,
            createdBy: MemberId,
            now: Instant,
        ): Invite =
            Invite(
                id = id,
                bondId = bondId,
                code = code,
                createdByMemberId = createdBy,
                expiresAt = now.plus(TTL),
                usedAt = null,
                usedByUserId = null,
                revokedAt = null,
            )
    }
}
