package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val REQUEST_OPERATION = "requestAccountDeletion"
private const val CANCEL_OPERATION = "cancelAccountDeletion"
private const val REQUEST_BODY = "DeletionRequestBody"
private const val PROFILE = "ProfileResponse"

/**
 * `POST` and `DELETE /me/deletion-request` (FR-008, ADR-0058) say what their schemas cannot: the
 * two windows, what the job does after them, and the one `404`. `password` is a Kotlin `String?`
 * only so that a missing one is a `422` naming it; `null` is never accepted, so it is typed
 * `string` and required, as `SupportMessageRequest`'s fields are.
 */
internal fun documentAccountDeletion(api: OpenAPI) {
    api.paths.values.flatMap { it.readOperations() }.forEach { operation ->
        when (operation.operationId) {
            REQUEST_OPERATION -> operation.description = REQUEST_DESCRIPTION
            CANCEL_OPERATION -> operation.description = CANCEL_DESCRIPTION
        }
    }
    api.components.schemas[REQUEST_BODY]?.properties?.get("password")?.let { field ->
        field.types = setOf("string")
        field.minLength = 1
        field.description = "The account's current password, typed again. Absent, null or blank is `422`; wrong is `403`."
    }
    api.components.schemas[PROFILE]
        ?.properties
        ?.get("deletionRequest")
        ?.description =
        "The account deletion waiting to be carried out, with its dates, or `null` when none is. Read it on start: " +
        "a client reinstalled during the 14 days draws the pending state, and its cancel, from this."
}

private const val REQUEST_DESCRIPTION =
    "Asks for the signed-in account to be deleted (FR-008). Nothing is destroyed now: `202` answers with " +
        "`requestedAt`, `cancellableUntil` (14 days later) and `deleteBy` (30 days later, FR-008's bound). Until " +
        "`cancellableUntil` the person can still sign in and use the app, and `DELETE` calls it off; other sessions " +
        "are not ended. From `cancellableUntil` it cannot be cancelled, and within the hour the account is deleted: " +
        "the address and name are erased and the account can no longer sign in; every entry its owner wrote becomes " +
        "a blank placeholder in each shared archive, so the other person's days and streak stay as they were; their " +
        "bookmarks, reactions, photos and voice notes, devices and sessions are deleted, their open support messages " +
        "emptied, and every bond they are still in ends as if they had left it. Asking again while one is pending " +
        "is the same `202` with the same dates. The current `password` is required: wrong is `403 " +
        "INVALID_CREDENTIALS`, without `WWW-Authenticate` (the session is fine; do not refresh or sign out), " +
        "absent or blank is `422`. `401` here is only ever the access token refused. A confirmed address " +
        "is emailed once per request, saying when and how to cancel. Five an hour per account, wrong passwords " +
        "included: the sixth is `429` with `Retry-After`."

private const val CANCEL_DESCRIPTION =
    "Calls off the pending account deletion. `204`. `404 NOT_FOUND` when there is nothing to cancel, whether " +
        "nothing was requested, it was already cancelled, or `cancellableUntil` has passed: one answer for all, " +
        "because there is nothing the person can do about any of them."
