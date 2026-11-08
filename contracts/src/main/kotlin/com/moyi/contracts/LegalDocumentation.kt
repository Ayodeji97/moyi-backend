package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse

/** `identity.web.LegalController`'s read and `MeController`'s re-consent, by operation id. */
internal const val LEGAL_DOCUMENTS_OPERATION = "legalDocuments"
private const val ACCEPT_LEGAL_OPERATION = "acceptLegalDocuments"

/**
 * `GET /meta/legal` and `POST /me/legal-consent` (FR-011, FR-103, ADR-0056).
 *
 * The read is a conditional read like the archive's two (`documentRevalidation`), but a
 * **public** one: the same for everyone, sent with `Cache-Control: public, no-cache`, so its
 * tag is described here and not with theirs, which says "private" and "not the other
 * member's". springdoc sees neither the tag, the `If-None-Match` nor the `304`, all of
 * which come from `common.web.Representations` and Spring MVC, so they are stated here.
 */
internal fun documentLegal(api: OpenAPI) {
    val operations = api.paths.values.flatMap { it.readOperations() }
    operations.filter { it.operationId == LEGAL_DOCUMENTS_OPERATION }.forEach { operation ->
        operation.description = LEGAL_DESCRIPTION
        operation.addParametersItem(
            Parameter()
                .`in`("header")
                .name("If-None-Match")
                .required(false)
                .description(
                    "The `ETag` of an earlier answer the client still holds. If it is still current the answer is " +
                        "`304` with no body; any other value is ignored and the whole response is sent.",
                ).schema(StringSchema()),
        )
        operation.responses["200"]?.addHeaderObject("ETag", tag())
        // What is sent is application/json whatever was asked for (RepresentationConverter).
        operation.responses["200"]
            ?.content
            ?.keys
            ?.retainAll(setOf("application/json"))
        operation.responses.addApiResponse(
            "304",
            ApiResponse()
                .description("Not Modified: the documents are still those the client holds under that tag. No body.")
                .addHeaderObject("ETag", tag()),
        )
    }
    operations.filter { it.operationId == ACCEPT_LEGAL_OPERATION }.forEach { it.description = ACCEPT_DESCRIPTION }
}

private fun tag(): Header =
    Header()
        .description(
            "Strong validator of this response body (RFC 9110 §8.8.3). Opaque: keep the whole value and send it back " +
                "as `If-None-Match`. The same for every caller; sent with `Cache-Control: public, no-cache`.",
        ).schema(StringSchema())

private const val LEGAL_DESCRIPTION =
    "The current Terms of Service, Privacy Policy and data-processing summary: each a `version` and the absolute " +
        "`url` it is read at. Public: no token is needed, because sign-up shows them before there is an account. " +
        "A `version` is an opaque label (letters, digits, `.`, `_`, `-`; at most 40): compare it with `GET /me`'s " +
        "`legalAccepted`, never parse it. Where the two differ, ask the person to accept again and send " +
        "`POST /me/legal-consent`. The data-processing summary is shown, never accepted on its own. Registration " +
        "records the Terms and Privacy versions this answer names, whatever the request says."

private const val ACCEPT_DESCRIPTION =
    "The caller accepts the current Terms of Service and Privacy Policy, the versions `GET /meta/legal` names. No " +
        "body, and none is read: the versions recorded are the server's. `204` whether or not anything was new; a " +
        "version accepted before keeps the moment it was first accepted. Afterwards `GET /me`'s `legalAccepted` " +
        "names the current versions."
