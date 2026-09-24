package com.moyi.common.security

import com.moyi.common.core.IdGenerator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Mints access tokens (doc 09 §3): RS256, fifteen minutes, and the seven
 * claims the document lists — `sub`, `jti`, `iat`, `exp`, `iss`, `aud`,
 * `scope` — plus, since FR-007, `sid`: the refresh-token family the token
 * was minted for, so the sessions list can say which one is *this* one
 * (ADR-0025). Another opaque UUID, no more personal than `jti`. **No PII in
 * the token**: the subject is a UUID, and the token carries no email, no
 * name, no status. A JWT is base64, not encrypted, and every client and
 * proxy on the path can read it.
 *
 * `jti` comes from the injected [IdGenerator] so a test can predict it and
 * so the generator is the one place randomness for identifiers comes from.
 * It is not used for anything yet; doc 09 keeps a per-`jti` denylist as a
 * future single-session revocation, and a claim that is not there cannot be
 * denylisted later.
 *
 * The signing itself is Spring Security's [JwtEncoder] over Nimbus — doc 25
 * D5, no hand-written crypto.
 */
@Component
class AccessTokenIssuer(
    private val encoder: JwtEncoder,
    private val keys: SigningKeys,
    private val properties: JwtProperties,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun issue(
        userId: UUID,
        sessionId: UUID? = null,
    ): IssuedAccessToken {
        val now = clock.instant()
        val expiresAt = now.plus(properties.accessTokenTtl)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.kid).build()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(properties.issuer)
                .subject(userId.toString())
                .audience(listOf(properties.audience))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(ids.opaque().toString())
                .claim(SCOPE_CLAIM, DEFAULT_SCOPE)
                .apply { sessionId?.let { claim(SESSION_CLAIM, it.toString()) } }
                .build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedAccessToken(token, expiresAt, properties.accessTokenTtl.seconds)
    }

    companion object {
        const val SCOPE_CLAIM = "scope"

        /** The refresh-token family this access token belongs to (FR-007). Absent on a token minted outside a session. */
        const val SESSION_CLAIM = "sid"

        /** One scope for one client. Roles for the admin console (doc 09 §4) widen this when they exist. */
        const val DEFAULT_SCOPE = "user"
    }
}

/**
 * A minted token and when it stops working. [expiresIn] is seconds, the
 * shape doc 06 §3.1's login response returns.
 *
 * `toString` is redacted: this is the credential.
 */
class IssuedAccessToken(
    val token: String,
    val expiresAt: Instant,
    val expiresIn: Long,
) {
    override fun toString(): String = "IssuedAccessToken(redacted, expiresAt=$expiresAt)"
}
