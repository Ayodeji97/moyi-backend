package com.moyi.identity.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `PUT` and `DELETE /api/v1/me/devices/current/push-token` (spec N1,
 * ADR-0044): the token goes to the device of the caller's session and is kept
 * by `notification`; every way a session ends forgets it; and a token that a
 * later sign-in of the same phone registers folds the earlier device row
 * (ADR-0025).
 *
 * The context is `SessionsEndpointTest`'s, so Spring reuses it.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
// LargeClass: every way a session ends, each with its own test. LongParameterList: what Spring hands the test.
@Suppress("LargeClass", "LongParameterList")
internal class PushTokenEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: JsonMapper,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val pushTokens: PushTokens,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE push_tokens, refresh_tokens, devices, verification_tokens, consent_records, credentials, users CASCADE",
        )
        emails.sent.clear()
    }

    @Test
    fun `a token is given to the device of the caller's session, and a second one replaces it`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)

        put(phone, "token-1").status shouldBe 204
        put(phone, "token-1").status shouldBe 204
        put(phone, "token-2").status shouldBe 204

        val row = jdbc.queryForMap("SELECT * FROM push_tokens")
        row["token"] shouldBe "token-2"
        row["platform"] shouldBe "ANDROID"
        row["device_id"] shouldBe deviceOf(phone)
        row["user_id"] shouldBe userId(ADA)
    }

    @Test
    fun `the same phone signed in twice is one device once its token says so, and both sessions stay`() {
        register(ADA)
        val first = login(ADA, device = PIXEL)
        put(first, "token-phone").status shouldBe 204
        clock.advance(Duration.ofMinutes(1))
        val second = login(ADA, device = PIXEL)

        put(second, "token-phone").status shouldBe 204

        tokenHolders() shouldContainExactly listOf(deviceOf(second))
        revokedAt(deviceOf(first)) shouldBe clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        revokedAt(deviceOf(second)) shouldBe null
        // ADR-0025 decision 4: revocation is explicit; the fold ends no session.
        sessionsOf(second) shouldBe 2
    }

    @Test
    fun `a phone that changed hands gives its token to the new owner, and folds nothing of the old one's`() {
        register(ADA)
        register(GRACE)
        val adas = login(ADA, device = PIXEL)
        put(adas, "token-shared").status shouldBe 204
        val graces = login(GRACE, device = PIXEL)

        put(graces, "token-shared").status shouldBe 204

        tokenHolders() shouldContainExactly listOf(deviceOf(graces))
        revokedAt(deviceOf(adas)) shouldBe null
    }

    @Test
    fun `DELETE forgets the token of the caller's device and no other, and saying it twice is the same`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        val watch = login(ADA, device = WATCH)
        put(phone, "token-phone").status shouldBe 204
        put(watch, "token-watch").status shouldBe 204

        delete(phone).status shouldBe 204
        delete(phone).status shouldBe 204

        tokenHolders() shouldContainExactly listOf(deviceOf(watch))
    }

    @Test
    fun `a session that began without a device has none to hold a token, and is told so with a 404`() {
        register(ADA)
        val bare = login(ADA, device = null)

        val refused = put(bare, "token-1")
        refused.status shouldBe 404
        refused.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        delete(bare).status shouldBe 404
        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `logging out forgets the device's token, and the access token that outlives it cannot give one back`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        put(phone, "token-phone").status shouldBe 204

        post("/api/v1/auth/logout", """{"refreshToken":"${phone.refreshToken}"}""").status shouldBe 204

        tokenHolders().shouldBeEmpty()
        // The access token lives out its fifteen minutes (doc 09 §3); its session does not.
        put(phone, "token-phone").status shouldBe 404
        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `ending another session forgets that session's token and keeps the caller's`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        val watch = login(ADA, device = WATCH)
        put(phone, "token-phone").status shouldBe 204
        put(watch, "token-watch").status shouldBe 204
        val phoneSession = familyOf(phone)

        mockMvc
            .delete("/api/v1/auth/sessions/$phoneSession") { bearer(watch) }
            .andReturn()
            .response.status shouldBe 204

        tokenHolders() shouldContainExactly listOf(deviceOf(watch))
    }

    @Test
    fun `logging out everywhere forgets every token of the caller's, and nobody else's`() {
        register(ADA)
        register(GRACE)
        val phone = login(ADA, device = PIXEL)
        val watch = login(ADA, device = WATCH)
        val graces = login(GRACE, device = PIXEL)
        put(phone, "token-phone").status shouldBe 204
        put(watch, "token-watch").status shouldBe 204
        put(graces, "token-grace").status shouldBe 204

        mockMvc
            .post("/api/v1/auth/logout-all") { bearer(phone) }
            .andReturn()
            .response.status shouldBe 204

        tokenHolders() shouldContainExactly listOf(deviceOf(graces))
    }

    @Test
    fun `a refresh token used twice ends its family, and the phone that may be the thief's is told nothing more`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        put(phone, "token-phone").status shouldBe 204
        post("/api/v1/auth/refresh", """{"refreshToken":"${phone.refreshToken}"}""").status shouldBe 200

        val reused = post("/api/v1/auth/refresh", """{"refreshToken":"${phone.refreshToken}"}""")

        reused.status shouldBe 401
        reused.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `a refresh by an account that can no longer sign in ends the session and forgets its token`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        put(phone, "token-phone").status shouldBe 204
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?::citext", ADA) shouldBe 1

        post("/api/v1/auth/refresh", """{"refreshToken":"${phone.refreshToken}"}""").status shouldBe 401

        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `a password reset signs every device out and forgets every token`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        put(phone, "token-phone").status shouldBe 204
        emails.sent.clear()

        post("/api/v1/auth/forgot-password", """{"email":"$ADA"}""").status shouldBe 202
        await().atMost(Duration.ofSeconds(5)).untilAsserted { emails.sent shouldHaveSize 1 }
        val secret = Regex("""token=([A-Za-z0-9_-]+)""").find(emails.sent.single().text)!!.groupValues[1]
        post("/api/v1/auth/reset-password", """{"token":"$secret","password":"a different good password"}""").status shouldBe 200

        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `a registration that waits on a logout of the same session finds it ended, and keeps no token`() {
        // Both take the per-user sessions lock (ADR-0021). Without it in the registration, the PUT would read
        // the session as live, wait for nothing, and keep a token for a session the logout had just ended.
        register(ADA)
        val phone = login(ADA, device = PIXEL)
        val user = userId(ADA)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holderPid = CompletableFuture<Int>()
            val logout =
                pool.submit {
                    transactions.execute {
                        jdbc.queryForObject(
                            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(1, hashtext(CAST(? AS text)))) AS held",
                            Int::class.java,
                            user,
                        )
                        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ?", user)
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            val pid = holderPid.get(10, TimeUnit.SECONDS)
            val registration = pool.submit<MockHttpServletResponse> { put(phone, "token-late") }

            await().atMost(Duration.ofSeconds(10)).until { registration.isDone || blockedBy(pid) }
            registration.isDone shouldBe false

            release.countDown()
            logout.get(10, TimeUnit.SECONDS)
            registration.get(10, TimeUnit.SECONDS).status shouldBe 404
        } finally {
            pool.shutdownNow()
        }
        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `two people registering one token at once end with the later one holding it, no 500 and the token in no log line`() {
        // The review of #96: both registrations found nobody holding the token, so the second insert waited on
        // the first's uncommitted row in the unique index and then failed, a 500 whose message quoted the token.
        register(ADA)
        register(GRACE)
        val adas = login(ADA, device = PIXEL)
        val graces = login(GRACE, device = PIXEL)
        val adasDevice = deviceOf(adas)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(captured)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holderPid = CompletableFuture<Int>()
            val first =
                pool.submit {
                    transactions.execute {
                        pushTokens.register(PushTokenRegistration(adasDevice, userId(ADA), PushPlatform.ANDROID, PushToken(RACED_TOKEN)))
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            val pid = holderPid.get(10, TimeUnit.SECONDS)
            val second = pool.submit<MockHttpServletResponse> { put(graces, RACED_TOKEN) }

            await().atMost(Duration.ofSeconds(10)).until { second.isDone || blockedBy(pid) }
            second.isDone shouldBe false

            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS).status shouldBe 204
        } finally {
            pool.shutdownNow()
            root.detachAppender(captured)
        }
        tokenHolders() shouldContainExactly listOf(deviceOf(graces))
        val lines =
            captured.list.map { event -> event.formattedMessage + (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "") }
        lines.filter { RACED_TOKEN in it }.shouldBeEmpty()
    }

    @Test
    fun `a token that is blank, has whitespace in it or is too long is refused, naming the field`() {
        register(ADA)
        val phone = login(ADA, device = PIXEL)

        for (bad in listOf("", "two words", "x".repeat(1025))) {
            val refused = put(phone, bad)
            refused.status shouldBe 422
            refused.contentAsString shouldContain "\"field\":\"token\""
            // One mistake, one violation: blank is @NotBlank's, too long is @Size's, the rest PushToken's.
            Regex("\"field\":\"token\"").findAll(refused.contentAsString).count() shouldBe 1
        }
        put(phone, "two words").contentAsString shouldContain "a push token has no whitespace in it"
        tokenHolders().shouldBeEmpty()
    }

    @Test
    fun `the push token routes need a bearer`() {
        mockMvc
            .put("/api/v1/me/devices/current/push-token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"token-1"}"""
            }.andReturn()
            .response.status shouldBe 401
        mockMvc
            .delete("/api/v1/me/devices/current/push-token")
            .andReturn()
            .response.status shouldBe 401
    }

    private class Signed(
        val accessToken: String,
        val refreshToken: String,
    )

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(session: Signed) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${session.accessToken}")
    }

    private fun put(
        session: Signed,
        token: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/me/devices/current/push-token") {
                bearer(session)
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"$token"}"""
            }.andReturn()
            .response

    private fun delete(session: Signed): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/me/devices/current/push-token") { bearer(session) }
            .andReturn()
            .response

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

    private fun register(email: String) {
        post(
            "/api/v1/auth/register",
            """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                """"acceptedTermsVersion":"2026-09-01","over18":true}""",
        ).status shouldBe 201
    }

    private fun login(
        email: String,
        device: String?,
    ): Signed {
        val response =
            post("/api/v1/auth/login", """{"email":"$email","password":"$PASSWORD"${device?.let { ""","device":$it""" } ?: ""}}""")
        response.status shouldBe 200
        val body = json.readTree(response.contentAsString)
        return Signed(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    private fun sessionsOf(session: Signed): Int {
        val response = mockMvc.get("/api/v1/auth/sessions") { bearer(session) }.andReturn().response
        return json.readTree(response.contentAsString)["sessions"].size()
    }

    /** The access token's `sid`: the family the session is. */
    private fun familyOf(session: Signed): UUID {
        val payload = session.accessToken.split('.')[1]
        val claims =
            json.readTree(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(payload),
            )
        return UUID.fromString(claims["sid"].asText())
    }

    private fun deviceOf(session: Signed): UUID =
        jdbc.queryForObject(
            "SELECT DISTINCT device_id FROM refresh_tokens WHERE family_id = ?",
            UUID::class.java,
            familyOf(session),
        )!!

    private fun userId(email: String): UUID = jdbc.queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID::class.java, email)!!

    private fun tokenHolders(): List<UUID> = jdbc.queryForList("SELECT device_id FROM push_tokens", UUID::class.java).filterNotNull()

    private fun revokedAt(device: UUID): java.time.Instant? =
        jdbc.queryForObject("SELECT revoked_at FROM devices WHERE id = ?", java.sql.Timestamp::class.java, device)?.toInstant()

    private fun blockedBy(pid: Int): Boolean =
        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, pid)!! > 0

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val RACED_TOKEN = "raced~push~token~secret"
        const val ADA = "ada@example.com"
        const val GRACE = "grace@example.com"
        const val PIXEL = """{"platform":"ANDROID","appVersion":"1.0.3","osVersion":"16"}"""
        const val WATCH = """{"platform":"WEAR","appVersion":"1.0.3","osVersion":"5.1"}"""

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
