package com.moyi.identity.web

import com.moyi.common.core.IdGenerator
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.IssuedAccessToken
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.Gate
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.database.DeviceStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.identity.infra.security.Argon2Properties
import com.moyi.identity.infra.security.Argon2idPasswordHasher
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.infra.sessionsLockWaitersOf
import com.moyi.identity.service.IssueSessionTokens
import com.moyi.identity.service.ResetPassword
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Spec ruling 7 and §8's first row: a sign-in against a suspension, each order, and against a
 * password reset. Two beans are wrapped, not replaced, each with a [Gate] the test arms: the
 * password hasher stops a sign-in inside its password check (outside any transaction), and the
 * access-token issuer stops it while minting (inside its transaction, holding the sessions
 * lock). Synchronised on those latches and on `pg_locks`, each with a deadline that fails the
 * test; never a sleep.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginSuspensionRaceTest.GateConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
@Suppress("LongParameterList") // The port, the reset, HTTP, the gates, the clock, the mail and the transactions.
internal class LoginSuspensionRaceTest(
    @Autowired private val suspension: AccountSuspension,
    @Autowired private val resets: ResetPassword,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val hasher: GatedPasswordHasher,
    @Autowired private val issuer: GatedSessionTokens,
    @Autowired private val clock: MutableClock,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var pool: ExecutorService

    @BeforeEach
    fun start() {
        clock.set(Instant.parse("2026-11-13T10:00:00.123456Z"))
        pool = Executors.newFixedThreadPool(2)
    }

    @AfterEach
    fun clear() {
        hasher.gate.disarm()
        hasher.hashGate.disarm()
        issuer.gate.disarm()
        pool.shutdownNow()
        pool.awaitTermination(WAIT.seconds, TimeUnit.SECONDS)
        jdbc.execute(
            "TRUNCATE TABLE account_suspensions, refresh_tokens, devices, verification_tokens, consent_records, credentials, " +
                "users, outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    @Test
    fun `a suspension that commits while the password is being checked wins`() {
        val (ada, email) = signUp()
        val stopped = hasher.gate.arm()

        val signIn = pool.submit<MockHttpServletResponse> { login(email) }
        stopped.awaitReached()
        // The password was judged right against an ACTIVE account; the account is then suspended.
        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        stopped.release()

        signIn.get(WAIT.seconds, TimeUnit.SECONDS).status shouldBe 401
        // No family at all: the account had never signed in, so any row would be this sign-in's,
        // created after the suspension had revoked everything.
        refreshTokens(ada) shouldBe 0
    }

    @Test
    fun `a sign-in that holds the sessions lock finishes first and is then undone`() {
        val (ada, email) = signUp()
        val stopped = issuer.gate.arm()

        val signIn = pool.submit<MockHttpServletResponse> { login(email) }
        stopped.awaitReached()
        val suspended = pool.submit<SuspensionResult> { transactions.execute { suspension.suspend(ada) } }
        // Only if the token is minted inside the sign-in's transaction is the suspension held here.
        awaitSessionsLockWaiter(ada)
        // The suspension asked at 10:00:00; it takes effect only once the sign-in commits, later.
        // Stamped when asked, its bump (10:00:01) would precede this token's `iat` (10:00:05).
        clock.advance(Duration.ofSeconds(5))
        stopped.release()

        val response = signIn.get(WAIT.seconds, TimeUnit.SECONDS)
        response.status shouldBe 200
        suspended.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED

        liveRefreshTokens(ada) shouldBe 0
        refresh(refreshToken(response.contentAsString)).status shouldBe 401
        clock.advance(Duration.ofSeconds(1))
        val me = mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(response.contentAsString)}") }
        me.andReturn().response.status shouldBe 401
    }

    /**
     * §8's refresh row, for the access token as well as the refresh token: a refresh mints under
     * the sessions lock, so a suspension waiting on it stamps after this token's `iat`.
     */
    @Test
    fun `a refresh that holds the sessions lock finishes first and its access token is then refused`() {
        val (ada, email) = signUp()
        val signedIn = login(email)
        signedIn.status shouldBe 200
        val stopped = issuer.gate.arm()

        val rotation = pool.submit<MockHttpServletResponse> { refresh(refreshToken(signedIn.contentAsString)) }
        stopped.awaitReached()
        val suspended = pool.submit<SuspensionResult> { transactions.execute { suspension.suspend(ada) } }
        // Only if the token is minted inside the rotation's transaction is the suspension held here.
        awaitSessionsLockWaiter(ada)
        // Minted after the commit, the token would be stamped later than a suspension that ran in
        // between; held here, the suspension can only stamp after the rotation commits.
        clock.advance(Duration.ofSeconds(5))
        stopped.release()

        val response = rotation.get(WAIT.seconds, TimeUnit.SECONDS)
        response.status shouldBe 200
        suspended.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED

        liveRefreshTokens(ada) shouldBe 0
        refresh(refreshToken(response.contentAsString)).status shouldBe 401
        clock.advance(Duration.ofSeconds(1))
        val me = mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(response.contentAsString)}") }
        me.andReturn().response.status shouldBe 401
    }

    @Test
    fun `a password reset committed during the check also wins`() {
        val (_, email) = signUp()
        forgot(email).status shouldBe 202
        val secret = awaitResetSecret(email)
        val stopped = hasher.gate.arm()

        val signIn = pool.submit<MockHttpServletResponse> { login(email) }
        stopped.awaitReached()
        // The old password was judged right; it is then replaced. The account can still sign in,
        // so only the comparison of the hash read under the lock with the one verified refuses.
        resets.reset(VerificationSecret(secret), Password.of(NEW_PASSWORD))
        stopped.release()

        signIn.get(WAIT.seconds, TimeUnit.SECONDS).status shouldBe 401
    }

    /**
     * A reset link sent before a suspension: the link is judged live and the new password hashed,
     * and the suspension commits before the reset's transaction opens. Refused under the sessions
     * lock, with exactly the answer a spent link gets, and the password is unchanged.
     */
    @Test
    fun `a reset link sent before a suspension is refused after it commits, as a spent link is`() {
        val (ada, email) = signUp()
        forgot(email).status shouldBe 202
        val secret = awaitResetSecret(email)
        val hashBefore = passwordHash(ada)
        val stopped = hasher.hashGate.arm()

        val reset = pool.submit<MockHttpServletResponse> { resetByLink(secret) }
        stopped.awaitReached()
        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        stopped.release()

        val refused = reset.get(WAIT.seconds, TimeUnit.SECONDS)
        // The same link again is a spent one: the refusal must be that answer, byte for byte.
        val spent = resetByLink(secret)
        refused.status shouldBe 410
        refused.status shouldBe spent.status
        noInstance(refused.contentAsString) shouldBe noInstance(spent.contentAsString)
        passwordHash(ada) shouldBe hashBefore
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada) shouldBe "SUSPENDED"
    }

    // --- helpers ---

    /** As the smoke's `no_instance`: the bodies compared apart from the path that was typed. */
    private fun noInstance(body: String): String = body.replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private fun passwordHash(userId: UUID): String =
        jdbc.queryForObject("SELECT password_hash FROM credentials WHERE user_id = ?", String::class.java, userId)!!

    private fun resetByLink(secret: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/reset-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"$secret","password":"$NEW_PASSWORD"}"""
            }.andReturn()
            .response

    private fun awaitSessionsLockWaiter(userId: UUID) {
        await().atMost(WAIT).until { jdbc.sessionsLockWaitersOf(userId) > 0 }
    }

    private fun refreshTokens(userId: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Int::class.java, userId)!!

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
        // Registration's own email, settled, so a later one is the reset's.
        await().atMost(WAIT).until { emails.sent.toList().any { it.to == email } }
        emails.sent.clear()
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!! to email
    }

    private fun login(email: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","password":"$PASSWORD"}"""
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

    private fun awaitResetSecret(email: String): String {
        var secret: String? = null
        await().atMost(WAIT).until {
            secret =
                emails.sent
                    .toList()
                    .filter { it.to == email }
                    .firstNotNullOfOrNull { Regex("""token=([A-Za-z0-9_-]+)""").find(it.text)?.groupValues?.get(1) }
            secret != null
        }
        return secret!!
    }

    private fun refreshToken(body: String): String = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun accessToken(body: String): String = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    @TestConfiguration
    class GateConfiguration {
        @Bean
        @Primary
        fun gatedPasswordHasher(properties: Argon2Properties): GatedPasswordHasher = GatedPasswordHasher(Argon2idPasswordHasher(properties))

        @Bean
        @Primary
        fun gatedSessionTokens(
            tokens: RefreshTokenStore,
            devices: DeviceStore,
            ids: IdGenerator,
            secrets: TokenGenerator,
            accessTokens: AccessTokenIssuer,
        ): GatedSessionTokens = GatedSessionTokens(tokens, devices, ids, secrets, accessTokens)

        @Bean
        @Primary
        fun recordingEmailSender(): RecordingEmailSender = RecordingEmailSender()
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a different good password"
        val WAIT: Duration = Duration.ofSeconds(10)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}

