package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

/** `TodayResponse.prompt` — see [documentPrompts]. */
private const val PROMPT_PROPERTY = "prompt"

/** `SubmitEntryRequest.promptId` — see [documentPrompts]. */
private const val PROMPT_ID_PROPERTY = "promptId"

/**
 * `TodayResponse.prompt` says when it is null (ADR-0038). `SubmitEntryRequest.promptId` is
 * text in Kotlin (ADR-0038 decision 9: a value that is not an id is the same `422` as one no
 * prompt has), but what a client should send is a prompt's id, so the wire schema says `uuid`.
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
    api.components.schemas[OpenApiConfiguration.SUBMIT_ENTRY_REQUEST]
        ?.properties
        ?.get(PROMPT_ID_PROPERTY)
        ?.apply {
            format = "uuid"
            description =
                "The id of the prompt this entry answers, from today's `prompt` or any earlier one; optional. A value " +
                "that names no prompt is 422 VALIDATION_FAILED on this field. Never sent back, on any entry."
        }
}
