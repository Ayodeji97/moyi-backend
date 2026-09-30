package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.ChangeTimezone
import com.moyi.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The bond's shared time zone (FR-027, `states.md` §8's three-step flow).
 *
 * Its own controller because it is its own flow — propose, confirm, cancel —
 * rather than another field on `PATCH /bonds/{bondId}`. That separation is the
 * contract telling a client the truth: this setting cannot be changed the way
 * the others can.
 *
 * **No `If-Match` on any of these, and that is a decision** (ADR-0030). B4's
 * condition protects a blind overwrite of fields the client last read; a
 * proposal is a fresh intent about one named value, and requiring the bond's
 * `ETag` here would make the three-step flow fail whenever anything else about
 * the bond had moved in between — including unrelated public settings. Confirmation instead names the immutable
 * proposal id the member reviewed.
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/timezone")
internal class BondTimezoneController(
    private val guard: BondAccessGuard,
    private val changeTimezone: ChangeTimezone,
) {
    /** `200` with the bond: a proposal recorded, or the zone already moved if the caller is alone in it. */
    @PatchMapping
    fun proposeBondTimezone(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: ChangeTimezoneRequest,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = changeTimezone.propose(membership, request.toZone())
        return ResponseEntity.ok().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    @PostMapping("/confirm")
    fun confirmBondTimezone(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: ConfirmTimezoneRequest,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = changeTimezone.confirm(membership, request.toProposalId())
        return ResponseEntity.ok().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    /** Either member, `204`. `states.md` §8 adapts the pending state for it rather than drawing a screen. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelBondTimezoneChange(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        changeTimezone.cancel(membership)
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
