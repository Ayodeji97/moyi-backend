package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import java.math.BigDecimal

private const val ENTRY_RESPONSE = "EntryResponse"
private const val ENTRY_IMAGE_RESPONSE = "EntryImageResponse"
private const val ENTRY_VOICE_RESPONSE = "EntryVoiceResponse"
private const val IMAGE_PROPERTY = "image"
private const val VOICE_PROPERTY = "voice"
private const val WAVEFORM_PROPERTY = "waveform"
private const val WAVEFORM_SLICES = 64
private const val WAVEFORM_MAX_LEVEL = 255

/**
 * An entry's photo (Phase 4 D2, ADR-0048), `{id, status, url?, width?,
 * height?, sensitive}`, and its voice note (D3, ADR-0049), `{id, status, url?,
 * durationMs?, sensitive}` (D4, ADR-0051, added `NOT_SHARED` and `sensitive`):
 * every field always sent, as an entry's are, so that a client can tell "not shown yet" (`"url": null`, `"status": "PROCESSING"`) from a
 * field the server left out. And the entry's own `image` and `voice` say what
 * `null` means there. `OpenApiContractTest` holds both.
 */
internal fun documentEntryImage(api: OpenAPI) {
    allRequired(api, ENTRY_IMAGE_RESPONSE, ENTRY_IMAGE_DESCRIPTION)
    allRequired(api, ENTRY_VOICE_RESPONSE, ENTRY_VOICE_DESCRIPTION)
    documentWaveform(api)
    val entry =
        api.components.schemas[ENTRY_RESPONSE]
            ?.allOf
            ?.lastOrNull()
            ?.properties
    entry?.get(IMAGE_PROPERTY)?.description = IMAGE_PROPERTY_DESCRIPTION
    entry?.get(VOICE_PROPERTY)?.description = VOICE_PROPERTY_DESCRIPTION
}

/** `waveform` (ADR-0061): exactly 64 integers, each 0 to 255, when it is not null. */
private fun documentWaveform(api: OpenAPI) {
    api.components.schemas[ENTRY_VOICE_RESPONSE]?.properties?.get(WAVEFORM_PROPERTY)?.let { waveform ->
        waveform.minItems = WAVEFORM_SLICES
        waveform.maxItems = WAVEFORM_SLICES
        waveform.items?.minimum = BigDecimal.ZERO
        waveform.items?.maximum = BigDecimal.valueOf(WAVEFORM_MAX_LEVEL.toLong())
        waveform.description = WAVEFORM_DESCRIPTION
    }
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
    "An entry's photo as the caller is shown it. url, width and height are present when status is READY: to its " +
        "author once it is ready, to the other member only once it has also passed moderation; until then it is " +
        "PROCESSING to them, whatever the reason. NOT_SHARED is its author's alone: the photo is held back, for review, " +
        "from the other member, who is shown PROCESSING and never told it was removed; url, width and height are " +
        "still given, unless it was blocked outright, when they are null. A photo held back before the entry was " +
        "sent is attached all the same and is NOT_SHARED from the entry's first answer, exactly as one held back " +
        "after: the outcome does not depend on when moderation ran. sensitive is true when the photo passed " +
        "moderation with adult nudity found: show it blurred, behind a tap to view; it is false otherwise, and " +
        "always false unless status is READY. The url is a signed GET, valid for at least 24 hours, ending on the " +
        "hour and the same all hour, so it may be cached by; it is a credential for the photo."

private const val ENTRY_VOICE_DESCRIPTION =
    "An entry's voice note as the caller is played it. url and durationMs are present when status is READY, " +
        "to its author once it is transcoded, to the other member only once it has passed moderation; until then it is " +
        "PROCESSING to them, whatever the reason. NOT_SHARED is its author's alone, as a photo's: held back from the " +
        "other member for review, whether before or after the entry was sent; url and durationMs are still given " +
        "unless it was blocked outright. sensitive " +
        "as a photo's. The url is a signed GET of an Ogg Opus file (mono, 24 kbit/s), " +
        "valid for at least 24 hours, ending on the hour and the same all hour, so it may be cached by; it is a " +
        "credential for the voice note. durationMs is the length of what was transcoded, at most 61.1 seconds."

private const val WAVEFORM_DESCRIPTION =
    "The voice note's loudness over time, to draw: 64 levels, each 0 to 255, one per equal slice of its length " +
        "in order, the RMS of that slice scaled so that the loudest is 255 (all 0 for a silent note). Measured by " +
        "the server from the note it keeps, never sent by a client. Given exactly when url is; null when url is, and " +
        "for a note processed before levels were measured, when the client draws its own stand-in."

private const val IMAGE_PROPERTY_DESCRIPTION =
    "The entry's photo, or null when it has none. Always null on an erased entry; a locked entry has no such field."

private const val VOICE_PROPERTY_DESCRIPTION =
    "The entry's voice note, or null when it has none. Always null on an erased entry; a locked entry has no such field."
