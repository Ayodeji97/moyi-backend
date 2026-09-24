package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.CreateBond
import com.moyi.bond.service.GetBond
import com.moyi.bond.service.ListBonds
import com.moyi.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `/api/v1/bonds` (doc 06 §3.3). Behind the bearer — none of these paths is in
 * the chain's public list — and nothing here does any work (doc 18 §4).
 *
 * **A bond-scoped method's first statement is the guard**, before any body is
 * validated and before any state is examined, so a non-member never sees a
 * 409, 412 or 422 that a member would: every one of those would say that the
 * bond is real.
 *
 * The id is taken as text and parsed here rather than as a `UUID` parameter,
 * the `SessionsController` precedent: a value that is not a UUID cannot name a
 * bond, and the answer to that is the same 404 as to a UUID that names
 * nobody's — not a 400 complaining about the shape.
 *
 * Every response carrying a bond carries its `ETag`, so a client that later
 * `PATCH`es (slice B4) already holds the `If-Match` it will need.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class BondsController(
    private val guard: BondAccessGuard,
    private val createBond: CreateBond,
    private val getBond: GetBond,
    private val listBonds: ListBonds,
) {
    @PostMapping
    fun create(
        caller: CurrentUser,
        @Valid @RequestBody request: CreateBondRequest,
    ): ResponseEntity<BondResponse> {
        val view = createBond.create(request.toDraft(UserId(caller.id)))
        return ResponseEntity.status(HttpStatus.CREATED).eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    @GetMapping
    fun list(caller: CurrentUser): BondsResponse = BondsResponse(listBonds.forUser(UserId(caller.id)).map(BondResponse::from))

    @GetMapping("/{bondId}")
    fun get(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = getBond.view(membership)
        return ResponseEntity.ok().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
