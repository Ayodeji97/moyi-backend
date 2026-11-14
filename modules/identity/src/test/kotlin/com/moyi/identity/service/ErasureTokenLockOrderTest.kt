package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.AccountDeletions
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordResetTokenExpiredException
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationCodeRejectedException
import com.moyi.identity.domain.VerificationRequested
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.domain.VerificationTokenExpiredException
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.web.LoginEndpointTest
import com.moyi.identity.web.RecordingEmailSender
import com.moyi.identity.web.SessionsEndpointTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * One lock order for the erasure and everything that spends or issues a verification token
 * (ADR-0066's owed item, closed by its amendment of 2026-11-14): **the user's sessions lock
 * first.** The erasure takes it, then the `users` row, then deletes `verification_tokens`. A
 * reset, a verification and the issuing of a code used to take a token row first (the consume,
 * the code's `FOR UPDATE`, the retirement of an older code) and the sessions lock or `users`
 * after it: the reverse, and Postgres broke the cycle by failing one side with `deadlock
 * detected`.
 *
 * Each test drives that interleaving. A connection holds the account's `credentials` row, so the
 * job stops at its `DELETE FROM credentials` holding the sessions lock and `users`; the other
 * side starts and is waited for until Postgres shows it blocked by the job's backend; the
 * connection commits, and the job goes on to `verification_tokens`. With the token taken first,
 * the other side holds a row the job now needs: a deadlock. With the sessions lock first, it
 * holds nothing, the job finishes, and the other side finds the token gone. Synchronised on
 * `pg_blocking_pids`, never a sleep.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(
    LoginEndpointTest.RecordingConfiguration::class,
    SessionsEndpointTest.TimeConfiguration::class,
    ErasureTokenLockOrderTest.RequestsConfiguration::class,
)
@Suppress("LongParameterList") // The job, the request, the three other sides, HTTP, the database and the events: each named.
internal class ErasureTokenLockOrderTest(
    @Autowired private val deletions: AccountDeletions,
    @Autowired private val deletion: AccountDeletion,
    @Autowired private val resets: ResetPassword,
    @Autowired private val verifications: VerifyEmail,
    @Autowired private val forgotten: RequestPasswordReset,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val dataSource: DataSource,
    @Autowired private val requested: RecordedVerificationRequests,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var pool: ExecutorService

    @BeforeEach
    fun start() {
        pool = Executors.newFixedThreadPool(2)
    }

    @AfterEach
    fun clear() {
        pool.shutdownNow()
        pool.awaitTermination(WAIT.seconds, TimeUnit.SECONDS)
        jdbc.execute(
            "TRUNCATE TABLE deletion_requests, refresh_tokens, devices, verification_tokens, consent_records, credentials, " +
                "users, outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    @Test
    fun `a password reset by link that meets the erasure waits for it, and finds its link gone`() {
        val ada = signUp()
        val secret = linkFrom(forgotPassword(ada))

        val outcome = againstTheErasure(ada) { resets.reset(VerificationSecret(secret), Password.of(NEW_PASSWORD)) }

        outcome.shouldBeInstanceOf<PasswordResetTokenExpiredException>()
        status(ada) shouldBe "DELETED"
    }

    @Test
    fun `an email verification by link that meets the erasure waits for it, and finds its link gone`() {
        val ada = signUp()
        val secret = linkFrom(registrationEmail(ada))

        val outcome = againstTheErasure(ada) { verifications.verify(VerificationSecret(secret)) }

        outcome.shouldBeInstanceOf<VerificationTokenExpiredException>()
        status(ada) shouldBe "DELETED"
    }

    @Test
    fun `an email verification by code that meets the erasure waits for it, and finds no code`() {
        val ada = signUp()
        val code = codeFrom(registrationEmail(ada))

        val outcome = againstTheErasure(ada) { verifications.verify(Email(emailOf(ada)), VerificationCode(code)) }

        outcome.shouldBeInstanceOf<VerificationCodeRejectedException>()
        status(ada) shouldBe "DELETED"
    }

    @Test
    fun `a forgotten password asked for again as the erasure runs waits for it, and then issues nothing`() {
        val ada = signUp()
        forgotPassword(ada) // a code for the second request to retire
        val address = Email(emailOf(ada))
        requested.clear()

        val outcome = againstTheErasure(ada) { forgotten.request(address) }

        // Answered as every forgot-password is (no exception: the edge's 202), and nothing issued:
        // the account read before the lock is read again under it, and is erased (ADR-0058 decision 5).
        outcome shouldBe null
        status(ada) shouldBe "DELETED"
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens WHERE user_id = ?", Int::class.java, ada) shouldBe 0
        // Published in the request's own thread, before its commit: none means no email was asked for.
        requested.shouldBeEmpty()
    }

    // --- the interleaving ---

    /**
     * Runs the deletion job for [userId] and [other] against each other in the order that used to
     * deadlock, and answers what [other] threw (`null` if nothing). The job must have carried the
     * account out: a deadlock fails one of the two, and the job reports its own failure as `0`.
     */
    private fun againstTheErasure(
        userId: UUID,
        other: () -> Unit,
    ): Throwable? {
        dueForDeletion(userId)
        val (job, side) =
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                // The job will stop at its DELETE FROM credentials, holding the sessions lock and the row.
                holder.prepareStatement("SELECT 1 FROM credentials WHERE user_id = ? FOR UPDATE").use {
                    it.setObject(1, userId)
                    it.executeQuery().use { rows -> rows.next() shouldBe true }
                }
                val job = pool.submit<Int> { deletions.executeDue(LIMIT) }
                val jobPid = awaitBlockedBy(pidOf(holder))
                val side: Future<Throwable?> =
                    pool.submit<Throwable?> {
                        try {
                            other()
                            null
                        } catch (thrown: RuntimeException) {
                            thrown
                        }
                    }
                // The other side has reached a lock the job holds: before the fix, after taking a token row.
                awaitBlockedBy(jobPid)
                holder.commit()
                job to side
            }
        val thrown = side.get(WAIT.seconds, TimeUnit.SECONDS)
        job.get(WAIT.seconds, TimeUnit.SECONDS) shouldBe 1
        return thrown
    }

    /** The one backend Postgres shows blocked by [pid]; waited for, with a deadline that fails. */
    private fun awaitBlockedBy(pid: Int): Int {
        var blocked: Int? = null
        await().atMost(WAIT).until {
            blocked =
                jdbc
                    .queryForList("SELECT pid FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))", Int::class.java, pid)
                    .singleOrNull()
            blocked != null
        }
        return blocked!!
    }

    private fun pidOf(connection: Connection): Int =
        connection.createStatement().use { it.executeQuery("SELECT pg_backend_pid()").use { rows -> rows.next().let { rows.getInt(1) } } }

    /** A deletion asked for over the service and moved back past its fourteen days, so the tokens stay live. */
    private fun dueForDeletion(userId: UUID) {
        deletion.request(UserId(userId), PASSWORD)
        jdbc.update(
            "UPDATE deletion_requests SET requested_at = requested_at - interval '15 days', " +
                "execute_after = execute_after - interval '15 days' WHERE user_id = ?",
            userId,
        )
    }

    // --- the account and its emails ---

    private fun signUp(): UUID {
        val email = "u-${UUID.randomUUID()}@example.com"
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!!
    }

    private fun emailOf(userId: UUID): String = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String::class.java, userId)!!

    private fun registrationEmail(userId: UUID): String = awaitEmailTo(emailOf(userId), count = 1)

    private fun forgotPassword(userId: UUID): String {
        val email = emailOf(userId)
        val before = emails.sent.count { it.to == email }
        mockMvc
            .post("/api/v1/auth/forgot-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email"}"""
            }.andReturn()
            .response.status shouldBe 202
        return awaitEmailTo(email, count = before + 1)
    }

    /** The text of the [count]th email to [address], waited for: each is sent after its commit, on another thread. */
    private fun awaitEmailTo(
        address: String,
        count: Int,
    ): String {
        await().atMost(WAIT).until { emails.sent.count { it.to == address } >= count }
        return emails.sent.filter { it.to == address }[count - 1].text
    }

    private fun linkFrom(text: String): String = Regex("""token=([A-Za-z0-9_-]+)""").find(text)!!.groupValues[1]

    private fun codeFrom(text: String): String = Regex("""(?m)^(\d{6})$""").find(text)!!.groupValues[1]

    private fun status(userId: UUID): String? = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, userId)

    @TestConfiguration
    class RequestsConfiguration {
        @Bean
        fun recordedVerificationRequests(): RecordedVerificationRequests = RecordedVerificationRequests()
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a different good password"
        const val LIMIT = 100
        val WAIT: Duration = Duration.ofSeconds(10)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}

/** Every [VerificationRequested] published, as it is published: the event the email is sent from, after the commit. */
internal class RecordedVerificationRequests : CopyOnWriteArrayList<VerificationRequested>() {
    @EventListener
    fun on(event: VerificationRequested) {
        add(event)
    }
}
