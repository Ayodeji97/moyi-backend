package com.moyi.common.security.ratelimit

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.PersonalDataHasher
import com.moyi.common.security.SecurityTestApplication
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.RedisIntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.UUID

/**
 * Doc 06 §4 over HTTP: the headers on every limited response, the 429 in the
 * problem shape with `Retry-After`, the per-user bucket behind the token, and
 * the address the per-IP bucket is keyed on.
 */
@SpringBootTest(classes = [SecurityTestApplication::class])
@AutoConfigureMockMvc
@Import(RateLimitInterceptorTest.TimeConfiguration::class)
class RateLimitInterceptorTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val issuer: AccessTokenIssuer,
    @Autowired private val hasher: PersonalDataHasher,
    @Autowired private val redis: StringRedisTemplate,
) : RedisIntegrationTest() {
    @AfterEach
    fun flush() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `a limited endpoint carries the three headers on success`() {
        val response = register()

        response.status shouldBe 200
        response.getHeader("X-RateLimit-Limit") shouldBe "3"
        response.getHeader("X-RateLimit-Remaining") shouldBe "2"
        // Reset is when the bucket is *full again*: one token is missing and
        // greedy refill returns it in a third of the hour.
        response.getHeader("X-RateLimit-Reset") shouldBe
            clock
                .instant()
                .plus(Duration.ofMinutes(20))
                .epochSecond
                .toString()
        response.getHeader(HttpHeaders.RETRY_AFTER) shouldBe null
    }

    @Test
    fun `the request after the last token is 429 RATE_LIMITED with Retry-After, in the problem shape`() {
        repeat(3) { register().status shouldBe 200 }

        val refused = register()

        refused.status shouldBe 429
        refused.contentType!! shouldStartWith MediaType.APPLICATION_PROBLEM_JSON_VALUE
        refused.contentAsString shouldContain "\"code\":\"RATE_LIMITED\""
        refused.contentAsString shouldContain "Please wait 20 minutes before trying again."
        // Three an hour, greedy: one token every twenty minutes.
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "1200"
        refused.getHeader("X-RateLimit-Limit") shouldBe "3"
        refused.getHeader("X-RateLimit-Remaining") shouldBe "0"
        refused.getHeader("X-RateLimit-Reset") shouldBe
            clock
                .instant()
                .plus(Duration.ofHours(1))
                .epochSecond
                .toString()
    }

    @Test
    fun `the wait is real, and it is over when the clock says so`() {
        repeat(3) { register() }
        register().status shouldBe 429

        clock.advance(Duration.ofMinutes(20))

        register().status shouldBe 200
    }

    @Test
    fun `another address has its own bucket`() {
        repeat(3) { register(from = "203.0.113.7") }
        register(from = "203.0.113.7").status shouldBe 429

        register(from = "203.0.113.8").status shouldBe 200
    }

    @Test
    fun `with no trusted proxy, a forged X-Forwarded-For does not buy a fresh bucket`() {
        repeat(3) { register(forwardedFor = "198.51.100.$it") }

        register(forwardedFor = "198.51.100.99").status shouldBe 429
    }

    @Test
    fun `an authenticated request consumes the per-user bucket, one hundred and twenty a minute`() {
        val token = issuer.issue(UUID.randomUUID()).token

        val response = mockMvc.get("/api/v1/probe/whoami") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andReturn().response

        response.status shouldBe 200
        response.getHeader("X-RateLimit-Limit") shouldBe "120"
        response.getHeader("X-RateLimit-Remaining") shouldBe "119"
    }

    @Test
    fun `a public endpoint without the annotation is not limited and says nothing about limits`() {
        val response = mockMvc.get("/actuator/health").andReturn().response

        response.status shouldBe 200
        response.getHeader("X-RateLimit-Limit") shouldBe null
    }

    @Test
    fun `a controller can ask who is calling and gets the hashes, never the address`() {
        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    with { request -> request.apply { remoteAddr = "203.0.113.7" } }
                    header(HttpHeaders.USER_AGENT, "Moyi/1.0 (Pixel 9)")
                }.andReturn()
                .response

        response.status shouldBe 200
        response.contentAsString shouldContain "\"addressHash\":\"${hasher.hash("203.0.113.7")}\""
        response.contentAsString shouldContain "\"userAgentHash\":\"${hasher.hash("Moyi/1.0 (Pixel 9)")}\""
        response.contentAsString shouldContain "\"address\":null"
    }

    @Test
    fun `no User-Agent is a null hash, not a hash of nothing`() {
        val response = mockMvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON }.andReturn().response

        response.contentAsString shouldContain "\"userAgentHash\":null"
    }

    private fun register(
        from: String = "127.0.0.1",
        forwardedFor: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                with { request -> request.apply { remoteAddr = from } }
                forwardedFor?.let { header("X-Forwarded-For", it) }
            }.andReturn()
            .response

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }
}
