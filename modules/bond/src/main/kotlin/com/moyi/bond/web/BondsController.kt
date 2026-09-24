package com.moyi.bond.web

import com.moyi.bond.domain.UserId
import com.moyi.bond.service.CreateBond
import com.moyi.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/api/v1/bonds` (doc 06 §3.3). Behind the bearer — none of these paths is in
 * the chain's public list — and nothing here does any work (doc 18 §4).
 *
 * Every response carrying a bond carries its `ETag`, so a client that later
 * `PATCH`es (slice B4) already holds the `If-Match` it will need, without a
 * second round trip to fetch one.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class BondsController(
    private val createBond: CreateBond,
) {
    @PostMapping
    fun create(
        caller: CurrentUser,
        @Valid @RequestBody request: CreateBondRequest,
    ): ResponseEntity<BondResponse> {
        val view = createBond.create(request.toDraft(UserId(caller.id)))
        return ResponseEntity.status(HttpStatus.CREATED).eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }
}
