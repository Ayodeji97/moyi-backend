package com.moyi.common.security

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * How access tokens are signed, verified and how long they live (doc 09 §3).
 *
 * **The signing key fails closed.** [keySource] defaults to [KeySource.CONFIGURED],
 * which refuses to start unless both PEMs are present — the same posture as
 * the email provider (ADR-0017) and the breach corpus (ADR-0016): an
 * environment that forgot the key must not boot with a key it invented,
 * because every token it issued would die with the process and nothing would
 * say so until users were signed out at the next deploy. [KeySource.EPHEMERAL]
 * exists for exactly that trade-off where it is acceptable — a developer's
 * laptop and a test context — and has to be asked for by name.
 *
 * Issuer and audience are fixed strings, not URLs anything resolves. They are
 * the two claims that stop a token minted for one deployment being accepted
 * by another that happens to trust the same key.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.security.jwt")
data class JwtProperties(
    @field:NotBlank
    val issuer: String = DEFAULT_ISSUER,
    @field:NotBlank
    val audience: String = DEFAULT_AUDIENCE,
    /** FR-003 / doc 09 §3: fifteen minutes. Short, because revocation of an access token is best-effort by design. */
    @field:NotNull
    val accessTokenTtl: Duration = DEFAULT_ACCESS_TOKEN_TTL,
    @field:NotNull
    val keySource: KeySource = KeySource.CONFIGURED,
    /** PKCS#8, PEM-armoured (`-----BEGIN PRIVATE KEY-----`). From the environment, never a file in the repository. */
    val privateKeyPem: String? = null,
    /** X.509 SubjectPublicKeyInfo, PEM-armoured (`-----BEGIN PUBLIC KEY-----`). */
    val publicKeyPem: String? = null,
) {
    enum class KeySource {
        /** Both PEMs supplied. The only source production may use. */
        CONFIGURED,

        /**
         * A fresh 2048-bit RSA pair generated at startup. Tokens do not
         * survive a restart, and the application says so at WARN every time it
         * boots this way. For the `local` profile and test contexts only.
         */
        EPHEMERAL,
    }

    private companion object {
        const val DEFAULT_ISSUER = "https://api.moyi.app"
        const val DEFAULT_AUDIENCE = "moyi-app"
        val DEFAULT_ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
    }
}
