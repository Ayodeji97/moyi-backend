package com.moyi.common.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import java.time.Clock
import java.time.Duration

/**
 * The application's one `SecurityFilterChain`, and the JWT encoder and
 * decoder it shares with [AccessTokenIssuer].
 *
 * **A resource server, not a hand-written filter.** Chirp writes a
 * `OncePerRequestFilter` that reads the header, calls its own `JwtService`
 * and sets the `SecurityContext` by hand. Spring Security ships that filter
 * — `BearerTokenAuthenticationFilter` — with the header parsing, the
 * `WWW-Authenticate` challenge, the failure routing and a decoder built on
 * Nimbus. Doc 25 D5 says take every protocol primitive from the library and
 * write only the lifecycle, and this is where that rule has the most teeth:
 * the difference between a filter that rejects `alg: none` and one that
 * accepts it is a detail somebody has to have thought of, and the library
 * already did.
 *
 * **Stateless.** No session is created or read; every request carries its
 * own proof. CSRF is disabled because CSRF is an attack on ambient cookie
 * credentials, and there are none (doc 09 §5).
 *
 * **What is public is a list, not a pattern.** `POST` on the named auth
 * endpoints and `GET` on health; everything else needs a bearer token,
 * including any path that does not exist yet. A wildcard over everything
 * under `/auth/` would silently make `GET /auth/sessions` (FR-007) public the
 * day it is added.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties::class, HashingProperties::class, ClientAddressProperties::class)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        entryPoint: ProblemAuthenticationEntryPoint,
        deniedHandler: ProblemAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                PUBLIC_AUTH_ENDPOINTS.forEach { path -> authorize(HttpMethod.POST, path, permitAll) }
                authorize(HttpMethod.GET, "/actuator/health", permitAll)
                authorize(HttpMethod.GET, "/actuator/health/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { this.jwtDecoder = jwtDecoder }
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = deniedHandler
            }
            // The same two handlers for requests that never presented a token
            // at all, so "no token" and "bad token" are the same shape of 401.
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = deniedHandler
            }
        }
        return http.build()
    }

    @Bean
    fun signingKeys(properties: JwtProperties): SigningKeys = SigningKeys.from(properties)

    @Bean
    fun personalDataHasher(properties: HashingProperties): PersonalDataHasher = PersonalDataHasher.from(properties)

    @Bean
    fun jwtEncoder(keys: SigningKeys): JwtEncoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(keys.rsaKey)))

    /**
     * Verifies with the **public** half only, pinned to RS256. Pinning the
     * algorithm is what closes the algorithm-confusion family of attacks: a
     * token claiming `HS256` signed with the public key as the HMAC secret is
     * refused before its signature is even examined, and `alg: none` has no
     * signature to examine.
     *
     * Validators run in order and all of them run; the first failure wins.
     * Timestamp (with the default 60 s skew, and the injected clock so a test
     * can expire a token without sleeping), issuer, audience, revocation.
     */
    @Bean
    fun jwtDecoder(
        keys: SigningKeys,
        properties: JwtProperties,
        revocations: TokenRevocations,
        clock: Clock,
    ): JwtDecoder {
        val decoder =
            NimbusJwtDecoder
                .withPublicKey(keys.publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
        val timestamps = JwtTimestampValidator(CLOCK_SKEW).apply { setClock(clock) }
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                timestamps,
                JwtIssuerValidator(properties.issuer),
                AudienceValidator(properties.audience),
                RevocationValidator(revocations),
            ),
        )
        return decoder
    }

    companion object {
        /**
         * Doc 06 §1: "everything except the public auth endpoints". Named one
         * by one, `POST` only. Two of these do not exist yet (FR-004); listing
         * them now costs nothing and means the slice that adds them cannot
         * forget to open them — the test that posts to them would get a 401
         * instead of the 404 it gets today.
         */
        val PUBLIC_AUTH_ENDPOINTS: List<String> =
            listOf(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/resend-verification",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
            )

        /** Spring Security's default. Sixty seconds of tolerance for a phone whose clock is wrong. */
        val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
