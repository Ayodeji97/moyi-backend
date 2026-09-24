package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.CreateInvite
import com.moyi.bond.service.InviteLinks
import com.moyi.bond.service.InviteNotFoundException
import com.moyi.bond.service.RevokeInvite
import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A bond's invites (doc 06 §3.3). Bond-scoped, so the guard runs first on
 * both methods and a non-member is told the bond does not exist — the
 * cross-tenant suite covers both routes.
 *
 * Separate from `BondsController` because the two have different subjects:
 * that one is about bonds, this is about the codes that let someone into one.
 * `/invites/{code}` is different again and lives in `InvitesController`, keyed
 * on the code rather than on a bond the caller cannot yet name.
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/invites")
internal class BondInvitesController(
    private val guard: BondAccessGuard,
    private val createInvite: CreateInvite,
    private val revokeInvite: RevokeInvite,
    private val links: InviteLinks,
) {
    /** FR-022. Ten a day per user: creating one revokes the last, so this bounds churn rather than concurrency. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitBucket.INVITE_CREATE_USER)
    fun create(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): InviteDetailResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val invite = createInvite.forBond(membership)
        return InviteDetailResponse.from(invite, links.linkFor(invite.code))
    }

    /** FR-023. `204`, and the same 404 for an invite that is already dead, another bond's, or invented. */
    @DeleteMapping("/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @PathVariable inviteId: String,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        revokeInvite.revoke(membership, inviteIdOrNotFound(inviteId))
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })

    /** A value that is not an id cannot name an invite, and the answer to that is the same 404. */
    private fun inviteIdOrNotFound(raw: String): InviteId =
        InviteId(runCatching { UUID.fromString(raw) }.getOrElse { throw InviteNotFoundException() })
}
