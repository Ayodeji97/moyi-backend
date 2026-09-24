package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.Argon2Properties
import com.moyi.identity.infra.security.Argon2idPasswordHasher
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.notification.api.EmailMessage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.awaitility.Awaitility.await
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * FR-003, FR-004 and doc 09 §3 end to end: login, refresh rotation with reuse
 * detection, logout, logout-all and password reset, through the real context
 * against a real Postgres. Two beans are wrapped, not replaced — the hasher
 * records how often it verified (the constant-time claim is asserted by that
 * counter, per doc 12 §3.3), and the email sender records what went out.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class)
internal class LoginEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val hasher: RecordingPasswordHasher,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE refresh_tokens, verification_tokens, consent_records, credentials, users CASCADE")
        hasher.matchesCalls.set(0)
        hasher.dummyCalls.set(0)
        emails.sent.clear()
    }

    // ---- login -------------------------------------------------------------

    @Test
    fun `an active user receives a token pair and the profile`() {
        registerAndActivate()

        val response = login(email = "ADA@Example.com", password = PASSWORD)

        response.status shouldBe 200
        response.contentType!! shouldStartWith MediaType.APPLICATION_JSON_VALUE
        response.contentAsString shouldContain "\"accessToken\":"
        response.contentAsString shouldContain "\"expiresIn\":900"
        response.contentAsString shouldContain "\"refreshToken\":"
        response.contentAsString shouldContain "\"email\":\"ada@example.com\""
        response.contentAsString shouldContain "\"status\":\"ACTIVE\""
        response.contentAsString shouldNotContain PASSWORD
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe null

        // One family, one live token, stored as a hash.
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT token_hash FROM refresh_tokens", String::class.java) shouldNotContain
            refreshToken(response.contentAsString)
    }

    @Test
    fun `the access token from login is accepted by GET me`() {
        registerAndActivate()
        val accessToken = accessToken(login().contentAsString)

        val me = mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }.andReturn().response

        me.status shouldBe 200
        me.contentAsString shouldContain "\"email\":\"ada@example.com\""
    }

    @Test
    fun `an unverified account signs in, and the profile says so`() {
        // FR-002: "Unverified accounts may sign in but MUST NOT create or join
        // a Bond." The first version refused them here, which would have made
        // screen 3's "Check again" impossible — it reads GET /me after signing in.
        registerAndActivate(activate = false)

        val response = login()

        response.status shouldBe 200
        response.contentAsString shouldContain "\"emailVerified\":false"
        response.contentAsString shouldContain "\"status\":\"PENDING_VERIFICATION\""
    }

    @Test
    fun `a password typed on a keyboard that composes differently still signs in`() {
        // Registration stores the NFKC form (ADR-0012). "e" + combining acute
        // is the same password as "é"; a login that compared the raw text
        // would have refused it, and the person would have had no way to
        // enter the password they set.
        registerAndActivate(password = "caf\u00e9 au lait plus")

        login(password = "cafe\u0301 au lait plus").status shouldBe 200
    }

    @Test
    fun `a suspended account is refused with the same body as a wrong password`() {
        registerAndActivate()
        val wrongPassword = login(password = "wrong password").contentAsString
        jdbc.update("UPDATE users SET status = 'SUSPENDED'")

        val suspended = login()

        suspended.status shouldBe 401
        suspended.contentAsString shouldBe wrongPassword
    }

    @Test
    fun `wrong password, unknown address and malformed address are one identical 401`() {
        registerAndActivate()

        val wrongPassword = login(password = "wrong password")
        val unknownEmail = login(email = "nobody@example.com")
        val malformed = login(email = "not-an-email")

        listOf(wrongPassword, unknownEmail, malformed).forEach {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"INVALID_CREDENTIALS\""
            it.contentAsString shouldNotContain "wrong password"
            it.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe null
        }
        wrongPassword.contentAsString shouldBe unknownEmail.contentAsString
        unknownEmail.contentAsString shouldBe malformed.contentAsString
    }

    @Test
    fun `the unknown-address path pays for exactly one Argon2id verification`() {
        // Doc 12 §3.3: asserted by a counter, not by a stopwatch. Without the
        // dummy verify the unknown path returns in ~2 ms and the known one in
        // ~150, which is a yes/no answer to "is this address registered" that
        // is measurable from anywhere.
        registerAndActivate()

        login(email = "nobody@example.com").status shouldBe 401
        hasher.dummyCalls.get() shouldBe 1
        hasher.matchesCalls.get() shouldBe 0

        login(password = "wrong password").status shouldBe 401
        hasher.dummyCalls.get() shouldBe 1
        hasher.matchesCalls.get() shouldBe 1
    }

    // ---- lockout -----------------------------------------------------------

    @Test
    fun `five wrong passwords lock the account silently, for a minute, and the right password is refused too`() {
        registerAndActivate()

        repeat(5) { login(password = "wrong password $it").status shouldBe 401 }
        val locked = login(password = PASSWORD)

        locked.status shouldBe 401
        locked.contentAsString shouldContain "\"code\":\"INVALID_CREDENTIALS\""
        failedAttempts() shouldBe 5
        secondsUntilUnlock() shouldBeInRange 55..60
        // The verify still ran for the locked attempt: 5 wrong + 1 locked.
        hasher.matchesCalls.get() shouldBe 6
    }

    @Test
    fun `each lock is longer than the last, and a successful sign-in resets everything`() {
        registerAndActivate()
        repeat(5) { login(password = "wrong").status shouldBe 401 }
        secondsUntilUnlock() shouldBeInRange 55..60

        expireLock()
        login(password = "wrong").status shouldBe 401
        failedAttempts() shouldBe 6
        secondsUntilUnlock() shouldBeInRange 115..120

        expireLock()
        login(password = "wrong").status shouldBe 401
        failedAttempts() shouldBe 7
        secondsUntilUnlock() shouldBeInRange 235..240

        expireLock()
        login(password = PASSWORD).status shouldBe 200
        failedAttempts() shouldBe 0
        jdbc.queryForObject("SELECT locked_until IS NULL FROM credentials", Boolean::class.java) shouldBe true
    }

    @Test
    fun `two simultaneous wrong passwords both count`() {
        // Read-then-write would let one increment overwrite the other.
        registerAndActivate()
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            List(2) {
                executor.submit {
                    ready.await()
                    login(password = "wrong")
                }
            }.also { ready.countDown() }.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        failedAttempts() shouldBe 2
    }

    // ---- refresh -----------------------------------------------------------

    @Test
    fun `refresh rotates the token, and the old one is then a reuse that revokes the family`() {
        registerAndActivate()
        val first = refreshToken(login().contentAsString)

        val rotated = refresh(first)
        rotated.status shouldBe 200
        val second = refreshToken(rotated.contentAsString)
        second shouldNotBe first

        val reuse = refresh(first)
        reuse.status shouldBe 401
        reuse.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""

        // The whole family is gone, including the successor that was never used.
        val successor = refresh(second)
        successor.status shouldBe 401
        successor.contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `a reuse emails the account holder, once per family`() {
        // Doc 09 §3: "the entire token family is revoked immediately, and the
        // user is emailed". The registration email is the first one; this is
        // the second. Presenting the stale token again is not a third.
        registerAndActivate()
        val first = refreshToken(login().contentAsString)
        refresh(first).status shouldBe 200

        refresh(first).status shouldBe 401
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 2 }
        val notice = emails.sent.last()
        notice.to shouldBe "ada@example.com"
        // Honest about scope: that sign-in ended; other devices did not.
        notice.subject shouldContain "was ended"
        notice.text shouldContain "other devices are not affected"
        notice.text shouldContain "I forgot my password"

        refresh(first).status shouldBe 401
        Thread.sleep(SETTLE.toMillis())
        emails.sent shouldHaveSize 2
    }

    @Test
    fun `a token that was never issued, or has expired, is REFRESH_TOKEN_INVALID`() {
        registerAndActivate()
        val token = refreshToken(login().contentAsString)

        refresh("never-issued").let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        }

        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'")
        refresh(token).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        }
        // Expiry is not a reuse: nothing was revoked and nobody was emailed.
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `two concurrent refreshes of one token let one through and treat the other as a reuse`() {
        // Strict, per doc 09 §3 — which is why doc 13 gives the client a mutex.
        registerAndActivate()
        val first = refreshToken(login().contentAsString)
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val responses =
            try {
                List(2) {
                    executor.submit<MockHttpServletResponse> {
                        ready.await()
                        refresh(first)
                    }
                }.also { ready.countDown() }.map { it.get() }
            } finally {
                executor.shutdown()
            }

        responses.count { it.status == 200 } shouldBe 1
        responses.single { it.status == 401 }.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        // And the winner's new token is dead too: the family went with it.
        val winner = refreshToken(responses.single { it.status == 200 }.contentAsString)
        refresh(winner).status shouldBe 401
    }

    // ---- logout ------------------------------------------------------------

    @Test
    fun `logout revokes the refresh-token family and is idempotent`() {
        registerAndActivate()
        val token = refreshToken(login().contentAsString)

        logout(token).status shouldBe 204
        refresh(token).status shouldBe 401
        logout(token).status shouldBe 204
        logout("never-issued").status shouldBe 204
    }

    @Test
    fun `logout-all revokes every family and every outstanding access token`() {
        registerAndActivate()
        val phone = login().contentAsString
        val laptop = login().contentAsString
        val phoneRefresh = refreshToken(phone)
        val laptopRefresh = refreshToken(laptop)
        val phoneAccess = accessToken(phone)

        mockMvc
            .post("/api/v1/auth/logout-all") { header(HttpHeaders.AUTHORIZATION, "Bearer $phoneAccess") }
            .andReturn()
            .response.status shouldBe 204

        refresh(phoneRefresh).status shouldBe 401
        refresh(laptopRefresh).status shouldBe 401
        // ADR-0019: the revocation instant is compared at one-second
        // resolution, so a token issued in the same second as the bump would
        // survive it. Move the boundary past that second, as a real caller
        // would have by the time it mattered.
        jdbc.update("UPDATE users SET tokens_invalid_before = tokens_invalid_before + interval '2 seconds'")
        mockMvc
            .get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $phoneAccess") }
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `logout-all without a token is 401`() {
        mockMvc
            .post("/api/v1/auth/logout-all")
            .andReturn()
            .response.status shouldBe 401
    }

    // ---- password reset ----------------------------------------------------

    @Test
    fun `forgot and reset, through the emailed link, change the password and sign every device out`() {
        registerAndActivate()
        val oldRefresh = refreshToken(login().contentAsString)
        val oldAccess = accessToken(login().contentAsString)
        emails.sent.clear()

        forgot("ada@example.com").status shouldBe 202
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 1 }
        val link = emails.sent.single()
        link.subject shouldContain "password"
        // The reset page, not the verify page.
        link.text shouldContain "https://moyi.test/reset-password?token="
        val secret = secretIn(link)

        reset(secret, NEW_PASSWORD).status shouldBe 200

        login(password = PASSWORD).status shouldBe 401
        login(password = NEW_PASSWORD).status shouldBe 200
        refresh(oldRefresh).status shouldBe 401
        jdbc.update("UPDATE users SET tokens_invalid_before = tokens_invalid_before + interval '2 seconds'")
        mockMvc
            .get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $oldAccess") }
            .andReturn()
            .response.status shouldBe 401

        // T-17: the old address is told.
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 2 }
        emails.sent.last().subject shouldContain "password was changed"

        // Single use.
        reset(secret, "yet another good password").let {
            it.status shouldBe 410
            it.contentAsString shouldContain "\"code\":\"PASSWORD_RESET_TOKEN_EXPIRED\""
        }
    }

    @Test
    fun `two password changes are two notices, not one and a retry`() {
        // A provider that remembers idempotency keys would have swallowed the
        // second notice under a key made of the user id alone.
        registerAndActivate()
        forgot("ada@example.com").status shouldBe 202
        reset(secretIn(awaitEmail(2)), NEW_PASSWORD).status shouldBe 200
        awaitEmail(3)
        forgot("ada@example.com").status shouldBe 202
        reset(secretIn(awaitEmail(4)), "yet another good password").status shouldBe 200
        awaitEmail(5)

        val notices = emails.sent.filter { it.subject.contains("password was changed") }
        notices shouldHaveSize 2
        notices[0].idempotencyKey shouldNotBe notices[1].idempotencyKey
    }

    @Test
    fun `forgot for an unknown, verified-elsewhere or unverified address is 202 and sends only when it should`() {
        registerAndActivate(activate = false)

        forgot("nobody@example.com").status shouldBe 202
        Thread.sleep(SETTLE.toMillis())
        // Only the registration email so far.
        emails.sent shouldHaveSize 1

        // FR-002 lets an unverified account sign in, so it may also reset its password.
        forgot("ada@example.com").status shouldBe 202
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 2 }
    }

    @Test
    fun `a reset token presented to verify-email is not recognised, and vice versa`() {
        // The two purposes share a table; the lookup must not share them.
        registerAndActivate(activate = false)
        val verifySecret = secretIn(awaitEmail(1))
        forgot("ada@example.com").status shouldBe 202
        val resetSecret = secretIn(awaitEmail(2))

        reset(verifySecret, NEW_PASSWORD).status shouldBe 422
        mockMvc
            .post("/api/v1/auth/verify-email") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"$resetSecret"}"""
            }.andReturn()
            .response.status shouldBe 422
    }

    @Test
    fun `a breached new password is refused at reset, per field`() {
        registerAndActivate()
        forgot("ada@example.com").status shouldBe 202
        val secret = secretIn(awaitEmail(2))

        val response = reset(secret, "password")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"code\":\"NOT_BREACHED\""
        // And the token was not spent by a refused attempt.
        reset(secret, NEW_PASSWORD).status shouldBe 200
    }

    @Test
    fun `oversized inputs are validation failures, not work`() {
        // Nothing valid is this long, and the bound is what stops a megabyte
        // being hashed on request. Found on the second review pass: every
        // other request body in the repo already had one.
        login(password = "x".repeat(MAX_PASSWORD_LENGTH + 1)).let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        }
        refresh("x".repeat(MAX_TOKEN_LENGTH + 1)).status shouldBe 422
        logout("x".repeat(MAX_TOKEN_LENGTH + 1)).status shouldBe 422
        hasher.matchesCalls.get() shouldBe 0
        hasher.dummyCalls.get() shouldBe 0
    }

    // ---- helpers -----------------------------------------------------------

    private fun registerAndActivate(
        activate: Boolean = true,
        password: String = PASSWORD,
    ) {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "email": "ada@example.com",
                      "password": "$password",
                      "displayName": "Ada",
                      "locale": "en",
                      "acceptedTermsVersion": "2026-09-01",
                      "over18": true
                    }
                    """.trimIndent()
            }.andReturn()
            .response.status shouldBe 201
        if (activate) jdbc.update("UPDATE users SET email_verified_at = now(), status = 'ACTIVE'")
        // Registration's own email; the counters start from a settled state.
        awaitEmail(1)
        hasher.calls.set(0)
    }

    private fun login(
        email: String = "ada@example.com",
        password: String = PASSWORD,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","password":"$password","deviceInfo":"test-device"}"""
            }.andReturn()
            .response

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$token"}"""
            }.andReturn()
            .response

    private fun logout(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/logout") {
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

    private fun reset(
        token: String,
        password: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/reset-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"$token","password":"$password"}"""
            }.andReturn()
            .response

    private fun awaitEmail(count: Int): EmailMessage {
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize count }
        return emails.sent.last()
    }

    private fun secretIn(email: EmailMessage): String =
        Regex("""token=([A-Za-z0-9_-]+)""").find(email.text)?.groupValues?.get(1) ?: error("no link in the email body")

    private fun refreshToken(body: String): String = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun accessToken(body: String): String = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun failedAttempts(): Int = jdbc.queryForObject("SELECT failed_attempts FROM credentials", Int::class.java)!!

    private fun secondsUntilUnlock(): Int =
        jdbc.queryForObject("SELECT CAST(EXTRACT(EPOCH FROM (locked_until - now())) AS int) FROM credentials", Int::class.java)!!

    private fun expireLock() = jdbc.update("UPDATE credentials SET locked_until = now() - interval '1 second'")

    @TestConfiguration
    class RecordingConfiguration {
        @Bean
        @Primary
        fun recordingPasswordHasher(properties: Argon2Properties): RecordingPasswordHasher =
            RecordingPasswordHasher(Argon2idPasswordHasher(properties))

        @Bean
        @Primary
        fun recordingEmailSender(): RecordingEmailSender = RecordingEmailSender()
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a different good password"
        val WAIT: Duration = Duration.ofSeconds(5)
        val SETTLE: Duration = Duration.ofMillis(500)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
