package com.moyi.common.security

import com.moyi.common.testing.RedisIntegrationTest
import com.nimbusds.jose.PlainHeader
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Doc 12 §3.3's auth suite, the resource-server half: every way a token can
 * be wrong is a 401 in the RFC 9457 shape, and the one way it can be right is
 * a 200 that knows who called.
 *
 * Bad tokens are built with Spring's encoder over a key of the test's
 * choosing, or as an unsigned Nimbus `PlainJWT`, rather than through
 * [AccessTokenIssuer] — the issuer cannot be asked to produce a token with no
 * signature or somebody else's key, which is the point of it.
 */
@SpringBootTest(
    classes = [SecurityTestApplication::class],
    properties = ["moyi.security.jwt.key-source=ephemeral"],
)
@AutoConfigureMockMvc
class SecurityFilterChainTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val issuer: AccessTokenIssuer,
    @Autowired private val keys: SigningKeys,
    @Autowired private val properties: JwtProperties,
    @Autowired private val revocations: InMemoryTokenRevocations,
) : RedisIntegrationTest() {
    private val userId: UUID = UUID.randomUUID()

    @AfterEach
    fun reset() = revocations.reset()

    @Test
    fun `a token from the issuer is accepted, and the controller learns who called`() {
        val response = whoami(issuer.issue(userId).token)

        response.status shouldBe 200
        response.contentAsString shouldContain "\"userId\":\"$userId\""
    }

    @Test
    fun `no token is 401 in the problem shape, with the challenge header`() {
        val response = mockMvc.get("/api/v1/probe/whoami").andReturn().response

        response.status shouldBe 401
        response.contentType!! shouldStartWith MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        response.contentAsString shouldContain "Sign in to continue."
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
    }

    @Test
    fun `garbage is 401 with an invalid_token challenge`() {
        val response = whoami("not.a.jwt")

        response.status shouldBe 401
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE)!! shouldStartWith "Bearer error=\"invalid_token\""
        response.contentAsString shouldNotContain "not.a.jwt"
    }

    @Test
    fun `an expired token is 401`() {
        // Signed with the real key, so only the timestamp is wrong. Two
        // minutes past, which is outside the sixty-second skew allowance.
        val issuedAt = Instant.now().minus(17, ChronoUnit.MINUTES)
        val token = signedWithRealKey(claims(iat = issuedAt, exp = issuedAt.plus(15, ChronoUnit.MINUTES)))

        whoami(token).status shouldBe 401
    }

    @Test
    fun `alg none is 401`() {
        // The classic: a token whose header says it is unsigned, carrying
        // any claims the attacker likes. The decoder is pinned to RS256 and
        // never gets as far as reading them.
        val unsigned =
            JWTClaimsSet
                .Builder()
                .issuer(properties.issuer)
                .subject(userId.toString())
                .audience(properties.audience)
                .claim("scope", "user")
                .build()
        val token = PlainJWT(PlainHeader(), unsigned).serialize()

        whoami(token).status shouldBe 401
    }

    @Test
    fun `a token signed by another key is 401`() {
        // Same claims, same `kid` in the header, wrong private key: a forger
        // who knows everything about our tokens except the one thing that matters.
        val stranger = RSAKeyGenerator(2048).keyID(keys.kid).generate()

        whoami(signed(claims(), by = stranger)).status shouldBe 401
    }

    @Test
    fun `a token for another issuer or another audience is 401`() {
        whoami(signedWithRealKey(claims(issuer = "https://someone-else.example"))).status shouldBe 401
        whoami(signedWithRealKey(claims(audience = "another-app"))).status shouldBe 401
    }

    @Test
    fun `a token issued before the user's sessions were revoked is 401, and one issued after is not`() {
        val before = issuer.issue(userId).token
        revocations.revokeBefore(userId, Instant.now().plusSeconds(1))

        whoami(before).status shouldBe 401

        // A token minted two seconds after the bump is a fresh session.
        val after = signedWithRealKey(claims(iat = Instant.now().plusSeconds(2), exp = Instant.now().plusSeconds(600)))
        whoami(after).status shouldBe 200
    }

    @Test
    fun `a token for a user who no longer exists is 401`() {
        val token = issuer.issue(userId).token
        revocations.remove(userId)

        whoami(token).status shouldBe 401
    }

    @Test
    fun `a valid token without the required scope is 403 in the problem shape, not 500`() {
        // Method security fails inside MVC, where the catch-all advice would
        // otherwise report it as an internal error.
        val response =
            mockMvc
                .get("/api/v1/probe/admin") { header(HttpHeaders.AUTHORIZATION, "Bearer ${issuer.issue(userId).token}") }
                .andReturn()
                .response

        response.status shouldBe 403
        response.contentAsString shouldContain "\"code\":\"FORBIDDEN\""
        response.contentAsString shouldNotContain "plans"
    }

    @Test
    fun `health is public`() {
        mockMvc
            .get("/actuator/health")
            .andReturn()
            .response.status shouldBe 200
    }

    @Test
    fun `the named auth endpoints are public, and only for POST`() {
        // "Public" shows up as *reaching routing*: 404 where no controller
        // serves the path in this context, 200 for the two the probe
        // controller takes — never 401. Anything the chain protects is 401
        // regardless of whether a route exists, which is the second assertion.
        SecurityConfiguration.PUBLIC_AUTH_ENDPOINTS.forEach { path ->
            mockMvc
                .post(path)
                .andReturn()
                .response.status shouldNotBe 401
            mockMvc
                .get(path)
                .andReturn()
                .response.status shouldBe 401
        }
        mockMvc
            .get("/api/v1/does-not-exist")
            .andReturn()
            .response.status shouldBe 401
    }

    private fun whoami(token: String): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/probe/whoami") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
            .andReturn()
            .response

    private fun claims(
        issuer: String = properties.issuer,
        audience: String = properties.audience,
        iat: Instant = Instant.now(),
        exp: Instant = Instant.now().plusSeconds(600),
    ): JwtClaimsSet =
        JwtClaimsSet
            .builder()
            .issuer(issuer)
            .subject(userId.toString())
            .audience(listOf(audience))
            .issuedAt(iat)
            .expiresAt(exp)
            .id(UUID.randomUUID().toString())
            .claim("scope", "user")
            .build()

    private fun signedWithRealKey(claims: JwtClaimsSet): String = signed(claims, by = keys.rsaKey)

    private fun signed(
        claims: JwtClaimsSet,
        by: RSAKey,
    ): String {
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.kid).build()
        return NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(by))).encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }
}
