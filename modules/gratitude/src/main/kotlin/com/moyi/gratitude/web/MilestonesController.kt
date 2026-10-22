package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.gratitude.service.GetMilestones
import com.moyi.gratitude.service.MilestonesView
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * `GET /api/v1/bonds/{bondId}/milestones` (FR-075, doc 06:174; ADR-0041): the guard, the
 * joining-day reconcile `GET /streak` runs (a joiner's first request may be this one), the read.
 * No parameter. A member who has left reads it. The API's default `Cache-Control`, no `ETag`,
 * no bucket of its own (decision 11).
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class MilestonesController(
    private val access: BondAccess,
    private val getMilestones: GetMilestones,
    private val joining: ReconcileJoiningDay,
) {
    // Named `milestones`: the method name is the API's operationId.
    @GetMapping("/{bondId}/milestones")
    fun milestones(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): MilestonesResponse {
        val membership = access.guard(caller, bondId)
        joining.beforeRead(membership)
        return MilestonesResponse.from(getMilestones.of(membership))
    }
}

/**
 * What the bond has reached, and what is next.
 *
 * **Thresholds and dates about the bond, and nothing else** (FR-076, H-M2, H-M3): no field
 * names a member, carries an entry, or counts days. Not even `totalCompleteDays`, which
 * `GET /streak` sends: here it would make "days to the next" one subtraction on one response,
 * the countdown the owner ruled out (Q-M3).
 */
internal data class MilestonesResponse(
    /** Oldest first. Kept when the bond ends. */
    val achieved: List<AchievedMilestoneResponse>,
    /** The next threshold, or `null` once all four are reached or the bond takes no writes from the caller. */
    val next: NextMilestoneResponse?,
) {
    companion object {
        fun from(view: MilestonesView): MilestonesResponse =
            MilestonesResponse(
                achieved = view.achieved.map { AchievedMilestoneResponse(it.threshold, it.achievedOn) },
                next = view.next?.let(::NextMilestoneResponse),
            )
    }
}

internal data class AchievedMilestoneResponse(
    /** 7, 30, 100 or 365 complete days. */
    val threshold: Int,
    /** The bond's date of the day that was that many. */
    val achievedOn: LocalDate,
)

internal data class NextMilestoneResponse(
    val threshold: Int,
)
