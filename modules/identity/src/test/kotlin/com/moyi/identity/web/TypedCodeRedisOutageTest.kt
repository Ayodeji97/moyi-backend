package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.notification.api.EmailMessage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import javax.sql.DataSource

/**
 * The owner's ruling of 2026-11-09 (ADR-0023 amended): with Redis unreachable, a typed code
 * is refused until it is back, and everything else fails open as before.
 *
 * The limiter is **on** and Redis is a port nothing listens on, as in the app's
 * `RedisOutageTest`; [PostgresIntegrationTest] rather than `IntegrationTest`, so there is no
 * Redis container to stop and restart (the container restarts gotcha: a stopped
 * Testcontainers Redis comes back on a new port, and the cached context keeps the old one).
 */
@SpringBootTest(
    classes = [IdentityTestApplication::class],
    properties = [
        "moyi.security.rate-limit.enabled=true",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms",
        "moyi.security.rate-limit.request-timeout=200ms",
    ],
)
@AutoConfigureMockMvc
@Import(EmailVerificationEndpointTest.RecordingEmailConfiguration::class)
internal class TypedCodeRedisOutageTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, refresh_tokens, consent_records, credentials, users CASCADE")
        emails.sent.clear()
    }

    @Test
    fun `a typed verification code is 503 with Retry-After, and is neither spent nor counted`() {
        register().status shouldBe 201
        val code = codeIn(awaitEmails(1).single())

        val refused = verifyByCode(ADA, code)

        refused.status shouldBe 503
        refused.contentType!! shouldContain MediaType.APPLICATION_PROBLEM_JSON_VALUE
        refused.contentAsString shouldContain "\"code\":\"CODE_CHECK_UNAVAILABLE\""
        refused.contentAsString shouldNotContain code
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "60"
        refused.getHeader("X-RateLimit-Limit") shouldBe null
        jdbc.queryForObject("SELECT code_attempts FROM verification_tokens", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT consumed_at IS NULL FROM verification_tokens", Boolean::class.java) shouldBe true
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "PENDING_VERIFICATION"
    }

    @Test
    fun `the refusal is the same bytes for an address with no account`() {
        register().status shouldBe 201
        val code = codeIn(awaitEmails(1).single())

        val known = verifyByCode(ADA, code)
        val unknown = verifyByCode("nobody@example.com", code)

        unknown.status shouldBe 503
        withoutTraceId(unknown.contentAsString) shouldBe withoutTraceId(known.contentAsString)
        unknown.getHeader(HttpHeaders.RETRY_AFTER) shouldBe known.getHeader(HttpHeaders.RETRY_AFTER)
    }

    @Test
    fun `the link beside the code still verifies, and the person can then sign in`() {
        register().status shouldBe 201
        val email = awaitEmails(1).single()
        verifyByCode(ADA, codeIn(email)).status shouldBe 503

        verifyByToken(secretIn(email)).status shouldBe 200

        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "ACTIVE"
        // auth:login per email and per IP both fail open: the sign-in is served, not refused.
        login(PASSWORD).status shouldBe 200
    }

    @Test
    fun `a typed reset code is 503 and spends nothing, while the reset link still works`() {
        register().status shouldBe 201
        // forgot-password's two buckets fail open too: the email is sent.
        forgot().status shouldBe 202
        val email = awaitEmails(2).last()

        val refused = resetByCode(ADA, codeIn(email), NEW_PASSWORD)

        refused.status shouldBe 503
        refused.contentAsString shouldContain "\"code\":\"CODE_CHECK_UNAVAILABLE\""
        refused.getHeader(HttpHeaders.RETRY_AFTER) shouldBe "60"
        jdbc.queryForObject(
            "SELECT code_attempts FROM verification_tokens WHERE purpose = 'PASSWORD_RESET'",
            Int::class.java,
        ) shouldBe 0
        login(PASSWORD).status shouldBe 200

        resetByToken(secretIn(email), NEW_PASSWORD).status shouldBe 200
        login(NEW_PASSWORD).status shouldBe 200
    }

    @Test
    fun `a typed reset code for an address with no account is the same bytes`() {
        register().status shouldBe 201
        forgot().status shouldBe 202
        val code = codeIn(awaitEmails(2).last())

        val known = resetByCode(ADA, code, NEW_PASSWORD)
        val unknown = resetByCode("nobody@example.com", code, NEW_PASSWORD)

        unknown.status shouldBe 503
        withoutTraceId(unknown.contentAsString) shouldBe withoutTraceId(known.contentAsString)
        unknown.getHeader(HttpHeaders.RETRY_AFTER) shouldBe known.getHeader(HttpHeaders.RETRY_AFTER)
    }

    private fun awaitEmails(count: Int): List<EmailMessage> {
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize count }
        return emails.sent.toList()
    }

    private fun codeIn(email: EmailMessage): String =
        Regex("""(?m)^(\d{6})$""").find(email.text)?.groupValues?.get(1) ?: error("no code in the email body")

    private fun secretIn(email: EmailMessage): String =
        Regex("""token=([A-Za-z0-9_-]+)""").find(email.text)?.groupValues?.get(1) ?: error("no link in the email body")

    private fun withoutTraceId(body: String): String = body.replace(Regex(""","traceId":"[0-9a-f]*""""), "")

    private fun register(): MockHttpServletResponse =
        post(
            "/api/v1/auth/register",
            """
            {"email": "$ADA", "password": "$PASSWORD", "displayName": "Ada", "locale": "en",
             "acceptedTermsVersion": "2026-09-01", "over18": true}
            """.trimIndent(),
        )

    private fun verifyByCode(
        email: String,
        code: String,
    ) = post("/api/v1/auth/verify-email", """{"email": "$email", "code": "$code"}""")

    private fun verifyByToken(token: String) = post("/api/v1/auth/verify-email", """{"token": "$token"}""")

    private fun forgot() = post("/api/v1/auth/forgot-password", """{"email": "$ADA"}""")

    private fun resetByCode(
        email: String,
        code: String,
        password: String,
    ) = post("/api/v1/auth/reset-password", """{"email": "$email", "code": "$code", "password": "$password"}""")

    private fun resetByToken(
        token: String,
        password: String,
    ) = post("/api/v1/auth/reset-password", """{"token": "$token", "password": "$password"}""")

    private fun login(password: String) = post("/api/v1/auth/login", """{"email": "$ADA", "password": "$password"}""")

    private fun post(
        path: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private companion object {
        const val ADA = "ada@example.com"
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a different good password"
        val WAIT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
