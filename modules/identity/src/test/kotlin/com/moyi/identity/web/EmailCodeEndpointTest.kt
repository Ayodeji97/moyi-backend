package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.notification.api.EmailMessage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
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
 * #135: the verification and reset emails carry a six-digit code beside the link, and
 * `verify-email` and `reset-password` take `{email, code}` in place of the token
 * (ADR-0018 and ADR-0022, amended 2026-11-08).
 *
 * The link and the code are one credential on one row: these tests spend each and
 * present the other. Every refusal on the code path is one answer, whatever the reason,
 * because a code is typed against an address and any difference would say whether the
 * address has an account.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(EmailVerificationEndpointTest.RecordingEmailConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class EmailCodeEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, refresh_tokens, consent_records, credentials, users CASCADE")
        emails.sent.clear()
    }

    @Test
    fun `the verification email carries a six-digit code, stored only as a keyed hash, that verifies the address`(output: CapturedOutput) {
        register()
        val email = awaitEmails(1).single()
        val code = codeIn(email)

        email.html shouldContain code
        val stored = jdbc.queryForObject("SELECT code_hash FROM verification_tokens", String::class.java)!!
        stored shouldNotContain code
        stored.length shouldBe HMAC_LENGTH

        val response = verifyByCode(ADA, code)

        response.status shouldBe 200
        response.contentAsString shouldBe ""
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "ACTIVE"
        jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM verification_tokens", Boolean::class.java) shouldBe true
        output.all shouldNotContain code
    }

    @Test
    fun `an address is typed as the person likes, and the code may carry surrounding space`() {
        register()
        val code = codeIn(awaitEmails(1).single())

        verifyByCode(" ADA@Example.com ", " $code ").status shouldBe 200
    }

    @Test
    fun `a code once used is refused, and so is the link beside it`() {
        register()
        val email = awaitEmails(1).single()
        verifyByCode(ADA, codeIn(email)).status shouldBe 200

        val again = verifyByCode(ADA, codeIn(email))
        again.status shouldBe 422
        again.contentAsString shouldContain "\"code\":\"VERIFICATION_TOKEN_INVALID\""
        verifyByToken(secretIn(email)).status shouldBe 410
    }

    @Test
    fun `the link once used leaves the code beside it refused`() {
        register()
        val email = awaitEmails(1).single()
        verifyByToken(secretIn(email)).status shouldBe 200

        verifyByCode(ADA, codeIn(email)).status shouldBe 422
    }

    @Test
    fun `five wrong codes burn the code, and the right one is then refused, while the link still works`() {
        register()
        val email = awaitEmails(1).single()
        val code = codeIn(email)
        val wrong = otherThan(code)

        repeat(MAX_WRONG) { verifyByCode(ADA, wrong).status shouldBe 422 }

        verifyByCode(ADA, code).status shouldBe 422
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "PENDING_VERIFICATION"
        // Every refused presentation is counted, the sixth against a burned code too.
        jdbc.queryForObject("SELECT code_attempts FROM verification_tokens", Int::class.java) shouldBe MAX_WRONG + 1
        // The link is 256 bits and nobody guessed it; a stranger who burned the code by
        // typing wrong ones has not taken the link away from the person who owns the inbox.
        verifyByToken(secretIn(email)).status shouldBe 200
    }

    @Test
    fun `four wrong codes leave the right one working`() {
        register()
        val code = codeIn(awaitEmails(1).single())

        repeat(MAX_WRONG - 1) { verifyByCode(ADA, otherThan(code)).status shouldBe 422 }

        verifyByCode(ADA, code).status shouldBe 200
    }

    @Test
    fun `an unknown address, a wrong code, an expired code and a burned code are one answer`() {
        register()
        val code = codeIn(awaitEmails(1).single())
        val wrong = otherThan(code)

        val unknown = verifyByCode("nobody@example.com", code)
        val mistyped = verifyByCode(ADA, wrong)
        jdbc.update("UPDATE verification_tokens SET code_expires_at = now() - interval '1 second'")
        val expired = verifyByCode(ADA, code)
        jdbc.update("UPDATE verification_tokens SET code_expires_at = now() + interval '1 minute', code_attempts = 5")
        val burned = verifyByCode(ADA, code)

        listOf(unknown, mistyped, expired, burned).forEach { it.status shouldBe 422 }
        val bodies = listOf(unknown, mistyped, expired, burned).map { withoutTraceId(it.contentAsString) }
        bodies.distinct() shouldHaveSize 1
        bodies.first() shouldContain "\"code\":\"VERIFICATION_TOKEN_INVALID\""
        bodies.first() shouldNotContain code
    }

    @Test
    fun `a new email retires the old code, and the new code works`() {
        register()
        val first = codeIn(awaitEmails(1).single())
        resend().status shouldBe 202
        val second = codeIn(awaitEmails(2).last())
        // Two rows, two live links (ADR-0018 decision 6), and one code: the newer one's.
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens WHERE code_hash IS NOT NULL", Int::class.java) shouldBe 1

        if (first != second) verifyByCode(ADA, first).status shouldBe 422
        verifyByCode(ADA, second).status shouldBe 200
    }

    @Test
    fun `a body that is neither a token nor an address and a code is a validation failure`() {
        register()
        val code = codeIn(awaitEmails(1).single())

        listOf(
            """{}""",
            """{"email": "$ADA"}""",
            """{"code": "$code"}""",
            """{"token": " "}""",
            """{"token": "abc", "email": "$ADA", "code": "$code"}""",
            """{"email": "$ADA", "code": "12345"}""",
            """{"email": "$ADA", "code": "1234567"}""",
            """{"email": "$ADA", "code": "12a456"}""",
            """{"email": "not-an-address", "code": "$code"}""",
        ).forEach { body ->
            val response = post("/api/v1/auth/verify-email", body)
            response.status shouldBe 422
            response.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        }
        jdbc.queryForObject("SELECT code_attempts FROM verification_tokens", Int::class.java) shouldBe 0
    }

    @Test
    fun `a password is reset by address and code, every session ends, and the code and link are spent`() {
        register()
        verifyByCode(ADA, codeIn(awaitEmails(1).single())).status shouldBe 200
        login(PASSWORD).status shouldBe 200
        forgot().status shouldBe 202
        val email = awaitEmails(2).last()
        val code = codeIn(email)

        val response = resetByCode(ADA, code, NEW_PASSWORD)

        response.status shouldBe 200
        login(PASSWORD).status shouldBe 401
        login(NEW_PASSWORD).status shouldBe 200
        // The session from before the reset was ended by it; only the sign-in after it is live.
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Int::class.java) shouldBe 1
        resetByCode(ADA, code, "yet another good password").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"code\":\"PASSWORD_RESET_TOKEN_INVALID\""
        }
        resetByToken(secretIn(email), "yet another good password").status shouldBe 410
    }

    @Test
    fun `a refused new password does not spend the reset code or count against it`() {
        register()
        forgot().status shouldBe 202
        val code = codeIn(awaitEmails(2).last())

        resetByCode(ADA, code, "password").status shouldBe 422

        jdbc.queryForObject(
            "SELECT code_attempts FROM verification_tokens WHERE purpose = 'PASSWORD_RESET'",
            Int::class.java,
        ) shouldBe 0
        resetByCode(ADA, code, NEW_PASSWORD).status shouldBe 200
    }

    @Test
    fun `reset refusals by code are one answer for an unknown address and a wrong code`() {
        register()
        forgot().status shouldBe 202
        val code = codeIn(awaitEmails(2).last())

        val unknown = resetByCode("nobody@example.com", code, NEW_PASSWORD)
        val wrong = resetByCode(ADA, otherThan(code), NEW_PASSWORD)

        unknown.status shouldBe 422
        wrong.status shouldBe 422
        withoutTraceId(unknown.contentAsString) shouldBe withoutTraceId(wrong.contentAsString)
        unknown.contentAsString shouldContain "\"code\":\"PASSWORD_RESET_TOKEN_INVALID\""
    }

    @Test
    fun `a code is good only for what it was sent for`() {
        register()
        val verification = codeIn(awaitEmails(1).single())
        forgot().status shouldBe 202
        val reset = codeIn(awaitEmails(2).last())

        if (reset != verification) verifyByCode(ADA, reset).status shouldBe 422
        if (reset != verification) resetByCode(ADA, verification, NEW_PASSWORD).status shouldBe 422
        verifyByCode(ADA, verification).status shouldBe 200
        resetByCode(ADA, reset, NEW_PASSWORD).status shouldBe 200
    }

    private fun awaitEmails(count: Int): List<EmailMessage> {
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize count }
        return emails.sent.toList()
    }

    private fun codeIn(email: EmailMessage): String =
        Regex("""(?m)^(\d{6})$""").find(email.text)?.groupValues?.get(1) ?: error("no code in the email body")

    private fun secretIn(email: EmailMessage): String =
        Regex("""token=([A-Za-z0-9_-]+)""").find(email.text)?.groupValues?.get(1) ?: error("no link in the email body")

    private fun otherThan(code: String): String = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

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

    private fun resend() = post("/api/v1/auth/resend-verification", """{"email": "$ADA"}""")

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
        const val MAX_WRONG = 5

        /** HMAC-SHA256, base64url without padding. */
        const val HMAC_LENGTH = 43
        val WAIT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
