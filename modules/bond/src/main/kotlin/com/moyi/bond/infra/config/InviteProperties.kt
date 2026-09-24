package com.moyi.bond.infra.config

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
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
 * that ships.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.bond.invite")
internal data class InviteProperties(
    /** Absolute, `http` or `https`; the code is appended as a path segment. */
    @field:NotNull
    val linkBaseUrl: URI,
) {
    init {
        // Checked at binding, so a relative path or a `mailto:` in a
        // deployment's environment is a failed start rather than a link
        // nobody can open.
        require(linkBaseUrl.isAbsolute && linkBaseUrl.scheme in WEB_SCHEMES) {
            "moyi.bond.invite.link-base-url must be an absolute http(s) URL, e.g. https://example.com/i"
        }
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
    }
}
