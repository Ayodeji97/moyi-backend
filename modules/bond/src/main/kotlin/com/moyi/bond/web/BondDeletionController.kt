package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.RequestDeletion
import com.moyi.common.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Closing the box (FR-028, `states.md` §9).
 *
 * `POST` asks, and the other member's `POST` agrees — one route for both,
 * because the screen has one button and a separate confirm path would be a
 * second way to say the same thing.
 *
 * **`202`, not `200`:** nothing is destroyed when this returns. The bond enters
 * a 30-day cooling-off either member can cancel, and Phase 5's job is what
 * finally acts. `@ResponseStatus` as well as the entity, because springdoc reads
 * the annotation and a contract that said `200` would be a lie a generated
 * client was built on (the B1 lesson, ADR-0024).
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/deletion-request")
internal class BondDeletionController(
    private val guard: BondAccessGuard,
    private val requestDeletion: RequestDeletion,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestBondDeletion(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = requestDeletion.request(membership)
        return ResponseEntity.accepted().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    /** Either member, at any point in the cooling-off. `204`. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelBondDeletion(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        requestDeletion.cancel(membership)
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
