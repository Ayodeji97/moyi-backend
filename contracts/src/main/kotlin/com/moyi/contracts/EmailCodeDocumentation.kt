package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

/** The two request schemas that take the link's token or the email's code (#135): see [documentEmailCodes]. */
private val CREDENTIAL_REQUESTS = listOf("VerifyEmailRequest", "ResetPasswordRequest")

/**
 * States the rule `identity.web.PresentedCredential` enforces (#135, ADR-0018 amended
 * 2026-11-08): a body carries the token from the link, or the address and the code from the
 * same email, and not both. springdoc reads none of a custom constraint, so the rule is said
 * here in words; the server's check is the authority.
 */
internal fun documentEmailCodes(api: OpenAPI) {
    CREDENTIAL_REQUESTS.forEach { name ->
        val schema = api.components.schemas[name] ?: return@forEach
        schema.description =
            "Exactly one of: `token`, the value after `?token=` in the emailed link; or `email` and `code`, the " +
            "address the email was sent to and the six digits in it. Anything else is `422 VALIDATION_FAILED`."
        schema.properties?.get("token")?.description = "The token from the emailed link. Send it alone, without `email` or `code`."
        schema.properties?.get("email")?.description =
            "The address the email was sent to, as typed (case and surrounding space do not matter). Send with `code`."
        schema.properties?.get("code")?.description =
            "The six ASCII digits from the email (surrounding space is trimmed). Good for 15 minutes, until 5 wrong " +
            "tries, and until a newer email is sent; spent with the link beside it. Another shape is " +
            "`422 VALIDATION_FAILED` with the field's code `VALID_VERIFICATION_CODE`, and counts as no try."
    }
}

private const val CHECK_OPERATION = "checkResetCode"
private const val CHECK_REQUEST = "ResetCodeCheckRequest"

/**
 * `POST /auth/reset-password/check` (#169, ADR-0022 amended 2026-11-10): what a check is, and
 * what it is not. A right code is answered and left live; the reset still takes the code.
 */
internal fun documentResetCodeCheck(api: OpenAPI) {
    api.paths.values
        .flatMap { it.readOperations() }
        .filter { it.operationId == CHECK_OPERATION }
        .forEach { it.description = CHECK_DESCRIPTION }
    val schema = api.components.schemas[CHECK_REQUEST] ?: return
    schema.description = "The address the reset email was sent to, and the six digits in it. No `token`: a link needs no check."
    schema.properties?.get("email")?.description =
        "The address the reset email was sent to, as typed (case and surrounding space do not matter)."
    schema.properties?.get("code")?.description =
        "The six ASCII digits from the reset email (surrounding space is trimmed). Another shape is " +
        "`422 VALIDATION_FAILED` with the field's code `VALID_VERIFICATION_CODE`, and counts as no try."
}

private const val CHECK_DESCRIPTION =
    "Says whether a typed password-reset code is right, so the app can say \"wrong code\" before the new password " +
        "is typed. A check, not a grant: `204` means the code is right now, and nothing about it changes; the reset " +
        "itself (`POST /auth/reset-password` with `email`, `code` and `password`) still takes the same code, and may " +
        "still refuse it if it expires (15 minutes), is spent by the link, or is replaced by a newer email in " +
        "between. A wrong check is a wrong try at that code: checks and resets share its 5 tries, and its ten typed " +
        "codes a day per address, so checking first gains a guesser nothing. Every refusal is the reset's one " +
        "answer, `422 PASSWORD_RESET_TOKEN_INVALID`."
