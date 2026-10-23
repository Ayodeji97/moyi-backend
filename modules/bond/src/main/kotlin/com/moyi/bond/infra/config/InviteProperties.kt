package com.moyi.bond.infra.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Where an invite link lands.
 *
 * **Not at this API.** `states.md` §2: the `/i/{code}` page is a static page
 * on the marketing site that stores the code and hands it to the app on first
 * launch, and it **performs no lookup** — resolving a code is authenticated
 * (T-06), because an unauthenticated resolve is an oracle that returns a real
 * person's name on a hit. The same arrangement as the verification and reset
 * links, and `VerificationProperties` carries the longer version of the
 * argument.
 *
 * Required, with no default, for the reason that class gives: a default here
 * would be either a production URL committed to a repository or a `localhost`
 * that ships. `application.yml` carries none either (ADR-0018, amended
 * 2026-10-22): it once said `https://moyi.com/i`, a domain the project does
 * not own, and every profile inherited it.
 */
@ConfigurationProperties(prefix = "moyi.bond.invite")
internal class InviteProperties(
    linkBaseUrl: URI?,
) {
    /**
     * Absolute, `http` or `https`; the code is appended as a path segment.
     *
     * Checked at binding, so a missing value, a relative path or a `mailto:`
     * in a deployment's environment is a failed start rather than a link
     * nobody can open. Nullable in the constructor so that a missing value
     * reaches this message rather than Kotlin's "Parameter specified as
     * non-null is null", which is what a non-null `URI` with `@field:NotNull`
     * gave until 2026-10-22: the `@NotNull` never ran.
     */
    val linkBaseUrl: URI =
        requireNotNull(linkBaseUrl) { "$PROPERTY is required (from the environment), e.g. $EXAMPLE" }.also {
            require(it.isAbsolute && it.scheme in WEB_SCHEMES) { "$PROPERTY must be an absolute http(s) URL, e.g. $EXAMPLE" }
        }

    private companion object {
        const val PROPERTY = "moyi.bond.invite.link-base-url"
        const val EXAMPLE = "https://example.com/i"
        val WEB_SCHEMES = setOf("http", "https")
    }
}
