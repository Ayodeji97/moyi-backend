package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.gratitude.service.GetDays
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/v1/bonds/{bondId}/search?q=&limit=&cursor=` (FR-050, FR-091, doc 06:172; ADR-0039):
 * the caller's archive filtered by words, a page of days at a time, in the feed's own shape
 * ([DaysResponse]).
 *
 * **The guard first, then the parameters, then the joining-day reconcile, then the read**:
 * `DaysController`'s order, for its reasons. `q` arrives as text and is read only after the
 * membership is resolved, so a stranger gets the bond's `404` whatever `q` is, and a member
 * whose `q` cannot be searched gets one `422` on `q` that does not carry it back.
 *
 * **Not a conditional read, and kept by nobody.** No `ETag`; the response carries the API's
 * default `Cache-Control` (`no-cache, no-store, max-age=0, must-revalidate`), so no cache, the
 * client's included, keeps what was found (ADR-0039 decision 17).
 *
 * No bucket of its own: reads under the global per-user limit (decision 19).
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class SearchController(
    private val access: BondAccess,
    private val getDays: GetDays,
    private val joining: ReconcileJoiningDay,
) {
    // Named `search`: the method name is the API's operationId.
    @GetMapping("/{bondId}/search")
    fun search(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
    ): DaysResponse {
        val membership = access.guard(caller, bondId)
        val query = SearchQuery.parse(q, limit, cursor)
        joining.beforeRead(membership)
        return DaysResponse.from(getDays.search(membership, query.terms, query.before, query.limit))
    }
}
