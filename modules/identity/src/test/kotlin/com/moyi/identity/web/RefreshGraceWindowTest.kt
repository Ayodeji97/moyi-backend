package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Issue #136 and ADR-0021 as amended on 2026-11-08: a refresh whose answer was
 * lost may be retried within the grace window, while the successor it was
 * never given is still unused. The retry gets a new pair and voids that
 * successor; nobody is emailed. Outside the window, or once the successor has
 * been used, it is a reuse exactly as before — and a voided successor that
 * comes back later is a reuse too, which is how a thief who replayed inside
 * the window is still caught, one step later.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
internal class RefreshGraceWindowTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val json: JsonMapper,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE refresh_tokens, devices, verification_tokens, consent_records, credentials, users CASCADE")
        emails.sent.clear()
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    @Test
    fun `a retry of the rotated token inside the window gets a new pair, voids the unused successor, and emails nobody`() {
        val first = signIn()
        val second = refreshed(first)

        clock.advance(GRACE) // the boundary itself is inside

        val retry = refresh(first)
        retry.status shouldBe 200
        val third = refreshTokenIn(retry)
        third shouldNotBe second
        // The normal pair, with the profile, like any refresh.
        retry.contentAsString shouldContain "\"accessToken\""
        retry.contentAsString shouldContain "\"user\""

        liveTokens() shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE voided_at IS NOT NULL", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Int::class.java) shouldBe 0
        refreshed(third) // the family goes on from the retry's token
        noReuseEmail()
    }

    @Test
    fun `one microsecond past the window, the same retry is a reuse that ends the family and emails`() {
        val first = signIn()
        val second = refreshed(first)

        clock.advance(GRACE.plusNanos(1_000))

        refresh(first).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        }
        liveTokens() shouldBe 0
        refresh(second).contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        awaitReuseEmail()
    }

    @Test
    fun `once the successor has been used, the old token is a reuse even inside the window`() {
        // Two parties hold the family: whoever used the successor, and whoever is presenting its predecessor.
        val first = signIn()
        val second = refreshed(first)
        val third = refreshed(second)

        refresh(first).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        }
        refresh(third).contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        awaitReuseEmail()
    }

    @Test
    fun `a thief who replays inside the window is caught when the voided successor comes back`() {
        // The adversarial case recorded in ADR-0021's amendment. The client rotated R1 and holds R2; a thief
        // with a copy of R1 replays it inside the window and gets R3, voiding R2, and even rotates R3 to R4.
        // When the client next refreshes with R2, that is a reuse of a voided token: the family ends, R4
        // with it, and the person is emailed.
        val first = signIn()
        val clients = refreshed(first)
        val thiefs = refreshed(first)
        clock.advance(Duration.ofMinutes(14)) // the client's access token is about to expire
        val thiefsNext = refreshed(thiefs)

        refresh(clients).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        }
        liveTokens() shouldBe 0
        refresh(thiefsNext).contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        awaitReuseEmail()
    }

    @Test
    fun `a voided successor is a reuse even inside the window, so two holders cannot take turns`() {
        // Were a voided token itself eligible for the grace retry, the client and the thief could each void
        // the other's token for as long as they kept inside the window, and nobody would be told.
        val first = signIn()
        val clients = refreshed(first)
        refreshed(first)

        refresh(clients).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        }
        liveTokens() shouldBe 0
        awaitReuseEmail()
    }

    @Test
    fun `the window runs from the first rotation and a retry does not extend it`() {
        val first = signIn()
        refreshed(first)
        clock.advance(Duration.ofSeconds(20))
        refreshed(first)

        clock.advance(Duration.ofSeconds(11)) // 31 s after the rotation, 11 s after the retry

        refresh(first).contentAsString shouldContain "\"code\":\"TOKEN_REUSE_DETECTED\""
        liveTokens() shouldBe 0
    }

    @Test
    fun `a retry by an account that can no longer sign in ends the session as invalid, not a reuse`() {
        val first = signIn()
        refreshed(first)
        jdbc.update("UPDATE users SET status = 'SUSPENDED'") shouldBe 1

        refresh(first).contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        liveTokens() shouldBe 0
        noReuseEmail()
    }

    @Test
    fun `two simultaneous retries of one token both get a pair, one after the other, and leave one live token`() {
        // A client retry racing itself. The per-user sessions lock is held here so that both requests are
        // known to be waiting on it before either runs: then they run one after the other, the second voiding
        // the first's successor, and the family never has two live tokens.
        val first = signIn()
        refreshed(first)
        val user = jdbc.queryForObject("SELECT id FROM users", UUID::class.java)!!
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val holderPid = CompletableFuture<Int>()
            val holder =
                pool.submit {
                    transactions.execute {
                        jdbc.queryForObject(
                            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(1, hashtext(CAST(? AS text)))) AS held",
                            Int::class.java,
                            user,
                        )
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            val pid = holderPid.get(10, TimeUnit.SECONDS)
            val retries = List(2) { pool.submit<MockHttpServletResponse> { refresh(first) } }

            await().atMost(Duration.ofSeconds(10)).until { retries.any { it.isDone } || waitingOn(pid) == 2 }
            retries.none { it.isDone } shouldBe true

            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            val responses = retries.map { it.get(10, TimeUnit.SECONDS) }
            responses.map { it.status } shouldBe listOf(200, 200)
        } finally {
            pool.shutdownNow()
        }
        liveTokens() shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE voided_at IS NOT NULL", Int::class.java) shouldBe 2
        noReuseEmail()
    }

    @Test
    fun `a refresh waits on the token's row, and then reads what the holder committed`() {
        // The row lock (SELECT … FOR UPDATE) under the sessions lock. The holder here takes only the row
        // lock and revokes the family, as a writer that skipped the sessions lock would. The refresh must
        // wait for it and then see the family revoked — invalid, nobody emailed. Reading the row without
        // the lock, it saw the token live, tried to rotate it, found the compare-and-set lost, and called
        // that a reuse: an email to the person for a theft that never happened.
        val first = signIn()
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holderPid = CompletableFuture<Int>()
            val holder =
                pool.submit {
                    transactions.execute {
                        jdbc.queryForList("SELECT id FROM refresh_tokens FOR UPDATE")
                        jdbc.update("UPDATE refresh_tokens SET revoked_at = now()")
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            val pid = holderPid.get(10, TimeUnit.SECONDS)
            val waiting = pool.submit<MockHttpServletResponse> { refresh(first) }

            await().atMost(Duration.ofSeconds(10)).until { waiting.isDone || waitingOn(pid) == 1 }
            waiting.isDone shouldBe false

            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            waiting.get(10, TimeUnit.SECONDS).contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        } finally {
            pool.shutdownNow()
        }
        noReuseEmail()
    }

    // ---- helpers -----------------------------------------------------------

    /** Registers and signs Ada in; returns her refresh token once registration's own email has gone. */
    private fun signIn(): String {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"ada@example.com","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 1 }
        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"ada@example.com","password":"$PASSWORD","device":$PIXEL}"""
                }.andReturn()
                .response
        response.status shouldBe 200
        return refreshTokenIn(response)
    }

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$token"}"""
            }.andReturn()
            .response

    /** A refresh that must succeed; its new refresh token. */
    private fun refreshed(token: String): String {
        val response = refresh(token)
        response.status shouldBe 200
        return refreshTokenIn(response)
    }

    private fun refreshTokenIn(response: MockHttpServletResponse): String = json.readTree(response.contentAsString)["refreshToken"].asText()

    private fun liveTokens(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens WHERE rotated_at IS NULL AND revoked_at IS NULL AND expires_at > now()",
            Int::class.java,
        )!!

    /** How many backends are blocked by [pid] right now. */
    private fun waitingOn(pid: Int): Int =
        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, pid)!!

    private fun awaitReuseEmail() {
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 2 }
        emails.sent.last().subject shouldContain "was ended"
    }

    /**
     * The notice goes out after the commit on another thread, so its absence is the one thing here a latch
     * cannot show; the settle is the existing pattern in `LoginEndpointTest`. The synchronous half — no row
     * revoked by a reuse — is asserted by the tests themselves.
     */
    private fun noReuseEmail() {
        Thread.sleep(SETTLE.toMillis())
        emails.sent shouldHaveSize 1
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val PIXEL = """{"platform":"ANDROID","appVersion":"1.0.3","osVersion":"16"}"""
        val GRACE: Duration = Duration.ofSeconds(30)
        val WAIT: Duration = Duration.ofSeconds(5)
        val SETTLE: Duration = Duration.ofMillis(500)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
