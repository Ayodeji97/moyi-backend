package com.moyi.identity.infra.security

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI

/**
 * Where the emailed links point.
 *
 * **Not at this API.** A link in an email lands on a page on the marketing
 * site — the same arrangement as `/i/{code}` for invites (doc 02 §J1) — which
 * hands the token to the app, and the app `POST`s it to `/auth/verify-email`
 * or `/auth/reset-password`. The obvious alternative, a `GET …?token=` on the
 * API that acts directly, has a failure mode that is invisible in development
 * and universal in production: corporate mail gateways and link-scanning
 * clients *fetch every link* in an email before the person sees it, and a
 * single-use token consumed by a scanner is a link that says "expired" the
 * moment a human clicks it. A `GET` must never spend a token.
 *
 * Required, with no defaults. A default here would be a production URL
 * committed to a repository, or a `localhost` that ships. `application.yml`
 * carries the real values; the module's test context carries its own.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.identity.verification")
internal data class VerificationProperties(
    /** Where the email-verification link lands (FR-002). Absolute, `http` or `https`; the token is appended as `?token=`. */
    @field:NotNull
    val linkBaseUrl: URI,
    /**
     * Where the password-reset link lands (FR-004). A different page from
     * verification, necessarily: the first version of the reset flow reused
     * the verify page, which would have `POST`ed a reset token to
     * `/verify-email` and been told it was not recognised. Found in review,
     * because no test read the reset link out of the email.
     */
    @field:NotNull
    val resetLinkBaseUrl: URI,
) {
    init {
        // Checked at binding, so a relative path or a `mailto:` in a deployment's
        // environment is a failed start rather than a link nobody can open.
        // Raised by the automated review on the PR that added this class.
        require(linkBaseUrl.isWebUrl()) {
            "moyi.identity.verification.link-base-url must be an absolute http(s) URL, e.g. https://example.com/verify"
        }
        require(resetLinkBaseUrl.isWebUrl()) {
            "moyi.identity.verification.reset-link-base-url must be an absolute http(s) URL, e.g. https://example.com/reset-password"
        }
    }

    private fun URI.isWebUrl(): Boolean = isAbsolute && scheme in WEB_SCHEMES

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
    }
}
