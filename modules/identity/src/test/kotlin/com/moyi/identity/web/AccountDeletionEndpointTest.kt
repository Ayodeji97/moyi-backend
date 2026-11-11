package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountDeletions
import com.moyi.identity.api.UserDirectory
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * FR-008 end to end in identity (ADR-0058): the request and its cancellation over HTTP, the
 * pending state on `GET /me`, the email that says how to cancel, and the job's half that is
 * identity's own: the account anonymised, every way back into it closed, and one
 * `AccountDeleted` event for the other modules to act on.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // One flow, request to erasure; split, each half would re-state the other's set-up.
internal class AccountDeletionEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: JsonMapper,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val deletions: AccountDeletions,
    @Autowired private val directory: UserDirectory,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun start() {
        clock.set(Instant.parse("2026-11-09T10:00:00Z"))
    }

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE deletion_requests, refresh_tokens, devices, verification_tokens, consent_records, credentials, users, " +
                "outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    // --- the request ---

    @Test
    fun `a request with the password is 202 with the two windows, and GET me shows it pending`() {
        val ada = signUp("ada@example.com")

        val response = request(ada.access, PASSWORD)

        response.status shouldBe 202
        val body = json.readTree(response.contentAsString)
        val requestedAt = now()
        body["requestedAt"].asText() shouldBe requestedAt.toString()
        body["cancellableUntil"].asText() shouldBe requestedAt.plus(Duration.ofDays(14)).toString()
        body["deleteBy"].asText() shouldBe requestedAt.plus(Duration.ofDays(30)).toString()

        val pending = me(ada.access)["deletionRequest"]
        pending["requestedAt"].asText() shouldBe requestedAt.toString()
        pending["cancellableUntil"].asText() shouldBe requestedAt.plus(Duration.ofDays(14)).toString()
        pending["deleteBy"].asText() shouldBe requestedAt.plus(Duration.ofDays(30)).toString()
    }

    @Test
    fun `GET me says null when nothing is pending`() {
        val ada = signUp("ada@example.com")

        me(ada.access)["deletionRequest"].isNull shouldBe true
    }

    @Test
    fun `asking again while one is pending is the same 202 with the same dates, and one row`() {
        val ada = signUp("ada@example.com")
        val first = request(ada.access, PASSWORD).contentAsString
        clock.advance(Duration.ofDays(3))

        val second = request(login("ada@example.com").access, PASSWORD)

        second.status shouldBe 202
        second.contentAsString shouldBe first
        jdbc.queryForObject("SELECT count(*) FROM deletion_requests", Int::class.java) shouldBe 1
    }

    @Test
    fun `a wrong password is 403 INVALID_CREDENTIALS and records nothing, nor counts towards the sign-in lock`() {
        val ada = signUp("ada@example.com")

        val refused = request(ada.access, "not the password at all")

        // #166: 403, not 401. On a signed-in route a client reads 401 as "the access
        // token was refused" and refreshes; the token here is fine, the password typed
        // is not (ADR-0058 decision 1, amended 2026-11-10).
        refused.status shouldBe 403
        refused.contentAsString shouldContain "\"code\":\"INVALID_CREDENTIALS\""
        // Not the bearer's refusal: no challenge, which only a refused token carries.
        refused.getHeader(HttpHeaders.WWW_AUTHENTICATE).shouldBeNull()
        // And the session it was sent on still works.
        meResponse(ada.access).status shouldBe 200
        jdbc.queryForObject("SELECT count(*) FROM deletion_requests", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT failed_attempts FROM credentials", Int::class.java) shouldBe 0
        me(ada.access)["deletionRequest"].isNull shouldBe true
    }

    @Test
    fun `a missing or blank password is 422 VALIDATION_FAILED naming the field`() {
        val ada = signUp("ada@example.com")

        for (body in listOf("{}", """{"password":""}""", """{"password":"   "}""")) {
            val refused = post(ada.access, body)
            refused.status shouldBe 422
            refused.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
            refused.contentAsString shouldContain "password"
        }
        jdbc.queryForObject("SELECT count(*) FROM deletion_requests", Int::class.java) shouldBe 0
    }

    @Test
    fun `no token is 401 UNAUTHENTICATED on both routes`() {
        val noToken =
            mockMvc
                .post("/api/v1/me/deletion-request") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"password":"$PASSWORD"}"""
                }.andReturn()
                .response
        noToken.status shouldBe 401
        // #166: the challenge belongs to a refused token, and only to one; the wrong
        // password above is a 403 without it.
        noToken.getHeader(HttpHeaders.WWW_AUTHENTICATE).shouldNotBeNull()
        mockMvc
            .delete("/api/v1/me/deletion-request")
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `the person is emailed how to cancel, once per request, and the email carries no password`() {
        val ada = signUp("ada@example.com", verified = true)

        request(ada.access, PASSWORD).status shouldBe 202
        request(ada.access, PASSWORD).status shouldBe 202

        await().atMost(WAIT).until { emails.sent.isNotEmpty() }
        val mail = emails.sent.single { it.subject.contains("delet", ignoreCase = true) }
        mail.to shouldBe "ada@example.com"
        mail.text shouldContain "Delete my account"
        mail.text shouldContain "after 23 November 2026 at 10:00 UTC"
        mail.text shouldNotContain PASSWORD
        // The repeat is the same request, under the same key: a provider that remembers keys sends it once.
        mail.idempotencyKey shouldContain "deletion-requested-"
    }

    @Test
    fun `an unconfirmed address is not written to`() {
        val ada = signUp("ada@example.com", verified = false)

        request(ada.access, PASSWORD).status shouldBe 202

        Thread.sleep(SETTLE.toMillis()) // Nothing to wait for: the absence of a send has no event to synchronise on.
        emails.sent.filter { it.subject.contains("delet", ignoreCase = true) }.shouldBeEmpty()
    }

    @Test
    fun `while it is pending the person still signs in, and their other sessions are not ended`() {
        val ada = signUp("ada@example.com")
        val other = login("ada@example.com")

        request(ada.access, PASSWORD).status shouldBe 202

        refresh(other.refresh).status shouldBe 200
        login("ada@example.com").access.isNotBlank() shouldBe true
    }

    // --- cancelling ---

    @Test
    fun `cancelling inside the window is 204, GET me is clear again, and a second cancel is 404`() {
        val ada = signUp("ada@example.com")
        request(ada.access, PASSWORD)
        clock.advance(Duration.ofDays(13))
        val later = login("ada@example.com").access

        cancel(later).status shouldBe 204

        me(later)["deletionRequest"].isNull shouldBe true
        val again = cancel(later)
        again.status shouldBe 404
        again.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
    }

    @Test
    fun `nothing pending and a window that has closed are one 404, byte for byte`() {
        val ada = signUp("ada@example.com")
        val bea = signUp("bea@example.com")
        request(bea.access, PASSWORD)
        clock.advance(Duration.ofDays(14))

        val nothing = cancel(login("ada@example.com").access)
        val closed = cancel(login("bea@example.com").access)

        nothing.status shouldBe 404
        closed.status shouldBe 404
        closed.contentAsString.replace("\"traceId\":\"[^\"]*\"".toRegex(), "") shouldBe
            nothing.contentAsString.replace("\"traceId\":\"[^\"]*\"".toRegex(), "")
        jdbc.queryForObject("SELECT count(*) FROM deletion_requests WHERE cancelled_at IS NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `after a cancellation a new request starts new windows`() {
        val ada = signUp("ada@example.com")
        request(ada.access, PASSWORD)
        cancel(ada.access)
        clock.advance(Duration.ofDays(2))

        val again = json.readTree(request(login("ada@example.com").access, PASSWORD).contentAsString)

        again["requestedAt"].asText() shouldBe now().toString()
    }

    // --- carrying it out (identity's half) ---

    @Test
    fun `nothing is carried out inside the window, nor a cancelled request after it`() {
        val ada = signUp("ada@example.com")
        val bea = signUp("bea@example.com")
        request(ada.access, PASSWORD)
        request(bea.access, PASSWORD)
        cancel(bea.access)
        clock.advance(Duration.ofDays(14).minusSeconds(1))

        deletions.executeDue(LIMIT) shouldBe 0
        clock.advance(Duration.ofDays(30))
        deletions.executeDue(LIMIT) shouldBe 1

        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, bea.id) shouldBe "PENDING_VERIFICATION"
    }

    @Test
    fun `carried out, the account is anonymised and every way back into it is closed`() {
        val ada = signUp("ada@example.com", verified = true)
        val other = login("ada@example.com")
        val phone = login("ada@example.com", device = PIXEL)
        mockMvc
            .put("/api/v1/me/devices/current/push-token") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${phone.access}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"ada-phone-token"}"""
            }.andReturn()
            .response.status shouldBe 204
        jdbc.queryForObject("SELECT count(*) FROM push_tokens WHERE user_id = ?", Int::class.java, ada.id) shouldBe 1
        request(ada.access, PASSWORD)
        clock.advance(Duration.ofDays(14))

        deletions.executeDue(LIMIT) shouldBe 1

        val row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", ada.id)
        row["status"] shouldBe "DELETED"
        row["email"].toString() shouldNotContain "ada"
        row["display_name"].toString() shouldNotContain "Ada"
        row["email_verified_at"].shouldBeNull()
        row["deleted_at"].toString().isNotBlank() shouldBe true
        for (table in listOf("credentials", "refresh_tokens", "devices", "verification_tokens", "consent_records", "push_tokens")) {
            jdbc.queryForObject("SELECT count(*) FROM $table WHERE user_id = ?", Int::class.java, ada.id) shouldBe 0
        }
        directory.find(ada.id).shouldBeNull()
        directory.findAll(listOf(ada.id)).keys.shouldBeEmpty()

        meResponse(ada.access).status shouldBe 401 // the access token issued before is dead
        refresh(other.refresh).status shouldBe 401
        loginResponse("ada@example.com").status shouldBe 401
        // The address is free again: the person may come back as somebody new.
        signUp("ada@example.com").id
    }

    @Test
    fun `carried out, it says so once in the outbox, and a second run finds nothing to do`() {
        val ada = signUp("ada@example.com")
        request(ada.access, PASSWORD)
        clock.advance(Duration.ofDays(15))

        deletions.executeDue(LIMIT) shouldBe 1
        deletions.executeDue(LIMIT) shouldBe 0

        val events = jdbc.queryForList("SELECT event_type, aggregate_id FROM outbox_events")
        events shouldHaveSize 1
        events.single()["event_type"] shouldBe "AccountDeleted"
        events.single()["aggregate_id"] shouldBe ada.id
        jdbc.queryForObject("SELECT executed_at IS NOT NULL FROM deletion_requests", Boolean::class.java) shouldBe true
        cancel(ada.access).status shouldBe 401
    }

    @Test
    fun `a run that fails part-way leaves the account as it was, and the next run carries it out`() {
        val ada = signUp("ada@example.com")
        request(ada.access, PASSWORD)
        clock.advance(Duration.ofDays(15))
        jdbc.execute(
            "CREATE FUNCTION fail_deletion() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'test'; END \$\$",
        )
        jdbc.execute("CREATE TRIGGER fail_deletion BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION fail_deletion()")
        try {
            runCatching { deletions.executeDue(LIMIT) }
            jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada.id) shouldBe "PENDING_VERIFICATION"
            jdbc.queryForObject("SELECT count(*) FROM credentials WHERE user_id = ?", Int::class.java, ada.id) shouldBe 1
            jdbc.queryForObject("SELECT executed_at IS NULL FROM deletion_requests", Boolean::class.java) shouldBe true
        } finally {
            jdbc.execute("DROP TRIGGER fail_deletion ON outbox_events")
            jdbc.execute("DROP FUNCTION fail_deletion()")
        }

        deletions.executeDue(LIMIT) shouldBe 1
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada.id) shouldBe "DELETED"
    }

    @Test
    fun `one account that cannot be carried out does not hold up the others in the run`() {
        val ada = signUp("ada@example.com")
        val bea = signUp("bea@example.com")
        request(ada.access, PASSWORD)
        request(bea.access, PASSWORD)
        clock.advance(Duration.ofDays(15))
        jdbc.execute(
            "CREATE FUNCTION fail_ada() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                "IF NEW.aggregate_id = '${ada.id}' THEN RAISE EXCEPTION 'test'; END IF; RETURN NEW; END \$\$",
        )
        jdbc.execute("CREATE TRIGGER fail_ada BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION fail_ada()")
        try {
            deletions.executeDue(LIMIT) shouldBe 1
        } finally {
            jdbc.execute("DROP TRIGGER fail_ada ON outbox_events")
            jdbc.execute("DROP FUNCTION fail_ada()")
        }

        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada.id) shouldBe "PENDING_VERIFICATION"
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, bea.id) shouldBe "DELETED"
    }

    @Test
    fun `an access token issued in the moment before it is carried out is refused after it`() {
        val ada = signUp("ada@example.com")
        request(ada.access, PASSWORD)
        clock.advance(Duration.ofDays(14))
        val fresh = login("ada@example.com").access
        meResponse(fresh).status shouldBe 200

        deletions.executeDue(LIMIT) shouldBe 1

        // Same second as the erasure: the bump is what refuses it, not its fifteen minutes.
        meResponse(fresh).status shouldBe 401
    }

    @Test
    fun `it waits for a sign-in that holds the account's sessions, so none is left behind it`() {
        val ada = signUp("ada@example.com")
        request(ada.access, PASSWORD)
        clock.advance(Duration.ofDays(14))
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // A sign-in in flight: it has taken the sessions lock and not yet committed its family.
            val signIn =
                pool.submit {
                    transactions.executeWithoutResult {
                        jdbc.queryForList("SELECT pg_advisory_xact_lock(1, hashtext(CAST(? AS text)))", ada.id.toString())
                        holding.countDown()
                        release.await()
                    }
                }
            holding.await()
            val run = pool.submit<Int> { deletions.executeDue(LIMIT) }
            await().atMost(WAIT).until {
                jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Int::class.java)!! > 0
            }
            jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada.id) shouldBe "PENDING_VERIFICATION"
            release.countDown()
            signIn.get(WAIT.seconds, TimeUnit.SECONDS)
            run.get(WAIT.seconds, TimeUnit.SECONDS) shouldBe 1
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada.id) shouldBe "DELETED"
    }

    // --- helpers ---

    private fun request(
        access: String,
        password: String,
    ): MockHttpServletResponse = post(access, """{"password":"$password"}""")

    private fun post(
        access: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/me/deletion-request") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $access")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun cancel(access: String): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/me/deletion-request") { header(HttpHeaders.AUTHORIZATION, "Bearer $access") }
            .andReturn()
            .response

    private fun meResponse(access: String): MockHttpServletResponse =
        mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $access") }.andReturn().response

    private fun me(access: String): JsonNode {
        val response = meResponse(access)
        return if (response.status == 200) json.readTree(response.contentAsString) else json.createObjectNode()
    }

    private fun signUp(
        email: String,
        verified: Boolean = false,
    ): Account {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ? AND deleted_at IS NULL", UUID::class.java, email)!!
        if (verified) jdbc.update("UPDATE users SET email_verified_at = now(), status = 'ACTIVE' WHERE id = ?", id)
        emails.sent.clear()
        return Account(id, login(email).access)
    }

    private fun loginResponse(
        email: String,
        device: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","password":"$PASSWORD"${device?.let { ""","device":$it""" } ?: ""}}"""
            }.andReturn()
            .response

    private fun login(
        email: String,
        device: String? = null,
    ): Signed {
        val response = loginResponse(email, device)
        response.status shouldBe 200
        val body = json.readTree(response.contentAsString)
        return Signed(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$token"}"""
            }.andReturn()
            .response

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private class Account(
        val id: UUID,
        val access: String,
    )

    private class Signed(
        val access: String,
        val refresh: String,
    )

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val LIMIT = 100
        const val PIXEL = """{"platform":"ANDROID","appVersion":"1.0.3","osVersion":"16"}"""
        val WAIT: Duration = Duration.ofSeconds(5)
        val SETTLE: Duration = Duration.ofMillis(500)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
