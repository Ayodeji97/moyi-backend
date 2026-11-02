package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.gratitude.service.GetDays
import com.moyi.gratitude.service.QueryEmbedder
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/v1/bonds/{bondId}/remember?q=&limit=` (ADR-0054, spec §3.6): "Remember when". A
 * question in plain words, answered with the days of the caller's archive most relevant to it,
 * the keyword and the meaning rankings fused ([GetDays.remember]), in the archive's day shape
 * ([RememberResponse]). Entries only, never generated text.
 *
 * **The guard first, then the parameters, then the joining-day reconcile, then `q` embedded, then
 * the read**: [SearchController]'s order, for its reasons. A stranger gets the bond's `404`
 * whatever `q` is, and nothing is sent to a provider for them. `q` is embedded outside any
 * transaction; when it cannot be (setting off, no provider, a failure, the timeout) the answer is
 * the keyword ranking alone, still `200`, so the client renders one state.
 *
 * **Not a conditional read, and kept by nobody**, as search: no `ETag`, the API's default
 * `Cache-Control`.
 *
 * **Its own bucket**, `remember:user` ([RateLimitBucket.REMEMBER_USER]), 30 an hour: each call may
 * spend a provider request.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class RememberController(
    private val access: BondAccess,
    private val getDays: GetDays,
    private val joining: ReconcileJoiningDay,
    private val queryEmbedder: QueryEmbedder,
) {
    // Named `remember`: the method name is the API's operationId.
    @RateLimited(RateLimitBucket.REMEMBER_USER)
    @GetMapping("/{bondId}/remember")
    fun remember(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) limit: String?,
    ): RememberResponse {
        val membership = access.guard(caller, bondId)
        val query = RememberQuery.parse(q, limit)
        joining.beforeRead(membership)
        // Outside any transaction, as every provider call is: `null` is the keyword ranking alone.
        val semantic = queryEmbedder.matchFor(membership, query.terms.value)
        val days = getDays.remember(membership, query.terms, semantic, query.limit)
        // Once for the request, after the read: every day carries the archive's one shape (DayResponse).
        return RememberResponse.from(days, access.partnerDisplayNameOf(membership))
    }
}