/**
 * The real Argon2id hasher, with a [Gate] after `matches` (the sign-in's password check) and
 * another after `hash` (a reset's new password, hashed outside any transaction).
 */
internal class GatedPasswordHasher(
    private val delegate: PasswordHasher,
) : PasswordHasher by delegate {
    val gate = Gate()
    val hashGate = Gate()

    override fun matches(
        raw: String,
        hash: PasswordHash,
    ): Boolean = delegate.matches(raw, hash).also { gate.pass() }

    override fun hash(password: Password): PasswordHash = delegate.hash(password).also { hashGate.pass() }
}

/**
 * The real session-token issuer, with a [Gate] in front of minting the access token. Wrapped
 * here rather than at `AccessTokenIssuer` itself, whose constructor takes a `JwtEncoder` that
 * is not on this module's classpath; [accessTokenFor] is the one call through which a sign-in
 * reaches `AccessTokenIssuer.issue`.
 */
internal class GatedSessionTokens(
    tokens: RefreshTokenStore,
    devices: DeviceStore,
    ids: IdGenerator,
    secrets: TokenGenerator,
    accessTokens: AccessTokenIssuer,
) : IssueSessionTokens(tokens, devices, ids, secrets, accessTokens) {
    val gate = Gate()

    override fun accessTokenFor(
        userId: UserId,
        sessionId: UUID,
    ): IssuedAccessToken {
        gate.pass()
        return super.accessTokenFor(userId, sessionId)
    }
}
