package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema

/**
 * A request field that is text on the server and one of an enum's names on the wire, and the response field that
 * is that enum (#139).
 *
 * The request takes text so that a wrong value is a `422` naming the field rather than a body Jackson cannot read
 * (`bond.web.ValidBondType`, `identity.web.ValidDevicePlatform`), and each of those constraints asks the domain's
 * enum whether the value is one of its names. So springdoc sees a bare string. The response that returns the same
 * value is the enum itself, which springdoc lists from the domain. The request is given that list: one statement of
 * the set, the domain enum's, read twice, as `ReactionRequest.type` already is from `EntryResponse.reaction`.
 * `OpenApiContractTest` holds each pair equal.
 */
private val REQUEST_ENUM_SOURCES =
    listOf(
        Triple("CreateBondRequest", "type", "BondResponse"),
        Triple("PatchBondRequest", "type", "BondResponse"),
        Triple("DeviceRequest", "platform", "DeviceResponse"),
    )

internal fun documentRequestEnums(api: OpenAPI) {
    val schemas = api.components.schemas
    REQUEST_ENUM_SOURCES.forEach { (request, property, response) ->
        val names =
            schemas[response]
                ?.properties
                ?.get(property)
                ?.enum
                .orEmpty()
                .filterNotNull()

        @Suppress("UNCHECKED_CAST") // A schema's `enum` is typed by the schema; this one is read untyped, as reactions' is.
        val sent = schemas[request]?.properties?.get(property) as Schema<Any?>? ?: return@forEach
        // A field that may be null (the PATCH, where absence keeps the type) lists null among its values, or a strict
        // client refuses its own `null`. The pattern the platform used to publish is the enum's names again: dropped.
        sent.enum = if (NULL in sent.types.orEmpty()) names + null else names
        sent.pattern = null
    }
}

/**
 * `RegisterRequest.over18` is required (#138). FR-011: there is no account without the confirmation. `false` is
 * read and refused (`422`, `ASSERT_TRUE`); a body without the field is not read at all (`400 MALFORMED_REQUEST`),
 * since it is a Kotlin `Boolean` with no default (`RegistrationEndpointTest` holds both). springdoc marks a field
 * required only from `@NotNull`, which a non-null Kotlin property has no use for, so the document did not say so.
 */
internal fun requireOver18(api: OpenAPI) {
    val register = api.components.schemas[REGISTER_REQUEST] ?: return
    if (register.properties?.containsKey(OVER_18) == true && OVER_18 !in register.required.orEmpty()) {
        register.addRequiredItem(OVER_18)
    }
    register.properties?.get(OVER_18)?.description =
        "The person confirms they are 18 or older (FR-011). Required, and only `true` is accepted: `false` is " +
        "`422 VALIDATION_FAILED`, and a body without the field is `400 MALFORMED_REQUEST`."
}

private const val REGISTER_REQUEST = "RegisterRequest"
private const val OVER_18 = "over18"
private const val NULL = "null"
