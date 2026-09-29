package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.gratitude.service.GetToday
import com.moyi.gratitude.service.SubmitEntry
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `/api/v1/bonds/{bondId}/entries` and `/api/v1/bonds/{bondId}/today` (spec
 * §6.2, §6.1). Behind the bearer, as every bond-scoped path is — nothing
 * here is in `SecurityConfiguration`'s public list.
 *
 * **One controller for both, not two.** Both are reads and writes of the
 * same `/api/v1/bonds/{bondId}/…` resource, and `BondsController` already
 * sets the precedent this follows: one controller per path prefix, not one
 * per HTTP verb or per use case.
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
 * **The method names are API names, not Kotlin ones.** springdoc derives
 * each operation's `operationId` from the method name alone — the class is
 * not part of it — so [today] is named `today`, not `get`: a `get()` here
 * would collide with another controller's own `get()` and springdoc would
 * silently rename one of them to `get_1`, renaming a generated client's
 * method for an endpoint that did not change. `BondsController`'s own KDoc
 * has the fuller account, including the PR whose review found it.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class EntriesController(
    private val access: BondAccess,
    private val submitEntry: SubmitEntry,
    private val getToday: GetToday,
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

    /**
     * `GET /bonds/{bondId}/today` (spec §6.1). Named `today`, not `get` —
     * see this class's own KDoc on why the method name is the API's
     * `operationId` and not a Kotlin implementation detail.
     *
     * No `ETag`: nothing here is a resource a client would `PATCH` with
     * `If-Match` — `TodayResponse`'s own KDoc has the fuller account of what
     * this returns and, as importantly, what it deliberately does not.
     */
    @GetMapping("/{bondId}/today")
    fun today(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): TodayResponse {
        val membership = access.membershipOf(caller.id, bondIdOrNotFound(bondId))
        return TodayResponse.from(getToday.today(membership))
    }

    /** The one 404, matching `BondAccess.membershipOf`'s own — doc 06 §2, T-02: not found or not permitted to know it exists. */
    private fun bondIdOrNotFound(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw NotFoundException("That bond was not found.") }
}
