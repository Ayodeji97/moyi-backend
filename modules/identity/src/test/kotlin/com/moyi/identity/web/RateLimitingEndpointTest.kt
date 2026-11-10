package com.moyi.identity.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-012 and doc 06 §4, on the real endpoints: every bucket the identity
 * module owes is enforced, each 429 carries `Retry-After`, and the headers
 * are on the successful responses too (doc 12 §3.3, "each bucket enforced,
 * `Retry-After` present").
 *
 * Its own context, with the limiter on; the shared one runs with it off
 * (see `src/test/resources/application.yml`). Redis is flushed after each
 * test so the buckets start full.
 */
@SpringBootTest(
    classes = [IdentityTestApplication::class],
    properties = ["moyi.security.rate-limit.enabled=true"],
)
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, RateLimitingEndpointTest.TimeConfiguration::class)
internal class RateLimitingEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val hasher: RecordingPasswordHasher,
    @Autowired private val issuer: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE refresh_tokens, verification_tokens, consent_records, credentials, users CASCADE")
        // Through the container rather than a Redis client: this module has
        // no Redis dependency of its own, and should not grow one for a test.
        redis.execInContainer("valkey-cli", "FLUSHALL").exitCode shouldBe 0
        hasher.matchesCalls.set(0)
        hasher.dummyCalls.set(0)
    }

    // ---- auth:login, per email --------------------------------------------

    @Test
    fun `a sixth sign-in for one address inside fifteen minutes is 429, and the address need not exist`() {
        repeat(5) { login(email = "nobody@example.com").status shouldBe 401 }

        val refused = login(email = "nobody@example.com")

        refused.status shouldBe 429
        refused.contentType!! shouldStartWith MediaType.APPLICATION_PROBLEM_JSON_VALUE
        refused.contentAsString shouldContain "\"code\":\"RATE_LIMITED\""
        refused.contentAsString shouldContain "Please wait 3 minutes before trying again."
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "180"
        refused.getHeader("X-RateLimit-Limit") shouldBe "5"
        refused.getHeader("X-RateLimit-Remaining") shouldBe "0"
    }

    @Test
    fun `the per-email refusal costs no Argon2id verification, and a case variant is the same address`() {
        repeat(5) { login(email = "Nobody@Example.com") }
        hasher.dummyCalls.get() shouldBe 5

        login(email = "nobody@example.com").status shouldBe 429

        hasher.dummyCalls.get() shouldBe 5
        hasher.matchesCalls.get() shouldBe 0
    }

    @Test
    fun `a refused sign-in still carries the per-address numbers, and the wait ends`() {
        val refused = login()

        // The 401 came from the service; the interceptor had already written
        // the bucket it knows about, `auth:login` per IP.
        refused.status shouldBe 401
        refused.getHeader("X-RateLimit-Limit") shouldBe "20"
        refused.getHeader("X-RateLimit-Remaining") shouldBe "19"

        repeat(4) { login() }
        login().status shouldBe 429
        clock.advance(Duration.ofMinutes(3))
        login().status shouldBe 401
    }

    // ---- auth:login, per IP -------------------------------------------------

    @Test
    fun `twenty sign-ins an hour from one address, whatever the email, and another address is unaffected`() {
        repeat(20) { login(email = "user$it@example.com", from = "203.0.113.7").status shouldBe 401 }

        val refused = login(email = "user99@example.com", from = "203.0.113.7")
        refused.status shouldBe 429
        refused.getHeader("X-RateLimit-Limit") shouldBe "20"
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "180"

        login(email = "user99@example.com", from = "203.0.113.8").status shouldBe 401
    }

    // ---- auth:register, per IP ---------------------------------------------

    @Test
    fun `three registrations an hour per address, with the headers on each 201`() {
        val first = register("one@example.com")
        first.status shouldBe 201
        first.getHeader("X-RateLimit-Limit") shouldBe "3"
        first.getHeader("X-RateLimit-Remaining") shouldBe "2"
        register("two@example.com").status shouldBe 201
        register("three@example.com").status shouldBe 201

        val refused = register("four@example.com")
        refused.status shouldBe 429
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "1200"
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 3

        register("four@example.com", from = "203.0.113.9").status shouldBe 201
    }

    // ---- auth:reset, per email and per IP ----------------------------------

    @Test
    fun `three reset requests an hour per address, identical for an address that has no account`() {
        repeat(3) { forgot("nobody@example.com").status shouldBe 202 }

        val refused = forgot("nobody@example.com")
        refused.status shouldBe 429
        refused.getHeader("X-RateLimit-Limit") shouldBe "3"
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "1200"

        forgot("somebody-else@example.com").status shouldBe 202
    }

    @Test
    fun `twenty reset requests an hour from one address, whatever the email`() {
        repeat(20) { forgot("user$it@example.com").status shouldBe 202 }

        forgot("user99@example.com").status shouldBe 429
    }

    // ---- auth:resend, per email and per IP ---------------------------------

    @Test
    fun `resend is once a minute per address, and the minute passes`() {
        resend("nobody@example.com").status shouldBe 202

        val refused = resend("nobody@example.com")
        refused.status shouldBe 429
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "60"
        refused.contentAsString shouldContain "Please wait a minute before trying again."

        clock.advance(Duration.ofMinutes(1))
        resend("nobody@example.com").status shouldBe 202
    }

    @Test
    fun `twenty resends an hour from one address, whatever the email`() {
        repeat(20) { resend("user$it@example.com").status shouldBe 202 }

        resend("user99@example.com").status shouldBe 429
    }

    // ---- auth:code, per email and per IP (#135) -----------------------------

    @Test
    fun `ten typed codes a day per address, both endpoints together, identical for an address with no account`() {
        repeat(5) { verifyByCode("nobody@example.com", from = "203.0.113.${it + 10}").status shouldBe 422 }
        repeat(5) { resetByCode("nobody@example.com", from = "203.0.113.${it + 20}").status shouldBe 422 }

        val refused = verifyByCode("nobody@example.com", from = "203.0.113.30")
        refused.status shouldBe 429
        refused.getHeader("X-RateLimit-Limit") shouldBe "10"
        // Greedy refill: one try back every 2.4 hours.
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "8640"
        resetByCode("NOBODY@example.com", from = "203.0.113.31").status shouldBe 429

        verifyByCode("somebody-else@example.com", from = "203.0.113.32").status shouldBe 422
    }

    /** #169: the reset-code check is a typed code like the others, on the same daily ten and hourly twenty. */
    @Test
    fun `the reset-code check spends the same ten a day per address and twenty an hour per client address`() {
        repeat(4) { checkResetCode("nobody@example.com", from = "203.0.113.${it + 40}").status shouldBe 422 }
        repeat(3) { resetByCode("nobody@example.com", from = "203.0.113.${it + 50}").status shouldBe 422 }
        repeat(3) { verifyByCode("nobody@example.com", from = "203.0.113.${it + 60}").status shouldBe 422 }

        val refused = checkResetCode("NOBODY@example.com", from = "203.0.113.70")
        refused.status shouldBe 429
        refused.getHeader("X-RateLimit-Limit") shouldBe "10"
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "8640"
        resetByCode("nobody@example.com", from = "203.0.113.71").status shouldBe 429

        repeat(20) { checkResetCode("someone$it@example.com", from = "198.51.100.7").status shouldBe 422 }
        val perAddress = verifyByToken("one-more", from = "198.51.100.7")
        perAddress.status shouldBe 429
        perAddress.getHeader("X-RateLimit-Limit") shouldBe "20"
    }

    @Test
    fun `twenty presentations an hour from one client address, codes and links on both endpoints`() {
        repeat(10) { verifyByCode("user$it@example.com").status shouldBe 422 }
        repeat(5) { verifyByToken("not-a-token-$it").status shouldBe 422 }
        repeat(5) { resetByCode("other$it@example.com").status shouldBe 422 }

        val refused = verifyByToken("one-more")
        refused.status shouldBe 429
        refused.getHeader("X-RateLimit-Limit") shouldBe "20"
        resetByCode("last@example.com").status shouldBe 429
    }

    // ---- global authenticated, per user -------------------------------------

    @Test
    fun `an authenticated user gets one hundred and twenty requests a minute, then a one-second wait`() {
        register("ada@example.com").status shouldBe 201
        val userId = jdbc.queryForObject("SELECT id FROM users", UUID::class.java)!!
        val token = issuer.issue(userId).token

        val first = me(token)
        first.status shouldBe 200
        first.getHeader("X-RateLimit-Limit") shouldBe "120"
        first.getHeader("X-RateLimit-Remaining") shouldBe "119"
        repeat(119) { me(token).status shouldBe 200 }

        val refused = me(token)
        refused.status shouldBe 429
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "1"
        refused.contentAsString shouldContain "\"code\":\"RATE_LIMITED\""

        clock.advance(Duration.ofSeconds(1))
        me(token).status shouldBe 200
    }

    // ---- account:deletion, per user (ADR-0058) ------------------------------

    @Test
    fun `a sixth account-deletion request in an hour is 429, wrong passwords counted alike`() {
        register("ada@example.com").status shouldBe 201
        val userId = jdbc.queryForObject("SELECT id FROM users", UUID::class.java)!!
        val token = issuer.issue(userId).token

        repeat(5) { deletionRequest(token).status shouldBe 401 }

        val refused = deletionRequest(token)
        refused.status shouldBe 429
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "720"
        refused.contentAsString shouldContain "\"code\":\"RATE_LIMITED\""
        // Spent before the password was looked at: the sixth cost no Argon2id.
        hasher.matchesCalls.get() shouldBe 5
    }

    // ---- helpers -----------------------------------------------------------

    private fun deletionRequest(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/me/deletion-request") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = """{"password":"not the password"}"""
            }.andReturn()
            .response

    private fun login(
        email: String = "ada@example.com",
        from: String = "127.0.0.1",
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","password":"not the password"}"""
                with { request -> request.apply { remoteAddr = from } }
            }.andReturn()
            .response

    private fun register(
        email: String,
        from: String = "127.0.0.1",
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"correct horse battery","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
                with { request -> request.apply { remoteAddr = from } }
            }.andReturn()
            .response

    private fun forgot(email: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/forgot-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email"}"""
            }.andReturn()
            .response

    private fun resend(email: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/resend-verification") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email"}"""
            }.andReturn()
            .response

    private fun verifyByCode(
        email: String,
        from: String = "127.0.0.1",
    ) = post("/api/v1/auth/verify-email", """{"email":"$email","code":"123456"}""", from)

    private fun verifyByToken(
        token: String,
        from: String = "127.0.0.1",
    ) = post("/api/v1/auth/verify-email", """{"token":"$token"}""", from)

    private fun checkResetCode(
        email: String,
        from: String = "127.0.0.1",
    ) = post("/api/v1/auth/reset-password/check", """{"email":"$email","code":"123456"}""", from)

    private fun resetByCode(
        email: String,
        from: String = "127.0.0.1",
    ) = post("/api/v1/auth/reset-password", """{"email":"$email","code":"123456","password":"a different good password"}""", from)

    private fun post(
        path: String,
        body: String,
        from: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = body
                with { request -> request.apply { remoteAddr = from } }
            }.andReturn()
            .response

    private fun me(token: String): MockHttpServletResponse =
        mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andReturn().response

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
