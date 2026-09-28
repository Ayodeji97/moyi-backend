package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.MemberSettingsService
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
 * One member's own notification settings (doc 06 §3.3, `states.md` §8).
 *
 * Its own controller rather than two more methods on `BondsController`, because
 * the resource is a different one: that is about the bond both members share,
 * this is about the row only the caller can see. The path says `me` and takes no
 * member id, so there is no way to ask for anybody else's — the structural half
 * of "never show the partner's settings".
 *
 * The method names are API names: springdoc builds each `operationId` from the
 * method name alone, so a `get()` here would collide with another controller's
 * and one of them would be silently renamed `get_1` (`OpenApiContractTest`
 * holds it).
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/members/me/settings")
internal class BondMemberSettingsController(
    private val guard: BondAccessGuard,
    private val settings: MemberSettingsService,
) {
    /** Readable on an archived bond too — `states.md` §9 keeps the record open to both members. */
    @GetMapping
    fun getMemberSettings(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): MemberSettingsResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        return MemberSettingsResponse.from(settings.of(membership))
    }

    /**
     * A full replacement, and no `If-Match`: nobody else can write this row, so
     * there is no update to lose (ADR-0029).
     */
    @PutMapping
    fun replaceMemberSettings(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: MemberSettingsRequest,
    ): MemberSettingsResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        return MemberSettingsResponse.from(settings.replace(membership, request.toSettings()))
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
