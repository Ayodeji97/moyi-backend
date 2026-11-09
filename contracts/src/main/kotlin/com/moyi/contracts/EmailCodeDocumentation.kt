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
