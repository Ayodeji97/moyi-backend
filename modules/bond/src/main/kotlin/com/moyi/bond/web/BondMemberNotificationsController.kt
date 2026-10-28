package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.MemberNotificationsService
import com.moyi.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Which kinds of push the caller wants in one bond (FR-081, spec §4 N2, D-2,
 * ADR-0045). The caller's own, and only theirs: the path says `me` and takes
 * no member id, as `…/members/me/settings` does.
 *
 * A sibling of the settings resource rather than part of it: that `PUT`
 * clears what it is not sent, and these toggles must survive a client that
 * does not know them yet.
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/members/me/notifications")
internal class BondMemberNotificationsController(
    private val guard: BondAccessGuard,
    private val notifications: MemberNotificationsService,
) {
    /** Readable on an ended bond too, as the rest of a member's settings are. */
    @GetMapping
    fun getMemberNotifications(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): MemberNotificationsResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        return MemberNotificationsResponse.from(notifications.of(membership))
    }

    /**
     * Sets the toggles it is sent and leaves the others as they are. A `PUT`
     * of the resource, whose every field is optional: a client that sends
     * only `onThisDay` changes only that.
     */
    @PutMapping
    fun replaceMemberNotifications(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: MemberNotificationsRequest,
    ): MemberNotificationsResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        return MemberNotificationsResponse.from(notifications.change(membership, request::applyTo))
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
