package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

/** `RegisterRequest`'s schema name and its `displayName` property: see [documentDisplayName]. */
private const val REGISTER_REQUEST = "RegisterRequest"
private const val DISPLAY_NAME_PROPERTY = "displayName"

/**
 * States `RegisterRequest.displayName`'s character rule (ADR-0015, amended 2026-10-28),
 * which `identity.web.ValidDisplayName` enforces by asking `identity.domain.DisplayNames`.
 * springdoc can read `@NotBlank` and `@Size` off the field, and nothing of a custom
 * constraint, so the rule is said here in words; the server's check is the authority.
 */
internal fun documentDisplayName(api: OpenAPI) {
    api.components.schemas[REGISTER_REQUEST]
        ?.properties
        ?.get(DISPLAY_NAME_PROPERTY)
        ?.description =
        "The name the other member sees. Not blank, and at most 80 UTF-16 characters as sent. Surrounding " +
        "whitespace is trimmed off, and what is left may not contain a control character (Unicode category " +
        "Cc: tab and newline included, a name is one line) or a format character (Cf: the bidirectional controls such " +
        "as U+202E, the zero-width space, word joiner, byte-order mark, soft hyphen and tag characters), " +
        "except U+200C ZERO WIDTH NON-JOINER and U+200D ZERO WIDTH JOINER, which some scripts and emoji " +
        "sequences need. A refused name is `422 VALIDATION_FAILED` with the field's code `VALID_DISPLAY_NAME`. " +
        "Stored as sent, trimmed and not normalised."
}
