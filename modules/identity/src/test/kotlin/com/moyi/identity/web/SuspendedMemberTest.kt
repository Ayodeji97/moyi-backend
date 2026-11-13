package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.notification.api.EmailMessage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Spec §11's member, over the real identity endpoints: once suspended, every door the member
 * holds a key to is shut, and the sign-in says nothing a wrong password would not; once lifted,
 * the member signs in again, and nothing the suspension revoked comes back. The suspension goes
 * through the port, in a transaction, as the console's service will call it.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
internal class SuspendedMemberTest(
    @Autowired private val suspension: AccountSuspension,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun start() {
        clock.set(Instant.parse("2026-11-13T10:00:00.123456Z"))
    }

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE account_suspensions, refresh_tokens, devices, verification_tokens, consent_records, credentials, " +
                "users, outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    @Test
    fun `a suspended member is locked out, and the sign-in refusal is a wrong password's`() {
        val (ada, email) = signUp()
        val signedIn = login(email, PASSWORD)
        signedIn.status shouldBe 200
        val access = accessToken(signedIn.contentAsString)
        val refreshToken = refreshToken(signedIn.contentAsString)
        val wrongPassword = login(email, "not the password")
        wrongPassword.status shouldBe 401
        val failuresBefore = failedAttempts(ada)

        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        clock.advance(Duration.ofSeconds(1))

        val me = get("/api/v1/me", access)
        me.status shouldBe 401
        me.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldNotBe null
        get("/api/v1/auth/sessions", access).status shouldBe 401
        val refreshed = refresh(refreshToken)
        refreshed.status shouldBe 401
        refreshed.contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""

        val rightPassword = login(email, PASSWORD)
        rightPassword.status shouldBe 401
        noInstance(rightPassword.contentAsString) shouldBe noInstance(wrongPassword.contentAsString)
        failedAttempts(ada) shouldBe failuresBefore

        // No reset token, so nothing for the mail path to send: checked at its source, because the
        // mail itself is sent after the commit, asynchronously, and its absence cannot be awaited.
        forgot(email).status shouldBe 202
        resetTokens(ada) shouldBe 0
    }

    @Test
    fun `after the lift the member signs in again, and nothing the suspension revoked comes back`() {
        val (ada, email) = signUp()
        val before = login(email, PASSWORD)
        before.status shouldBe 200
        val oldRefresh = refreshToken(before.contentAsString)
        val oldAccess = accessToken(before.contentAsString)
        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        clock.advance(Duration.ofSeconds(1))
        forgot(email).status shouldBe 202

        transactions.execute { suspension.lift(ada) }.outcome shouldBe SuspensionOutcome.LIFTED
        clock.advance(Duration.ofSeconds(1))

        val after = login(email, PASSWORD)
        after.status shouldBe 200
        get("/api/v1/me", accessToken(after.contentAsString)).status shouldBe 200
        // The families revoked by the suspension stay revoked: the only live one is the new sign-in's.
        liveRefreshTokens(ada) shouldBe 1
        refresh(oldRefresh).status shouldBe 401
        get("/api/v1/me", oldAccess).status shouldBe 401

        // The reset asked for while suspended left no token (so no mail to send); one asked for now
        // leaves a token and its mail goes out: the check at the source is the one that tells them apart.
        resetTokens(ada) shouldBe 0
        forgot(email).status shouldBe 202
        resetTokens(ada) shouldBe 1
        await().atMost(WAIT).until { emails.sent.toList().any { it.to == email } }
    }

    @Test
    fun `a reset link or code sent before the suspension is refused after it, exactly as a spent one is`() {
        val (ada, email) = signUp()
        // Two emails, one after the other: the first's link stays live, the second's code is the
        // live one (a new email retires the last one's code).
        forgot(email).status shouldBe 202
        val first = awaitResetEmails(email, 1).single()
        forgot(email).status shouldBe 202
        val second = awaitResetEmails(email, 2).single { it != first }
        val hashBefore = passwordHash(ada)

        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        clock.advance(Duration.ofSeconds(1))

        // Each refused while suspended, then presented again: the second answer is a spent
        // credential's, and the first must be the same bytes, so nothing tells a suspension.
        val byLink = resetByLink(secretIn(first))
        val linkSpent = resetByLink(secretIn(first))
        byLink.status shouldBe 410
        byLink.status shouldBe linkSpent.status
        noInstance(byLink.contentAsString) shouldBe noInstance(linkSpent.contentAsString)

        val byCode = resetByCode(email, codeIn(second))
        val codeSpent = resetByCode(email, codeIn(second))
        byCode.status shouldBe 422
        byCode.status shouldBe codeSpent.status
        noInstance(byCode.contentAsString) shouldBe noInstance(codeSpent.contentAsString)

        passwordHash(ada) shouldBe hashBefore
        status(ada) shouldBe "SUSPENDED"
        transactions.execute { suspension.lift(ada) }.outcome shouldBe SuspensionOutcome.LIFTED
        clock.advance(Duration.ofSeconds(1))
        login(email, PASSWORD).status shouldBe 200
    }

    // --- helpers ---

    private fun status(userId: UUID): String = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, userId)!!

    private fun passwordHash(userId: UUID): String =
        jdbc.queryForObject("SELECT password_hash FROM credentials WHERE user_id = ?", String::class.java, userId)!!

    private fun awaitResetEmails(
        email: String,
        count: Int,
    ): List<EmailMessage> {
        await().atMost(WAIT).until { emails.sent.toList().count { it.to == email } >= count }
        return emails.sent.toList().filter { it.to == email }
    }

    private fun secretIn(email: EmailMessage): String =
        Regex("""token=([A-Za-z0-9_-]+)""").find(email.text)?.groupValues?.get(1) ?: error("no link in the email body")

    private fun codeIn(email: EmailMessage): String =
        Regex("""(?m)^(\d{6})$""").find(email.text)?.groupValues?.get(1) ?: error("no code in the email body")

    private fun resetByLink(secret: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/reset-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"$secret","password":"$NEW_PASSWORD"}"""
            }.andReturn()
            .response

    private fun resetByCode(
        email: String,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/reset-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","code":"$code","password":"$NEW_PASSWORD"}"""
            }.andReturn()
            .response

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a different good password"
        val WAIT: Duration = Duration.ofSeconds(10)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
