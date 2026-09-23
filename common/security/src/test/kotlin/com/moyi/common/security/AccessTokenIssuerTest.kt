package com.moyi.common.security

import com.moyi.common.testing.DeterministicIdGenerator
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class AccessTokenIssuerTest {
    private val now: Instant = Instant.parse("2026-09-23T12:00:00Z")
    private val keys = SigningKeys.from(JwtProperties(keySource = JwtProperties.KeySource.EPHEMERAL))
    private val properties = JwtProperties(keySource = JwtProperties.KeySource.EPHEMERAL)
    private val ids = DeterministicIdGenerator()
    private val issuer =
        AccessTokenIssuer(
            encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(keys.rsaKey))),
            keys = keys,
            properties = properties,
            ids = ids,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    // The decoder's clock is the same frozen one, or every token it sees is
    // fifteen minutes past its expiry by the time the test runs.
    private val decoder =
        NimbusJwtDecoder
            .withPublicKey(keys.publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build()
            .apply { setJwtValidator(JwtTimestampValidator().apply { setClock(Clock.fixed(now, ZoneOffset.UTC)) }) }

    @Test
    fun `carries exactly the seven claims doc 09 lists, and nothing that identifies a person`() {
        val userId = UUID.randomUUID()

        val issued = issuer.issue(userId)
        val jwt = decoder.decode(issued.token)

        jwt.claims.keys shouldContainExactlyInAnyOrder listOf("sub", "jti", "iat", "exp", "iss", "aud", "scope")
        jwt.subject shouldBe userId.toString()
        jwt.issuer.toString() shouldBe "https://api.moyi.app"
        jwt.audience shouldBe listOf("moyi-app")
        jwt.issuedAt shouldBe now
        jwt.expiresAt shouldBe now.plus(Duration.ofMinutes(15))
        jwt.id shouldBe ids.issued.single().toString()
        jwt.getClaimAsString("scope") shouldBe "user"
    }

    @Test
    fun `is RS256 and names its key`() {
        val jwt = decoder.decode(issuer.issue(UUID.randomUUID()).token)

        jwt.headers["alg"] shouldBe "RS256"
        jwt.headers["kid"] shouldBe keys.kid
    }

    @Test
    fun `reports how long the token lives, in seconds, as the login response will`() {
        val issued = issuer.issue(UUID.randomUUID())

        issued.expiresIn shouldBe 900
        issued.expiresAt shouldBe now.plusSeconds(900)
        // The credential does not print itself.
        issued.toString() shouldNotContain issued.token
    }
}
