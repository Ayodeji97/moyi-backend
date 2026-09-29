package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.gratitude.service.SubmitEntry
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `/api/v1/bonds/{bondId}/entries` (spec §6.2). Behind the bearer, as every
 * bond-scoped path is — nothing here is in `SecurityConfiguration`'s public
 * list.
 *
 * **The guard is this method's first statement**, before `@Valid` would
 * otherwise be the first thing a reader notices — `BondsController`'s own
 * precedent, and the same reasoning: a non-member must never see a `409` or
 * `422` a member would, because either would say the bond is real. What
 * `@Valid`/`@Idempotent` run *before* the method body at all — Spring's own
 * argument binding and this module's `HandlerInterceptor` — is a fact about
 * the caller's own request (a missing header, a malformed body) and does
 * not depend on the bond existing, which is why `BondsController.patchBond`
 * accepts the same ordering for its own `@Valid` body.
 *
 * The id is taken as text and parsed here, not as a `UUID` path variable —
 * `BondsController`'s own reasoning: a value that is not a UUID cannot name
 * a bond, and the answer is the same `404` as a UUID that names nobody's.
 *
 * `submitEntry` is the API name springdoc derives an `operationId` from
 * (`BondsController`'s own KDoc on why the method name is the contract).
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class EntriesController(
    private val access: BondAccess,
    private val submitEntry: SubmitEntry,
) {
    // @ResponseStatus and the ResponseEntity below both set 201 — redundant
    // at runtime (the entity's own status wins), load-bearing for springdoc:
    // it reads the annotation, not the return value, so without it the
    // generated document would say this returns 200 (BondsController.createBond's
    // own KDoc has the fuller account, including the test that holds both
    // in step: OpenApiContractTest's own 201 assertion for this operation).
    @Idempotent
    @PostMapping("/{bondId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    fun submitEntry(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: SubmitEntryRequest,
    ): ResponseEntity<EntryResponse> {
        val membership = access.membershipOf(caller.id, bondIdOrNotFound(bondId))
        val view = submitEntry.submit(membership, request.toDraft())
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(view))
    }

    /** The one 404, matching `BondAccess.membershipOf`'s own — doc 06 §2, T-02: not found or not permitted to know it exists. */
    private fun bondIdOrNotFound(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw NotFoundException("That bond was not found.") }
}
