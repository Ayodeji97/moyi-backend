package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.domain.CodeHash
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationCodeHasher
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.HmacVerificationCodeHasher
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.web.Gate
import com.moyi.identity.web.LoginEndpointTest
import com.moyi.identity.web.RecordingEmailSender
import com.moyi.identity.web.SessionsEndpointTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * A verification that straddles a suspension (admin console A3, found while building Task 1).
 * The typed-code path reads the account, then checks the code, then marks the address
 * verified, all in one transaction that holds no lock on the user's row until it writes. A
 * suspension that commits in between must survive the verification: the address is recorded
 * as verified, and nothing else of the row is written back from the earlier read.
 *
 * The code hasher is wrapped with a [Gate]: the verification stops there, after its read of
 * the account and before its write. Synchronised on that latch, with deadlines that fail the
 * test; never a sleep.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(
    VerifyEmailSuspensionRaceTest.GateConfiguration::class,
    LoginEndpointTest.RecordingConfiguration::class,
    SessionsEndpointTest.TimeConfiguration::class,
)
@Suppress("LongParameterList") // The port, HTTP, the gate, the clock, the mail and the transactions: each named.
internal class VerifyEmailSuspensionRaceTest(
    @Autowired private val suspension: AccountSuspension,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val codeHasher: GatedVerificationCodeHasher,
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
        pool = Executors.newSingleThreadExecutor()
    }

    @AfterEach
    fun clear() {
        codeHasher.gate.disarm()
        pool.shutdownNow()
        pool.awaitTermination(WAIT.seconds, TimeUnit.SECONDS)
        jdbc.execute(
            "TRUNCATE TABLE account_suspensions, refresh_tokens, devices, verification_tokens, consent_records, credentials, " +
                "users, outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    @Test
    fun `a suspension committed while the address is being verified survives the verification`() {
        val (ada, email, code) = register()
        val stopped = codeHasher.gate.arm()

        val verified = pool.submit<MockHttpServletResponse> { verifyByCode(email, code) }
        // The verification has read the account, PENDING_VERIFICATION, and holds its code's row.
        stopped.awaitReached()
        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        val bump = tokensInvalidBefore(ada)
        bump shouldNotBe null
        stopped.release()

        verified.get(WAIT.seconds, TimeUnit.SECONDS).status shouldBe 200
        status(ada) shouldBe "SUSPENDED"
        tokensInvalidBefore(ada) shouldBe bump
        openSpans(ada) shouldBe 1
        emailVerifiedAt(ada) shouldNotBe null

        clock.advance(Duration.ofSeconds(1))
        transactions.execute { suspension.lift(ada) }.outcome shouldBe SuspensionOutcome.LIFTED
        // The verification recorded while suspended counts (ruling 5).
        status(ada) shouldBe "ACTIVE"
    }

    // --- helpers ---

    private fun status(userId: UUID): String? = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, userId)

    private fun tokensInvalidBefore(userId: UUID): Instant? =
        jdbc.queryForObject("SELECT tokens_invalid_before FROM users WHERE id = ?", Timestamp::class.java, userId)?.toInstant()

    private fun emailVerifiedAt(userId: UUID): Instant? =
        jdbc.queryForObject("SELECT email_verified_at FROM users WHERE id = ?", Timestamp::class.java, userId)?.toInstant()

    private fun openSpans(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_suspensions WHERE user_id = ? AND lifted_at IS NULL",
            Int::class.java,
            userId,
        )!!

    private fun register(): Triple<UUID, String, String> {
        val email = "u-${UUID.randomUUID()}@example.com"
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        var code: String? = null
        await().atMost(WAIT).until {
            code =
                emails.sent
                    .toList()
                    .filter { it.to == email }
                    .firstNotNullOfOrNull { Regex("""(?m)^(\d{6})$""").find(it.text)?.groupValues?.get(1) }
            code != null
        }
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!!
        status(id) shouldBe "PENDING_VERIFICATION"
        return Triple(id, email, code!!)
    }

    private fun verifyByCode(
        email: String,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/verify-email") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email": "$email", "code": "$code"}"""
            }.andReturn()
            .response

    @TestConfiguration
    class GateConfiguration {
        @Bean
        @Primary
        fun gatedVerificationCodeHasher(real: HmacVerificationCodeHasher): GatedVerificationCodeHasher = GatedVerificationCodeHasher(real)
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        val WAIT: Duration = Duration.ofSeconds(10)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}

/** The real code hasher, with a [Gate] in front: the typed-code check, after it has read the account. */
internal class GatedVerificationCodeHasher(
    private val delegate: VerificationCodeHasher,
) : VerificationCodeHasher {
    val gate = Gate()

    override fun hash(
        tokenId: UUID,
        purpose: VerificationPurpose,
        code: VerificationCode,
    ): CodeHash {
        gate.pass()
        return delegate.hash(tokenId, purpose, code)
    }
}
