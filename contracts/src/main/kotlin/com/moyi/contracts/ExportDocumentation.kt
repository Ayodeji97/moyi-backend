package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val REQUEST_OPERATION = "requestDataExport"
private const val GET_OPERATION = "getDataExport"
private const val LATEST_OPERATION = "getLatestDataExport"
private const val RESPONSE = "DataExportResponse"

/**
 * `POST /me/export`, `GET /me/export` and `GET /me/export/{exportId}` (FR-009, FR-094, ADR-0059)
 * say what their schema cannot: that the archive is built later, what is in it, when a field is
 * present, and the 24 hours.
 */
internal fun documentDataExport(api: OpenAPI) {
    api.paths.values.flatMap { it.readOperations() }.forEach { operation ->
        when (operation.operationId) {
            REQUEST_OPERATION -> operation.description = REQUEST_DESCRIPTION
            GET_OPERATION -> operation.description = GET_DESCRIPTION
            LATEST_OPERATION -> operation.description = LATEST_DESCRIPTION
        }
    }
    val response = api.components.schemas[RESPONSE] ?: return
    mapOf(
        "status" to
            "`PREPARING` until it is built (usually within minutes), then `READY` for 24 hours, then `EXPIRED`; " +
            "`FAILED` if it could not be built after three tries (ask again).",
        "readyAt" to "When it became `READY`; null while `PREPARING`, and for a `FAILED` one.",
        "expiresAt" to "Only while `READY`: when the download URL stops working and the archive is deleted (`readyAt` + 24 h).",
        "sizeBytes" to "Only while `READY`: the zip's size in bytes.",
        "downloadUrl" to
            "Only while `READY`: a signed URL that downloads the zip without a bearer, good until `expiresAt` " +
            "(FR-094). Treat it as the archive itself: anyone holding it can download it.",
    ).forEach { (name, text) -> response.properties?.get(name)?.description = text }
}

private const val CONTENTS =
    "The archive is a zip of `moyi-export.json` (the account and its consents; every bond the person is or was in, " +
        "with every day of its archive exactly as the app shows it to them, their partner's entries only once revealed " +
        "to them, and every entry withdrawn on a block or a leave, theirs or their partner's, as a blank placeholder; " +
        "their bookmarks and the reactions on each entry; their support messages; and any recorded administrator " +
        "access), the photos and voice notes it names under `media/`, and a `README.txt`."

private const val REQUEST_DESCRIPTION =
    "Asks for a copy of everything the signed-in person is entitled to (FR-009). Nothing is built when this " +
        "returns: `202` answers `PREPARING` with the export's `id`, a job builds it, usually within minutes, and a " +
        "confirmed address is emailed when it is `READY` (the email carries no link: the person downloads it in the " +
        "app). Asking again while one is `PREPARING` is the same export, `202` with the same `id`; asking after it is " +
        "`READY`, `EXPIRED` or `FAILED` starts a new one. Allowed whatever state the person's bonds are in, ended or " +
        "left included (BR-9). " + CONTENTS + " Three new exports a day per account; a repeat while one is `PREPARING` spends none: the " +
        "fourth new one is `429` with `Retry-After`."

private const val GET_DESCRIPTION =
    "One of the caller's exports, as it is now. `downloadUrl`, `expiresAt` and `sizeBytes` are present only while " +
        "it is `READY`. An export past its 24 hours is `EXPIRED` from that moment, and so is a `READY` one when " +
        "entries are withdrawn in one of the person's bonds (a block or a leave that withdraws): its archive held " +
        "words that are gone for both people, so it is deleted, and the person asks again. An `exportId` that is someone " +
        "else's and one that does not exist are the same `404 NOT_FOUND`."

private const val LATEST_DESCRIPTION =
    "The caller's most recently requested export, as `GET /me/export/{exportId}` answers it, so that a client can " +
        "show its state after a restart without keeping the id. `404 NOT_FOUND` if the caller has never asked for one."
