package com.moyi.identity.infra.security

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI

/**
 * Where a verification link points.
 *
 * **Not at this API.** The link in the email lands on a page on the
 * marketing site — the same arrangement as `/i/{code}` for invites (doc 02
 * §J1) — which hands the token to the app, and the app `POST`s it to
 * `/auth/verify-email`. The obvious alternative, a `GET /verify?token=…`
 * on the API that verifies directly, has a failure mode that is invisible in
 * development and universal in production: corporate mail gateways and
 * link-scanning clients *fetch every link* in an email before the person
 * sees it, and a single-use token consumed by a scanner is a link that says
 * "expired" the moment a human clicks it. A `GET` must never spend a token.
 *
 * Required, with no default. A default here would be a production URL
 * committed to a repository, or a `localhost` that ships. `application.yml`
 * carries the real value; the module's test context carries its own.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.identity.verification")
internal data class VerificationProperties(
    /** Absolute. The token is appended as `?token=`. */
    @field:NotNull
    val linkBaseUrl: URI,
)
