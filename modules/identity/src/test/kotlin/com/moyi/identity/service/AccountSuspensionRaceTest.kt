package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountDeletions
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.infra.sessionsLockWaitersOf
import com.moyi.identity.web.LoginEndpointTest
import com.moyi.identity.web.RecordingEmailSender
import com.moyi.identity.web.SessionsEndpointTest
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Spec §8's races on the account: two admins at once, suspend against lift, and a suspension
 * against the deletion job, each order (ruling 15). Synchronised on what Postgres says is
 * waiting (`pg_locks`, `pg_stat_activity`) and on a latch the holding transaction waits on,
 * each with a deadline that fails the test; never a sleep.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
@Suppress("LongParameterList") // The port, the job, the request, HTTP, the clock and the transactions: each named.
internal class AccountSuspensionRaceTest(
    @Autowired private val suspension: AccountSuspension,
    @Autowired private val deletion: AccountDeletion,
    @Autowired private val deletions: AccountDeletions,
    @Autowired private val resets: ResetPassword,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var pool: ExecutorService
    private val release = CountDownLatch(1)

    @BeforeEach
    fun start() {
        clock.set(Instant.parse("2026-11-13T10:00:00.123456Z"))
        pool = Executors.newFixedThreadPool(3)
    }

    @AfterEach
    fun clear() {
        release.countDown()
        pool.shutdownNow()
        pool.awaitTermination(WAIT.seconds, TimeUnit.SECONDS)
        jdbc.execute(
            "TRUNCATE TABLE account_suspensions, deletion_requests, refresh_tokens, devices, verification_tokens, " +
                "consent_records, credentials, users, outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    @Test
    fun `two suspensions at once are one suspension and one ALREADY_SUSPENDED`() {
        val ada = signUp()

        val first = holding { suspension.suspend(ada) }
        val second = pool.submit<SuspensionResult> { transactions.execute { suspension.suspend(ada) } }
        awaitAdvisoryWaiter(ada)
        release.countDown()

        first.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED
        second.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.ALREADY_SUSPENDED
        openSpans(ada) shouldBe 1
    }

    @Test
    fun `a lift that waits on a suspension sees it, and lifts it`() {
        val ada = signUp()

        val first = holding { suspension.suspend(ada) }
        // Read before the suspension's commit, an active account would answer NOT_SUSPENDED.
        val second = pool.submit<SuspensionResult> { transactions.execute { suspension.lift(ada) } }
        awaitAdvisoryWaiter(ada)
        release.countDown()

        first.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED
        second.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.LIFTED
        status(ada) shouldBe "ACTIVE"
        openSpans(ada) shouldBe 0
    }

    @Test
    fun `a suspension that waits on a lift sees it, and suspends again`() {
        val ada = signUp()
        transactions.execute { suspension.suspend(ada) }.outcome shouldBe SuspensionOutcome.SUSPENDED
        clock.advance(Duration.ofSeconds(1))

        val first = holding { suspension.lift(ada) }
        // Read before the lift's commit, a suspended account would answer ALREADY_SUSPENDED.
        val second = pool.submit<SuspensionResult> { transactions.execute { suspension.suspend(ada) } }
        awaitAdvisoryWaiter(ada)
        release.countDown()

        first.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.LIFTED
        second.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED
        status(ada) shouldBe "SUSPENDED"
        openSpans(ada) shouldBe 1
        closedSpans(ada) shouldBe 1
    }

    @Test
    fun `a suspension that waits on the deletion job carrying the account out answers GONE`() {
        val ada = signUp()
        deletion.request(UserId(ada), PASSWORD)
        clock.advance(Duration.ofDays(14))
        // Gate the job after it has both identity locks and has erased: it waits to mark the
        // request carried out, on this row lock, holding everything it took before.
        val gateHeld = CountDownLatch(1)
        val gate =
            pool.submit {
                transactions.executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM deletion_requests WHERE user_id = ? FOR UPDATE", UUID::class.java, ada)
                    gateHeld.countDown()
                    release.await()
                }
            }
        gateHeld.await(WAIT.seconds, TimeUnit.SECONDS) shouldBe true
        val run = pool.submit<Int> { deletions.executeDue(LIMIT) }
        await().atMost(WAIT).until { blockedOn("UPDATE deletion_requests") }

        val suspended = pool.submit<SuspensionResult> { transactions.execute { suspension.suspend(ada) } }
        awaitAdvisoryWaiter(ada)
        status(ada) shouldBe "ACTIVE"
        release.countDown()

        gate.get(WAIT.seconds, TimeUnit.SECONDS)
        run.get(WAIT.seconds, TimeUnit.SECONDS) shouldBe 1
        suspended.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.GONE
        status(ada) shouldBe "DELETED"
        openSpans(ada) shouldBe 0
    }

    @Test
    fun `the deletion job that waits on a suspension still carries the account out`() {
        val ada = signUp()
        deletion.request(UserId(ada), PASSWORD)
        clock.advance(Duration.ofDays(14))

        val suspended = holding { suspension.suspend(ada) }
        val run = pool.submit<Int> { deletions.executeDue(LIMIT) }
        awaitAdvisoryWaiter(ada)
        release.countDown()

        suspended.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED
        run.get(WAIT.seconds, TimeUnit.SECONDS) shouldBe 1
        status(ada) shouldBe "DELETED"
        // Plan ruling 2: the span outlives the erasure.
        openSpans(ada) shouldBe 1
    }

    @Test
    fun `a suspension reads the account only once it holds its row, so a writer holding the row alone is seen`() {
        val ada = signUp()
        // A writer that holds the user's row and not the sessions lock, as `AccountDeletion`'s
        // request and cancel do. Here it changes the status, so a suspension that read the row
        // before the lock (and then waited only on its own UPDATE) would write over it.
        val written = CountDownLatch(1)
        val writerPid = AtomicInteger()
        val writer =
            pool.submit {
                transactions.executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID::class.java, ada)
                    jdbc.update("UPDATE users SET status = 'PENDING_DELETION' WHERE id = ?", ada)
                    writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                    written.countDown()
                    release.await()
                }
            }
        written.await(WAIT.seconds, TimeUnit.SECONDS) shouldBe true

        val suspended = pool.submit<SuspensionResult> { transactions.execute { suspension.suspend(ada) } }
        // Waiting on the writer's transaction, and on nothing else: blocked by the writer's backend.
        await().atMost(WAIT).until {
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted AND ? = ANY (pg_blocking_pids(pid))",
                Int::class.java,
                writerPid.get(),
            )!! > 0
        }
        release.countDown()

        writer.get(WAIT.seconds, TimeUnit.SECONDS)
        suspended.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.GONE
        status(ada) shouldBe "PENDING_DELETION"
        openSpans(ada) shouldBe 0
    }

    @Test
    fun `a password reset in flight when a suspension commits leaves the suspension whole`() {
        val ada = signUp()
        val secret = resetSecretFor(ada)

        val suspended = holding { suspension.suspend(ada) }
        // The reset spends its link and reaches the sessions lock while the suspension holds it.
        // Read before the suspension's commit and saved after it, the account would be written
        // back ACTIVE, and the reset's earlier instant over the suspension's bump.
        val reset = pool.submit { resets.reset(VerificationSecret(secret), Password.of(NEW_PASSWORD)) }
        awaitAdvisoryWaiter(ada)
        release.countDown()

        suspended.get(WAIT.seconds, TimeUnit.SECONDS).outcome shouldBe SuspensionOutcome.SUSPENDED
        reset.get(WAIT.seconds, TimeUnit.SECONDS)
        status(ada) shouldBe "SUSPENDED"
        openSpans(ada) shouldBe 1
        val suspendedAt =
            jdbc.queryForObject("SELECT suspended_at FROM account_suspensions WHERE user_id = ?", Timestamp::class.java, ada)!!
        tokensInvalidBefore(ada) shouldBe suspendedAt.toInstant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
    }

    // --- helpers ---

    /** Runs [act] in a transaction that stays open, holding what [act] locked, until [release]. */
    private fun holding(act: () -> SuspensionResult): Future<SuspensionResult> {
        val acted = CountDownLatch(1)
        val future =
            pool.submit<SuspensionResult> {
                transactions.execute {
                    act().also {
                        acted.countDown()
                        release.await()
                    }
                }
            }
        acted.await(WAIT.seconds, TimeUnit.SECONDS) shouldBe true
        return future
    }

    /** Somebody is waiting for this user's sessions advisory lock: the second party has reached the first lock. */
    private fun awaitAdvisoryWaiter(userId: UUID) {
        await().atMost(WAIT).until { jdbc.sessionsLockWaitersOf(userId) > 0 }
    }

    private fun blockedOn(statement: String): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE ?",
            Int::class.java,
            "$statement%",
        )!! > 0

    private fun tokensInvalidBefore(userId: UUID): Instant? =
        jdbc.queryForObject("SELECT tokens_invalid_before FROM users WHERE id = ?", Timestamp::class.java, userId)?.toInstant()

    /** A live reset link for the account, asked for over HTTP and read from its email. */
    private fun resetSecretFor(userId: UUID): String {
        val email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String::class.java, userId)!!
        emails.sent.clear()
        mockMvc
            .post("/api/v1/auth/forgot-password") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email"}"""
            }.andReturn()
            .response.status shouldBe 202
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

    private fun status(userId: UUID): String? = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, userId)

    private fun closedSpans(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_suspensions WHERE user_id = ? AND lifted_at IS NOT NULL",
            Int::class.java,
            userId,
        )!!

    private fun openSpans(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_suspensions WHERE user_id = ? AND lifted_at IS NULL",
            Int::class.java,
            userId,
        )!!

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
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!!
        var secret: String? = null
        await().atMost(WAIT).until {
            secret =
                emails.sent
                    .toList()
                    .filter { it.to == email }
                    .firstNotNullOfOrNull { Regex("""token=([A-Za-z0-9_-]+)""").find(it.text)?.groupValues?.get(1) }
            secret != null
        }
        mockMvc
            .post("/api/v1/auth/verify-email") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token": "$secret"}"""
            }.andReturn()
            .response.status shouldBe 200
        return id
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
