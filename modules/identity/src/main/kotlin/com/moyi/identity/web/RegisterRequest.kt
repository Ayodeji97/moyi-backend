package com.moyi.identity.web

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.User
import com.moyi.identity.service.RegistrationCommand
import jakarta.validation.constraints.AssertTrue
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
 * **The email and password constraints delegate to the domain** rather than
 * restating its rules; see [ValidEmail] for the three confirmed 500s that
 * restating them produced. `displayName`, `locale` and `acceptedTermsVersion`
 * keep ordinary annotations because their domain rules are `isNotBlank` and
 * nothing more — there is no second definition to drift from.
 */
internal data class RegisterRequest(
    @field:NotBlank
    @field:ValidEmail
    val email: String,
    @field:NotBlank
    @field:ValidPassword
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
     * Builds the command, constructing the domain's value types here at the
     * edge rather than in the service.
     *
     * Two things follow from that. The service receives types that cannot be
     * invalid, so it has no failure mode left to handle; and the plaintext
     * password stops existing as a bare `String` below this class, which is
     * what [toString] below is about.
     *
     * The constructors cannot throw here: the same factories ran during
     * validation, and a request that failed them never reached the handler.
     */
    fun toCommand() =
        RegistrationCommand(
            // A surrounding space in an address is a typing accident. The
            // password is NOT trimmed — ADR-0012 accepts spaces in one, and
            // removing them would make the password the user typed
            // unenterable.
            email = Email(email.trim()),
            password = Password.of(password),
            displayName = displayName.trim(),
            locale = locale,
            acceptedTermsVersion = acceptedTermsVersion.trim(),
        )

    /**
     * **Redacted, because the generated one is not.**
     *
     * A Kotlin `data class` prints every property, and this is the object
     * that holds the plaintext password. Spring prints it in two places
     * without anyone asking: `AbstractMessageConverterMethodArgumentResolver`
     * logs the deserialised body at `DEBUG` for `org.springframework.web`,
     * and `MethodArgumentNotValidException.getMessage()` embeds each
     * `FieldError`, whose `toString()` contains `rejected value [...]` — the
     * password itself when it fails validation, logged untruncated by
     * `AbstractHandlerExceptionResolver`. Neither needs an attacker; both
     * need one operator turning on `--debug`, at which point every
     * registration writes a password next to its email, in a log that gets
     * shipped somewhere and kept.
     *
     * `PasswordHash`'s KDoc already names this exact mechanism. The two types
     * carrying the *plaintext* were the ones that missed it.
     */
    override fun toString(): String = "RegisterRequest(email=$email, displayName=$displayName, locale=$locale)"

    private companion object {
        const val CONSENT_VERSION_MAX_LENGTH = 40

        /** Deliberately loose: two or three letters, optionally a region. Not the full BCP 47 grammar. */
        const val LANGUAGE_TAG = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$"
    }
}
