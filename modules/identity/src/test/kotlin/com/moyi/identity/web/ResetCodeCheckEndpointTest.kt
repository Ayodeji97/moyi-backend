package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
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
 * #169: `POST /auth/reset-password/check` asks whether a typed reset code is right, so the app
 * can say "wrong code" before the new password is typed (ADR-0022, amended 2026-11-10).
 *
 * A check, not a grant: a right code is answered 204 and left exactly as it was, and the
 * reset still takes the address, the code and the password. A wrong check is a wrong try at
 * the same code, on the same counter the reset counts on, so checking and resetting together
 * get five wrong tries, never ten. Its refusals are the reset's one answer.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(EmailVerificationEndpointTest.RecordingEmailConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class ResetCodeCheckEndpointTest(
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
    fun `a right code is 204 with no body, spends nothing, and the reset by the same code then works`(output: CapturedOutput) {
        val code = resetCode()

        val checked = check(ADA, code)

        checked.status shouldBe 204
        checked.contentAsString shouldBe ""
        resetRow("consumed_at IS NULL", Boolean::class.java) shouldBe true
        resetRow("code_attempts", Int::class.java) shouldBe 0
        login(PASSWORD).status shouldBe 200
        // A check twice is still a check: nothing about the code has changed.
        check(ADA, code).status shouldBe 204

        resetByCode(ADA, code, NEW_PASSWORD).status shouldBe 200
        login(NEW_PASSWORD).status shouldBe 200
        // Spent by the reset, so a later check is refused like any other dead code.
        check(ADA, code).status shouldBe 422
        output.all shouldNotContain code
    }

    @Test
    fun `an address is typed as the person likes, and the code may carry surrounding space`() {
        val code = resetCode()

        check(" ADA@Example.com ", " $code ").status shouldBe 204
    }

    /**
     * The shared counter, the point of #169's ruling: a wrong check is a wrong try at the
     * reset code. Three wrong checks and two wrong resets are five, and the code is burned for
     * both routes; if the check kept a count of its own, the right code would still reset.
     */
    @Test
    fun `wrong checks and wrong resets share the code's five tries, and together burn it`() {
        val code = resetCode()
        val wrong = otherThan(code)

        repeat(3) { check(ADA, wrong).status shouldBe 422 }
        repeat(2) { resetByCode(ADA, wrong, NEW_PASSWORD).status shouldBe 422 }

        resetRow("code_attempts", Int::class.java) shouldBe MAX_WRONG
        check(ADA, code).status shouldBe 422
        resetByCode(ADA, code, NEW_PASSWORD).status shouldBe 422
        login(PASSWORD).status shouldBe 200
    }

    @Test
    fun `four wrong checks leave one try, which a right reset may take`() {
        val code = resetCode()

        repeat(MAX_WRONG - 1) { check(ADA, otherThan(code)).status shouldBe 422 }

        resetRow("code_attempts", Int::class.java) shouldBe MAX_WRONG - 1
        // A right check costs nothing, so the one try left is still there for the reset.
        check(ADA, code).status shouldBe 204
        resetByCode(ADA, code, NEW_PASSWORD).status shouldBe 200
    }

    @Test
    fun `wrong resets count against the check too`() {
        val code = resetCode()

        repeat(MAX_WRONG) { resetByCode(ADA, otherThan(code), NEW_PASSWORD).status shouldBe 422 }

        check(ADA, code).status shouldBe 422
    }

    @Test
    fun `an unknown address, a wrong code, an expired code, a burned code and a spent code are one answer`() {
        val code = resetCode()
        val wrong = otherThan(code)

        val unknown = check("nobody@example.com", code)
        val mistyped = check(ADA, wrong)
        jdbc.update("UPDATE verification_tokens SET code_expires_at = now() - interval '1 second' WHERE purpose = 'PASSWORD_RESET'")
        val expired = check(ADA, code)
        jdbc.update(
            "UPDATE verification_tokens SET code_expires_at = now() + interval '1 minute', code_attempts = 5 " +
                "WHERE purpose = 'PASSWORD_RESET'",
        )
        val burned = check(ADA, code)
        jdbc.update("UPDATE verification_tokens SET code_attempts = 0, consumed_at = now() WHERE purpose = 'PASSWORD_RESET'")
        val spent = check(ADA, code)

        val refusals = listOf(unknown, mistyped, expired, burned, spent)
        refusals.forEach { it.status shouldBe 422 }
        val bodies = refusals.map { withoutTraceId(it.contentAsString) }
        bodies.distinct() shouldHaveSize 1
        bodies.first() shouldContain "\"code\":\"PASSWORD_RESET_TOKEN_INVALID\""
        bodies.first() shouldNotContain code
        // The reset's own refusal says the same thing in the same words; only `instance` differs.
        withoutInstance(bodies.first()) shouldBe
            withoutInstance(withoutTraceId(resetByCode("nobody@example.com", code, NEW_PASSWORD).contentAsString))
    }

    @Test
    fun `a verification code is not a reset code`() {
        register()
        val verification = codeIn(awaitEmails(1).single())

        check(ADA, verification).status shouldBe 422
    }

    @Test
    fun `a body that is not an address and a six-digit code is refused, and costs no try`() {
        val code = resetCode()

        // Both fields are required in the contract, so a body without one cannot be read (400,
        // as for every request with a missing required field); a field of the wrong shape is 422.
        listOf(
            """{}""",
            """{"email": "$ADA"}""",
            """{"code": "$code"}""",
            """{"token": "abc"}""",
        ).forEach { body ->
            val response = post(CHECK, body)
            response.status shouldBe 400
            response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
        }
        listOf(
            """{"email": " ", "code": "$code"}""",
            """{"email": "$ADA", "code": "12345"}""",
            """{"email": "$ADA", "code": "12a456"}""",
            """{"email": "not-an-address", "code": "$code"}""",
        ).forEach { body ->
            val response = post(CHECK, body)
            response.status shouldBe 422
            response.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        }
        resetRow("code_attempts", Int::class.java) shouldBe 0
    }

    /** Registers Ada, asks for a reset, and returns the reset email's code. */
    private fun resetCode(): String {
        register()
        awaitEmails(1)
        forgot().status shouldBe 202
        return codeIn(awaitEmails(2).last())
    }

    private fun <T : Any> resetRow(
        column: String,
        type: Class<T>,
    ): T = jdbc.queryForObject("SELECT $column FROM verification_tokens WHERE purpose = 'PASSWORD_RESET'", type)!!

    private fun awaitEmails(count: Int): List<EmailMessage> {
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize count }
        return emails.sent.toList()
    }

    private fun codeIn(email: EmailMessage): String =
        Regex("""(?m)^(\d{6})$""").find(email.text)?.groupValues?.get(1) ?: error("no code in the email body")

    private fun otherThan(code: String): String = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private fun withoutTraceId(body: String): String = body.replace(Regex(""","traceId":"[0-9a-f]*""""), "")

    private fun withoutInstance(body: String): String = body.replace(Regex(""","?"instance":"[^"]*""""), "")

    private fun register(): MockHttpServletResponse =
        post(
            "/api/v1/auth/register",
            """
            {"email": "$ADA", "password": "$PASSWORD", "displayName": "Ada", "locale": "en",
             "acceptedTermsVersion": "2026-09-01", "over18": true}
            """.trimIndent(),
        )

    private fun forgot() = post("/api/v1/auth/forgot-password", """{"email": "$ADA"}""")

    private fun check(
        email: String,
        code: String,
    ) = post(CHECK, """{"email": "$email", "code": "$code"}""")

    private fun resetByCode(
        email: String,
        code: String,
        password: String,
    ) = post("/api/v1/auth/reset-password", """{"email": "$email", "code": "$code", "password": "$password"}""")

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
        const val CHECK = "/api/v1/auth/reset-password/check"
        const val ADA = "ada@example.com"
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a different good password"
        const val MAX_WRONG = 5
        val WAIT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
