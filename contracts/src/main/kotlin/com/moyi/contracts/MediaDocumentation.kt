package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val ENTRY_RESPONSE = "EntryResponse"
private const val ENTRY_IMAGE_RESPONSE = "EntryImageResponse"
private const val IMAGE_PROPERTY = "image"

/**
 * An entry's photo (Phase 4 D2, ADR-0048), `{id, status, url?, width?,
 * height?}`: every field always sent, as an entry's are, so that a client can
 * tell "not shown yet" (`"url": null`, `"status": "PROCESSING"`) from a field
 * the server left out. And the entry's own `image` says what `null` means
 * there. `OpenApiContractTest` holds both.
 */
internal fun documentEntryImage(api: OpenAPI) {
    api.components.schemas[ENTRY_IMAGE_RESPONSE]?.let { image ->
        image.properties
            .orEmpty()
            .keys
            .filter { it !in image.required.orEmpty() }
            .forEach(image::addRequiredItem)
        image.description = ENTRY_IMAGE_DESCRIPTION
    }
    api.components.schemas[ENTRY_RESPONSE]
        ?.allOf
        ?.lastOrNull()
        ?.properties
        ?.get(IMAGE_PROPERTY)
        ?.description = IMAGE_PROPERTY_DESCRIPTION
}

private const val ENTRY_IMAGE_DESCRIPTION =
    "An entry's photo as the caller is shown it. url, width and height are present exactly when status is READY: " +
        "to its author once it is ready, to the other member only once it has also passed moderation; until then it is " +
        "PROCESSING to them, whatever the reason. The url is a signed GET, valid for at least 24 hours, ending on the hour " +
        "and the same all hour, so it may be cached by; it is a credential for the photo."

private const val IMAGE_PROPERTY_DESCRIPTION =
    "The entry's photo, or null when it has none. Always null on an erased entry; a locked entry has no such field."
