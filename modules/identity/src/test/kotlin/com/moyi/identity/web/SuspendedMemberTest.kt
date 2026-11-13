package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
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

        transactions.execute { suspension.suspend(ada) }!!.outcome shouldBe SuspensionOutcome.SUSPENDED
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

        forgot(email).status shouldBe 202
        resetTokens(ada) shouldBe 0
        emails.sent.toList().none { it.to == email } shouldBe true
    }

    @Test
    fun `after the lift the member signs in again, and nothing the suspension revoked comes back`() {
        val (ada, email) = signUp()
        val before = login(email, PASSWORD)
        before.status shouldBe 200
        val oldRefresh = refreshToken(before.contentAsString)
        val oldAccess = accessToken(before.contentAsString)
        transactions.execute { suspension.suspend(ada) }!!.outcome shouldBe SuspensionOutcome.SUSPENDED
        clock.advance(Duration.ofSeconds(1))
        forgot(email).status shouldBe 202

        transactions.execute { suspension.lift(ada) }!!.outcome shouldBe SuspensionOutcome.LIFTED
        clock.advance(Duration.ofSeconds(1))

        val after = login(email, PASSWORD)
        after.status shouldBe 200
        get("/api/v1/me", accessToken(after.contentAsString)).status shouldBe 200
        // The families revoked by the suspension stay revoked: the only live one is the new sign-in's.
        liveRefreshTokens(ada) shouldBe 1
        refresh(oldRefresh).status shouldBe 401
        get("/api/v1/me", oldAccess).status shouldBe 401

        // The reset asked for while suspended sent nothing; one asked for now does. The positive
        // control shows the sink would have caught the first.
        resetTokens(ada) shouldBe 0
        forgot(email).status shouldBe 202
        await().atMost(WAIT).until { emails.sent.toList().count { it.to == email } == 1 }
        resetTokens(ada) shouldBe 1
    }

    // --- helpers ---

    /** As the smoke's `no_instance`: the bodies compared apart from the path that was typed. */
    private fun noInstance(body: String): String = body.replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private fun failedAttempts(userId: UUID): Int =
        jdbc.queryForObject("SELECT failed_attempts FROM credentials WHERE user_id = ?", Int::class.java, userId)!!

    private fun resetTokens(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM verification_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'",
            Int::class.java,
            userId,
        )!!

    private fun liveRefreshTokens(userId: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Int::class.java, userId)!!

    private fun signUp(): Pair<UUID, String> {
        val email = "u-${UUID.randomUUID()}@example.com"
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        jdbc.update("UPDATE users SET email_verified_at = now(), status = 'ACTIVE' WHERE email = ?", email)
        // Registration's own email, settled, so any later one is a reset's.
        await().atMost(WAIT).until { emails.sent.toList().any { it.to == email } }
        emails.sent.clear()
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!! to email
    }

    private fun login(
        email: String,
        password: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","password":"$password"}"""
            }.andReturn()
            .response

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$token"}"""
            }.andReturn()
            .response

    private fun forgot(email: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/forgot-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email"}"""
            }.andReturn()
            .response

    private fun get(
        path: String,
        access: String,
    ): MockHttpServletResponse = mockMvc.get(path) { header(HttpHeaders.AUTHORIZATION, "Bearer $access") }.andReturn().response

    private fun refreshToken(body: String): String = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun accessToken(body: String): String = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private companion object {
        const val PASSWORD = "correct horse battery"
        val WAIT: Duration = Duration.ofSeconds(10)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
