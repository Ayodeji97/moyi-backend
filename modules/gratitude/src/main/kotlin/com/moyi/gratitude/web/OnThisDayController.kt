package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.gratitude.service.GetDays
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/v1/bonds/{bondId}/on-this-day` (FR-092, doc 06:175; ADR-0039): what the bond wrote
 * on this date in earlier years. The guard, the joining-day reconcile, the read; no parameter.
 *
 * Always answers a member (the owner's Q-O2: the opt-in is the weekly push's, Phase 4), and
 * answers `{"items": []}` on a bond that takes no writes (Q-O3). The API's default
 * `Cache-Control`, no `ETag` (decisions 17 and 18). No bucket of its own.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class OnThisDayController(
    private val access: BondAccess,
    private val getDays: GetDays,
    private val joining: ReconcileJoiningDay,
) {
    // Named `onThisDay`: the method name is the API's operationId.
    @GetMapping("/{bondId}/on-this-day")
    fun onThisDay(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): OnThisDayResponse {
        val membership = access.guard(caller, bondId)
        joining.beforeRead(membership)
        return OnThisDayResponse.from(getDays.onThisDay(membership))
    }
}
