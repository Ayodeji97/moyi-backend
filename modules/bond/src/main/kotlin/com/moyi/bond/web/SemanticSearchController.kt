package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.EnableSemanticSearch
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
 * Turning semantic search back on (FR-204, ADR-0054).
 *
 * The deletion request's shape: `POST` asks, and the other member's `POST`
 * agrees; `DELETE` calls a pending request off. Turning it **off** is not here:
 * it is one member's `PATCH /bonds/{bondId}` with `semanticSearchEnabled: false`.
 *
 * **`202`, not `200`:** on a paired bond nothing changes until the other member
 * agrees, and once on, the entries are embedded later by the sweep.
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/semantic-search")
internal class SemanticSearchController(
    private val guard: BondAccessGuard,
    private val enableSemanticSearch: EnableSemanticSearch,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestSemanticSearch(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = enableSemanticSearch.request(membership)
        return ResponseEntity.accepted().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    /** Either member, while a request is pending. `204`; nothing pending is `404`. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelSemanticSearchRequest(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        enableSemanticSearch.cancel(membership)
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
