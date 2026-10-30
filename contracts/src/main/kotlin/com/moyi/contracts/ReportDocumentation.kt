package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema

private const val REPORT_OPERATION = "reportEntry"
private const val REPORT_REQUEST = "ReportRequest"
private const val REPORT_RESPONSE = "ReportResponse"
private const val REASON_PROPERTY = "reason"
private const val DETAIL_PROPERTY = "detail"

/** Mirrors `gratitude.domain.ReportDetail.MAX_CHARACTERS`, which is internal to that module, as the entry text's limits are. */
private const val REPORT_DETAIL_MAX_CHARACTERS = 500

/**
 * `POST /entries/{entryId}/report` (FR-100, ADR-0053) says what its schema
 * cannot: what a report does to the entry's media, what each side is shown,
 * and what each refusal means. And it states the set of reasons on the
 * request, where springdoc cannot: `ReportRequest.reason` is text (so that a
 * wrong value is a `422` naming the field), and is given the list springdoc
 * generates for `ReportResponse.reason`, which is the domain's enum. One
 * statement of the set, read twice, as the reaction's `type` is.
 */
internal fun documentReports(api: OpenAPI) {
    api.paths.values
        .flatMap { it.readOperations() }
        .filter { it.operationId == REPORT_OPERATION }
        .forEach { it.description = REPORT_DESCRIPTION }
    @Suppress("UNCHECKED_CAST") // A schema's `enum` is typed by the schema, and these are read as untyped.
    val told =
        api.components.schemas[REPORT_RESPONSE]
            ?.properties
            ?.get(REASON_PROPERTY) as Schema<Any?>? ?: return
    val reasons = told.enum.orEmpty().filterNotNull()
    api.components.schemas[REPORT_RESPONSE]?.let { response ->
        response.properties.keys
            .filter { it !in response.required.orEmpty() }
            .forEach(response::addRequiredItem)
    }
    val request = api.components.schemas[REPORT_REQUEST] ?: return
    @Suppress("UNCHECKED_CAST")
    (request.properties?.get(REASON_PROPERTY) as Schema<Any?>?)?.let { sent ->
        // A Kotlin `String?` so that an absent reason is a 422 naming it; `{"reason": null}` is never accepted.
        sent.types = setOf("string")
        sent.enum = reasons
        sent.description = REASON_DESCRIPTION
    }
    request.properties?.get(DETAIL_PROPERTY)?.let { detail ->
        detail.minLength = 1
        detail.maxLength = REPORT_DETAIL_MAX_CHARACTERS
        detail.description = DETAIL_DESCRIPTION
    }
}

private const val REPORT_DESCRIPTION =
    "Reports the caller's partner's entry to a person, who reviews it, with its photo and voice note. `201` with the " +
        "report's id. From that moment the entry's photo and voice note are held back from the caller pending review: " +
        "the caller is shown them as PROCESSING, as anything held back is; the author is shown them as NOT_SHARED, as " +
        "anything held back is, and is never told that a report was made, or by whom. The entry's words are not " +
        "hidden. Only the partner's entry the caller is shown, in full or as a tombstone, can be reported, on a bond " +
        "that has ended too. `409 REPORT_ON_OWN_ENTRY` is an entry the caller wrote. `404` is every entry the caller " +
        "was never shown, the same as for an id that does not exist. An `Idempotency-Key` is required: repeating the " +
        "request with the same key is the same report, `201` again with `Idempotency-Replayed: true`, and nothing " +
        "filed twice. A `reason` that is absent, null or not one of the listed names, exactly, is `422`, and so is a " +
        "`detail` that is blank or longer than $REPORT_DETAIL_MAX_CHARACTERS characters. A body is judged before the " +
        "entry is looked at. Ten reports a day per account."

private const val REASON_DESCRIPTION =
    "Why the entry is reported, by its exact name. MINOR_SEXUAL_CONTENT is reviewed first."

private const val DETAIL_DESCRIPTION =
    "Optional: anything the reviewer should know, in the reporter's words, at most $REPORT_DETAIL_MAX_CHARACTERS " +
        "characters (Unicode code points). Not blank, and never shown to the other member. Kept until the report is " +
        "decided, then deleted."
