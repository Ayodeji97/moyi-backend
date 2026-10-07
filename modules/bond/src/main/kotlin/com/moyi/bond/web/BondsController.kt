package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.CreateBond
import com.moyi.bond.service.EndBond
import com.moyi.bond.service.GetBond
import com.moyi.bond.service.ListBonds
import com.moyi.bond.service.UpdateBond
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.IfMatch
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
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
 *
 * **The method names are API names, not Kotlin ones.** springdoc derives each
 * operation's `operationId` from the method name alone — the class is not part
 * of it — so a `list()` here and a `list()` on the sessions controller collide,
 * and springdoc silently renames one of them to `list_1` depending on scan
 * order. That renames a *generated client's method* for an endpoint that did
 * not change, and `oasdiff` does not catch it because no path or schema moved.
 * Found by the review of PR #37. `OpenApiContractTest` now holds it.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class BondsController(
    private val guard: BondAccessGuard,
    private val createBond: CreateBond,
    private val getBond: GetBond,
    private val bondList: ListBonds,
    private val endBond: EndBond,
    private val updateBond: UpdateBond,
) {
    /**
     * `@ResponseStatus` **and** a `ResponseEntity`, which looks redundant and
     * is not. The entity carries the real status and the `ETag`; the
     * annotation is what springdoc reads, and without it the generated
     * contract says this returns 200 — a lie a generated client would be
     * built on (ADR-0024). The two cannot drift unnoticed:
     * `BondsEndpointTest` asserts the runtime status is 201, and
     * `OpenApiContractTest` asserts the document says so.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createBond(
        caller: CurrentUser,
        @Valid @RequestBody request: CreateBondRequest,
    ): ResponseEntity<BondResponse> {
        val view = createBond.create(request.toDraft(UserId(caller.id)))
        return ResponseEntity.status(HttpStatus.CREATED).eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    @GetMapping
    fun listBonds(caller: CurrentUser): BondsResponse = BondsResponse(bondList.forUser(UserId(caller.id)).map(BondResponse::from))

    @GetMapping("/{bondId}")
    fun getBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = getBond.view(membership)
        return ResponseEntity.ok().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    /**
     * `PATCH /bonds/{bondId}` (doc 06 §1 and §3.3): a settings change,
     * conditional on the `ETag` any bond response carries.
     *
     * **The guard runs before `If-Match` is even parsed**, and that order is the
     * security property: a `428` or a `412` to a non-member would confirm that
     * the bond is real, which is T-02's oracle delivered to the one caller who
     * must not have it. A stranger gets the same 404 whatever their headers say.
     *
     * The body is validated by Spring *before* this method runs, so a malformed
     * body is a `422` for member and non-member alike. That discloses nothing —
     * it is a fact about the caller's own request, and the answer does not depend
     * on the bond existing — which is why `@Valid` stays where it reads best.
     */
    @PatchMapping("/{bondId}")
    fun patchBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: PatchBondRequest,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = updateBond.patch(membership, request.toSettings(), IfMatch.parse(ifMatch))
        return ResponseEntity.ok().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }

    /**
     * FR-026. `204` and no body: there is nothing to return, the bond the caller
     * just left is still readable at [getBond], and a body would be one more
     * place for a difference between this and [blockBond] to hide.
     *
     * `POST` rather than `DELETE`: nothing is deleted. The bond becomes a record
     * both members keep (`states.md` §9).
     *
     * **The body is optional, and so is everything in it** (FR-029a). A client
     * that sends none, as every client did before there was one, leaves as
     * before and keeps their entries: on leaving, withdrawal is *offered*.
     * A body that is there and cannot be read is the same `400` any
     * unreadable body gets, before the guard, for member and stranger alike;
     * so is one with a key this route does not know, or the flag twice
     * ([EndBondRequestReader] has why).
     */
    @PostMapping("/{bondId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leaveBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestBody(required = false) request: EndBondRequest?,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        endBond.leave(membership, withdrawEntries = request?.withdrawEntries ?: false)
    }

    /**
     * FR-029. `204`, byte for byte the response [leaveBond] gives, which doc 26
     * §2.1 requires and `DiscreetExitTest` proves.
     *
     * Unlike leave it is accepted on a bond that has already ended: blocking
     * somebody who left first is what the requirement is for.
     *
     * The same optional body as [leaveBond], with the other default: here
     * withdrawal is *given* unless the caller declines it (FR-029a). Repeating
     * the call with the flag on withdraws what an earlier one kept.
     */
    @PostMapping("/{bondId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun blockBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestBody(required = false) request: EndBondRequest?,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        endBond.block(membership, withdrawEntries = request?.withdrawEntries ?: true)
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
