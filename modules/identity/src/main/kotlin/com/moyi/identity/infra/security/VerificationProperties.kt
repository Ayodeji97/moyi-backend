package com.moyi.identity.infra.security

import org.springframework.boot.context.properties.ConfigurationProperties
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
 * committed to a repository, or a `localhost` that ships. Nor does
 * `application.yml` carry one: it did, `https://moyi.com/…`, a domain the
 * project does not own, which every profile inherited, so this guard never
 * fired and a deploy that forgot the variable would have emailed live
 * tokens to a stranger's server (ADR-0018, amended 2026-10-22). The values
 * come from the environment; the `local` profile and the test contexts
 * carry their own.
 */
@ConfigurationProperties(prefix = "moyi.identity.verification")
internal class VerificationProperties(
    linkBaseUrl: URI?,
    resetLinkBaseUrl: URI?,
) {
    /** Where the email-verification link lands (FR-002). Absolute, `http` or `https`; the token is appended as `?token=`. */
    val linkBaseUrl: URI = requireWebUrl(linkBaseUrl, "moyi.identity.verification.link-base-url", "https://example.com/verify")

    /**
     * Where the password-reset link lands (FR-004). A different page from
     * verification, necessarily: the first version of the reset flow reused
     * the verify page, which would have `POST`ed a reset token to
     * `/verify-email` and been told it was not recognised. Found in review,
     * because no test read the reset link out of the email.
     */
    val resetLinkBaseUrl: URI =
        requireWebUrl(resetLinkBaseUrl, "moyi.identity.verification.reset-link-base-url", "https://example.com/reset-password")

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")

        /**
         * Checked at binding, so a missing value, a relative path or a
         * `mailto:` in a deployment's environment is a failed start rather
         * than a link nobody can open (the shape check was raised by the
         * automated review on the PR that added this class).
         *
         * The parameters are nullable so that a missing value reaches this
         * message. With a non-null `URI` and `@field:NotNull`, as this class
         * was until 2026-10-22, Kotlin's own null check threw first —
         * "Parameter specified as non-null is null: …, parameter
         * linkBaseUrl" — and the `@NotNull` never ran. Only the base URL is
         * named here, never a token.
         */
        fun requireWebUrl(
            value: URI?,
            property: String,
            example: String,
        ): URI {
            requireNotNull(value) { "$property is required (from the environment), e.g. $example" }
            require(value.isAbsolute && value.scheme in WEB_SCHEMES) { "$property must be an absolute http(s) URL, e.g. $example" }
            return value
        }
    }
}
