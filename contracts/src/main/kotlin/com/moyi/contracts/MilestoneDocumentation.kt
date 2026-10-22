package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val MILESTONES_OPERATION = "milestones"

/**
 * `GET /bonds/{bondId}/milestones` (ADR-0041) says what its two fields cannot: what counts, when
 * today does, and when `next` is null. The four thresholds are stated in words, a mirror of
 * `gratitude.domain.Milestones.THRESHOLDS`, which is internal to that module; `MilestoneRulesTest`
 * holds the list and `OpenApiContractTest` holds this sentence.
 */
internal fun documentMilestones(api: OpenAPI) {
    api.paths.values
        .flatMap { it.readOperations() }
        .filter { it.operationId == MILESTONES_OPERATION }
        .forEach { it.description = MILESTONES_DESCRIPTION }
}

private const val MILESTONES_DESCRIPTION =
    "The bond's milestones at 7, 30, 100 and 365 complete days: days on which both members wrote, counted over the " +
        "bond's whole life. A day a freeze covered is not a complete day, and a broken streak loses no milestone. " +
        "`achieved` lists each one reached, oldest first, with the date of the day that reached it. Today counts once it " +
        "has revealed, by the same rule as the streak's numbers.\n\n" +
        "`next` is the next threshold and nothing else. It is null once all four are reached, and whenever the bond " +
        "takes no writes from the caller: it has ended, the caller has left, or it is counting down to deletion. " +
        "`achieved` is kept when the bond ends."
