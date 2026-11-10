package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val SUPPORT_OPERATION = "sendSupportMessage"
private const val SUPPORT_REQUEST = "SupportMessageRequest"
private const val SUPPORT_RESPONSE = "SupportMessageResponse"

/** Mirror `support.domain.SupportSubject.MAX_CHARACTERS` and `SupportBody.MAX_CHARACTERS`, internal to that module. */
private const val SUBJECT_MAX_CHARACTERS = 120
private const val BODY_MAX_CHARACTERS = 5000

/** Mirrors `support.domain.SupportReference.MAX_CHARACTERS`: 1 to 64 characters, each 0x21 to 0x7E. */
private const val REFERENCE_MAX_CHARACTERS = 64
private const val REFERENCE_PATTERN = "^[!-~]{1,64}$"

/**
 * `POST /support/messages` (FR-102, ADR-0057) says what its schema cannot: what happens to the
 * message, what the acknowledgement says, and the limits, which the domain enforces and springdoc
 * cannot read off a validator that asks it. `subject` and `body` are Kotlin `String?` only so that a
 * missing one is a `422` naming it; `null` is never accepted, so both are typed `string` and required.
 */
internal fun documentSupport(api: OpenAPI) {
    api.paths.values
        .flatMap { it.readOperations() }
        .filter { it.operationId == SUPPORT_OPERATION }
        .forEach { it.description = SUPPORT_DESCRIPTION }
    api.components.schemas[SUPPORT_RESPONSE]?.let { response ->
        response.properties.keys
            .filter { it !in response.required.orEmpty() }
            .forEach(response::addRequiredItem)
    }
    val request = api.components.schemas[SUPPORT_REQUEST] ?: return
    mapOf("subject" to SUBJECT_MAX_CHARACTERS, "body" to BODY_MAX_CHARACTERS).forEach { (name, max) ->
        request.properties?.get(name)?.let { field ->
            field.types = setOf("string")
            field.minLength = 1
            field.maxLength = max
            field.description =
                "At most $max characters (Unicode code points), not blank, U+0000 refused. Kept exactly as sent, " +
                "read by a person, never shown back and never repeated in the acknowledgement email."
        }
        if (name !in request.required.orEmpty()) request.addRequiredItem(name)
    }
    request.properties?.get("reference")?.let { field ->
        field.minLength = 1
        field.maxLength = REFERENCE_MAX_CHARACTERS
        field.pattern = REFERENCE_PATTERN
        field.description =
            "Optional: the reference of the error that led the person here, usually that error's `traceId`, when support " +
            "was opened from an error; absent or null otherwise. 1 to $REFERENCE_MAX_CHARACTERS visible ASCII characters " +
            "(0x21 to 0x7E: no space, nothing past ASCII), else `422` naming `reference`. Stored with the ticket and shown " +
            "to the person answering it; never repeated in the acknowledgement email, and deleted with the words when the " +
            "ticket is closed."
    }
}

private const val SUPPORT_DESCRIPTION =
    "Writes to Moyi's support, as the signed-in account: the message becomes a ticket a person reads, and `201` " +
        "answers with its id and when it arrived. Once it is stored, an acknowledgement email is attempted once, to the " +
        "account's address if that address has been confirmed; it gives the id as a reference and never repeats the " +
        "subject or the body. It is best effort and not promised: it is not retried if the email provider fails. A " +
        "signed-out person cannot use this route. An `Idempotency-Key` is optional: repeating the request with the " +
        "same key is the same ticket, `201` again with `Idempotency-Replayed: true`, filed once and acknowledged at " +
        "most once; without a key, each request is a new ticket. A replay spends one of the five a day as well, so " +
        "a retry can be `429`: wait for `Retry-After`, then the same key returns the same ticket. A `subject` or " +
        "`body` that is absent, null, blank or too long is `422` naming the field; so is a `reference` that is empty, " +
        "too long or not visible ASCII. Five messages a day per account, " +
        "refused ones and replays included: the sixth is `429` with `Retry-After`."
