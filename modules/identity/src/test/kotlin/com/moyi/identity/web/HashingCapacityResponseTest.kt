package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.domain.HashingCapacityExceededException
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHashAlgorithm
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * What a caller sees when Argon2id is at capacity (NFR-005a).
 *
 * A separate context from [RegistrationEndpointTest] because the hasher is
 * replaced wholesale — reaching real saturation through HTTP would mean
 * firing dozens of concurrent requests and asserting that *some* of them
 * failed, which is a test that passes for the wrong reason on a fast machine
 * and flakes on a slow one.
 *
 * It also exercises the thing a standalone MockMvc test could not: that
 * identity's own `@RestControllerAdvice` beats `common:web`'s catch-all in a
 * real application context.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(HashingCapacityResponseTest.SaturatedHasherConfiguration::class)
internal class HashingCapacityResponseTest(
    @Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {
    @Test
    fun `saturation is 503 with a Retry-After, not 500`() {
        val response =
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """
                        {
                          "email": "ada@example.com",
                          "password": "correct horse battery",
                          "displayName": "Ada",
                          "locale": "en",
                          "acceptedTermsVersion": "2026-09-01",
                          "over18": true
                        }
                        """.trimIndent()
                }.andReturn()
                .response

        // 500 would tell the client it is broken and retrying is pointless.
        // The request was fine; the server was briefly full.
        response.status shouldBe 503
        // FR-012 requires Retry-After on a 429 for the same reason it matters
        // here: without it every client picks its own interval, and a brief
        // saturation becomes a sustained one.
        response.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "1"
        response.contentAsString shouldContain "\"code\":\"HASHING_CAPACITY_EXCEEDED\""
    }

    @TestConfiguration
    class SaturatedHasherConfiguration {
        @Bean
        @Primary
        fun alwaysSaturated(): PasswordHasher =
            object : PasswordHasher {
                override val algorithm = PasswordHashAlgorithm.ARGON2ID

                override fun hash(password: Password): PasswordHash = throw HashingCapacityExceededException()

                override fun matches(
                    raw: String,
                    hash: PasswordHash,
                ): Boolean = throw HashingCapacityExceededException()

                override fun matchesDummy(raw: String): Boolean = throw HashingCapacityExceededException()
            }
    }

    private companion object {
        /**
         * The real corpus is a 17 MB release asset the build downloads and
         * pins by digest; a test that waited for it would be testing the
         * network. This points the context at a dozen-entry fixture built from
         * a readable list, using the same `BloomFilter` the service loads — so
         * the format is exercised rather than stood in for.
         *
         * Every context-booting test needs this, because there is deliberately
         * no flag that switches the corpus off:
         * `BloomFilterBreachedPasswordCorpus` refuses to start without one
         * (ADR-0016), and a test context that could boot without it would not
         * be the context we deploy.
         */
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
