package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

/** `TodayResponse.prompt` — see [documentPrompts]. */
private const val PROMPT_PROPERTY = "prompt"

/**
 * `TodayResponse.prompt` says when it is null (ADR-0038). `SubmitEntryRequest.promptId`
 * is Task 3's and is documented there.
 *
 * In a file of its own, not in `OpenApiConfiguration.kt`: that class and that file both sit
 * at detekt's `TooManyFunctions` threshold.
 */
internal fun documentPrompts(api: OpenAPI) {
    api.components.schemas[TODAY_RESPONSE]
        ?.properties
        ?.get(PROMPT_PROPERTY)
        ?.description =
        "The bond's prompt of the day: the same prompt for both members, each in their own language. Null when the " +
        "bond takes no writes from the caller (it has ended, is counting down to deletion, or the caller has left), " +
        "and when the library holds nothing for this bond. Optional to answer; dismissing it is the client's alone."
}
