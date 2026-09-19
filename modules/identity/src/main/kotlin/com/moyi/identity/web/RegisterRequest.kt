package com.moyi.identity.web

import com.moyi.identity.domain.Password
import com.moyi.identity.domain.User
import com.moyi.identity.service.RegistrationCommand
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * The wire format of `POST /api/v1/auth/register` (doc 06 §3.1).
 *
 * A separate type from [RegistrationCommand] rather than the same object
 * passed down: the wire format is a contract with clients and the command is
 * a contract with the service, and letting one type be both means a rename
 * for a client's benefit silently rewrites the service's signature. It is
 * also what keeps Bean Validation annotations — an HTTP-layer concern — out
 * of the layers below.
 *
 * The bounds here repeat the domain's. That is not duplication for its own
 * sake: the domain's `require` throws, which becomes a 500, while these
 * produce the field-level 422 a form can actually render. The domain keeps
 * its checks because it must hold for every caller, including ones that never
 * came through HTTP.
 */
internal data class RegisterRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = EMAIL_MAX_LENGTH)
    val email: String,
    @field:NotBlank
    @field:Size(min = Password.MIN_LENGTH, max = Password.MAX_LENGTH)
    val password: String,
    @field:NotBlank
    @field:Size(max = User.MAX_DISPLAY_NAME_LENGTH)
    val displayName: String,
    /** BCP 47 language tag. Defaulted rather than required — the client sends the device's. */
    @field:NotBlank
    @field:Pattern(regexp = LANGUAGE_TAG, message = "must be a language tag such as en or en-GB")
    val locale: String = "en",
    @field:NotBlank
    @field:Size(max = CONSENT_VERSION_MAX_LENGTH)
    val acceptedTermsVersion: String,
    /** FR-011. `false` is a validation failure, not a silent skip — there is no account without it. */
    @field:AssertTrue(message = "must be confirmed")
    val over18: Boolean,
) {
    /**
     * FR-001's third limit, which `@Size` cannot express: `@Size` counts
     * characters and this counts bytes. 128 characters of four-byte code
     * points is a 512-byte input, so without this the character bound is not
     * the bound on work that it appears to be.
     *
     * Bean Validation reports this against the property name rather than
     * `password`, which is a small cost for not writing a custom constraint
     * annotation to say one line.
     */
    @get:AssertTrue(message = "password must be at most ${Password.MAX_OCTETS} bytes")
    val isPasswordWithinByteLimit: Boolean
        get() = password.toByteArray(Charsets.UTF_8).size <= Password.MAX_OCTETS

    fun toCommand() =
        RegistrationCommand(
            // Trimmed here, at the edge, because a trailing space in an email
            // is a typing accident rather than an address. The password is
            // NOT trimmed: whitespace is a legitimate character in one
            // (ADR-0012 accepts spaces) and silently removing it would make
            // the password the user typed unenterable.
            email = email.trim(),
            password = password,
            displayName = displayName.trim(),
            locale = locale,
            acceptedTermsVersion = acceptedTermsVersion.trim(),
        )

    private companion object {
        /** RFC 5321 §4.5.3.1.3, the same bound the `users.email` column carries. */
        const val EMAIL_MAX_LENGTH = 254
        const val CONSENT_VERSION_MAX_LENGTH = 40

        /** Deliberately loose: two or three letters, optionally a region. Not the full BCP 47 grammar. */
        const val LANGUAGE_TAG = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$"
    }
}
