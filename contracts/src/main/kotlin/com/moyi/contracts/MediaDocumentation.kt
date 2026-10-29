package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val ENTRY_RESPONSE = "EntryResponse"
private const val ENTRY_IMAGE_RESPONSE = "EntryImageResponse"
private const val ENTRY_VOICE_RESPONSE = "EntryVoiceResponse"
private const val IMAGE_PROPERTY = "image"
private const val VOICE_PROPERTY = "voice"

/**
 * An entry's photo (Phase 4 D2, ADR-0048), `{id, status, url?, width?,
 * height?}`, and its voice note (D3, ADR-0049), `{id, status, url?,
 * durationMs?}`: every field always sent, as an entry's are, so that a client
 * can tell "not shown yet" (`"url": null`, `"status": "PROCESSING"`) from a
 * field the server left out. And the entry's own `image` and `voice` say what
 * `null` means there. `OpenApiContractTest` holds both.
 */
internal fun documentEntryImage(api: OpenAPI) {
    allRequired(api, ENTRY_IMAGE_RESPONSE, ENTRY_IMAGE_DESCRIPTION)
    allRequired(api, ENTRY_VOICE_RESPONSE, ENTRY_VOICE_DESCRIPTION)
    val entry =
        api.components.schemas[ENTRY_RESPONSE]
            ?.allOf
            ?.lastOrNull()
            ?.properties
    entry?.get(IMAGE_PROPERTY)?.description = IMAGE_PROPERTY_DESCRIPTION
    entry?.get(VOICE_PROPERTY)?.description = VOICE_PROPERTY_DESCRIPTION
}

private fun allRequired(
    api: OpenAPI,
    name: String,
    description: String,
) {
    api.components.schemas[name]?.let { schema ->
        schema.properties
            .orEmpty()
            .keys
            .filter { it !in schema.required.orEmpty() }
            .forEach(schema::addRequiredItem)
        schema.description = description
    }
}

private const val ENTRY_IMAGE_DESCRIPTION =
    "An entry's photo as the caller is shown it. url, width and height are present exactly when status is READY: " +
        "to its author once it is ready, to the other member only once it has also passed moderation; until then it is " +
        "PROCESSING to them, whatever the reason. The url is a signed GET, valid for at least 24 hours, ending on the hour " +
        "and the same all hour, so it may be cached by; it is a credential for the photo."

private const val ENTRY_VOICE_DESCRIPTION =
    "An entry's voice note as the caller is played it. url and durationMs are present exactly when status is READY, " +
        "to its author and to the other member alike: a voice note waits on no moderation. Until then it is PROCESSING, " +
        "whatever the reason. The url is a signed GET of an Ogg Opus file (mono, 24 kbit/s), valid for at least 24 hours, " +
        "ending on the hour and the same all hour, so it may be cached by; it is a credential for the voice note. " +
        "durationMs is the length of what was transcoded, at most 61 seconds and a few milliseconds."

private const val IMAGE_PROPERTY_DESCRIPTION =
    "The entry's photo, or null when it has none. Always null on an erased entry; a locked entry has no such field."

private const val VOICE_PROPERTY_DESCRIPTION =
    "The entry's voice note, or null when it has none. Always null on an erased entry; a locked entry has no such field."
