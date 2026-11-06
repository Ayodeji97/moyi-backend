package com.moyi.admin.service

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import com.moyi.admin.domain.AdminRole
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * The enrolment and sign-in services against the real schema, with the password port faked
 * (`AdminTestApplication`): every answer each can give, the lockout counting both factors
 * together, replay, and the audit row and counter each answer leaves. The HTTP half, with the
 * real Argon2 port, is `app`'s `AdminEnrolmentTest` and `AdminSignInTest`.
 */
@SpringBootTest(
    classes = [AdminTestApplication::class],
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=${AdminConsoleServiceTest.KEY}",
        // The lockout races run RACERS sign-in steps at once, each holding a connection while
        // it waits for the row, plus the holder's connection and the polling one. Sized here,
        // not left to the module's four, so the race cannot starve the pool it is measuring.
        "spring.datasource.hikari.maximum-pool-size=${AdminConsoleServiceTest.POOL_SIZE}",
    ],
)
@Import(AdminConsoleServiceTest.TimeConfiguration::class)
internal class AdminConsoleServiceTest(
    @Autowired private val enrolment: AdminEnrolment,
    @Autowired private val signIn: AdminSignIn,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `begin gives a page with a fresh secret each time, and prints none of it`() {
        val (email, token) = invited()
        val first = enrolment.begin(token).shouldNotBeNull()
        val second = enrolment.begin(token).shouldNotBeNull()
        (first.secretBase32 == second.secretBase32) shouldBe false
        first.email shouldBe email
        enrolment.current(token)!!.secretBase32 shouldBe second.secretBase32
        first.toString() shouldNotContain first.secretBase32
        first.toString() shouldNotContain email
    }

    @Test
    fun `complete refuses each way in turn and then enrols once`() {
        val (email, token) = invited()
        enrolment.begin(token)
        val code = code(secretOf(email), clock.instant())

        enrolment.complete(token, PASSWORD, "$PASSWORD!", code, null) shouldBe EnrolmentResult.PasswordMismatch
        enrolment.complete(token, "too short", "too short", code, null) shouldBe EnrolmentResult.PasswordTooShortOrLong
        val breached = AdminTestApplication.BREACHED
        enrolment.complete(token, breached, breached, code, null) shouldBe EnrolmentResult.PasswordBreached
        enrolment.complete(token, PASSWORD, PASSWORD, wrongCode(secretOf(email)), null) shouldBe EnrolmentResult.WrongCode
        statusOf(email) shouldBe "INVITED"

        enrolment.complete(token, PASSWORD, PASSWORD, code, null) shouldBe EnrolmentResult.Done
        statusOf(email) shouldBe "ACTIVE"
        enrolment.complete(token, PASSWORD, PASSWORD, code, null) shouldBe EnrolmentResult.InvalidLink
        enrolment.begin(token).shouldBeNull()
        enrolment.current(token).shouldBeNull()
    }

    @Test
    fun `a link that is not base64, or not 32 bytes, or unknown, is invalid`() {
        enrolment.begin("%%%").shouldBeNull()
        enrolment.begin(Base64.getUrlEncoder().encodeToString(ByteArray(16))).shouldBeNull()
        enrolment.begin(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))).shouldBeNull()
        enrolment.complete("%%%", PASSWORD, PASSWORD, "123456", null) shouldBe EnrolmentResult.InvalidLink
    }

    @Test
    fun `an invited account that never saw its page cannot finish with any code`() {
        val (_, token) = invited()
        enrolment.complete(token, PASSWORD, PASSWORD, "123456", null) shouldBe EnrolmentResult.WrongCode
        enrolment.current(token).shouldBeNull()
    }

    @Test
    fun `the password step answers each kind of account, counting only wrong passwords`() {
        val email = active()
        signIn.password("nobody-${UUID.randomUUID()}@example.com", PASSWORD, null) shouldBe PasswordStep.Refused
        signIn.password(email, "wrong", null) shouldBe PasswordStep.Refused
        failedAttempts(email) shouldBe 1
        signIn.password(email, PASSWORD, null).shouldBeInstanceOf<PasswordStep.Accepted>().adminId shouldBe idOf(email)

        val (invitedEmail, _) = invited()
        signIn.password(invitedEmail, PASSWORD, null) shouldBe PasswordStep.Refused
    }

    @Test
    fun `codes count toward the same lock as passwords, and a lock refuses both steps until it lapses`() {
        val email = active()
        val id = idOf(email)
        val secret = secretOf(email)
        val refusedBefore = counted("refused")
        repeat(2) { signIn.password(email, "wrong", null) }
        repeat(3) { signIn.totp(id, wrongCode(secret), null) }
        counted("refused") - refusedBefore shouldBe 5.0

        lockedUntil(email).shouldNotBeNull()
        failedAttempts(email) shouldBe 0
        signIn.password(email, PASSWORD, null) shouldBe PasswordStep.Refused
        signIn.totp(id, code(secret, clock.instant().plusSeconds(30)), null) shouldBe TotpStep.Refused

        val start = clock.instant()
        try {
            clock.advance(Duration.ofMinutes(15).plusSeconds(1))
            signIn.password(email, PASSWORD, null).shouldBeInstanceOf<PasswordStep.Accepted>()
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `a code is accepted once, with the account's role, and clears the failure count`() {
        val email = active()
        val id = idOf(email)
        val code = code(secretOf(email), clock.instant().plusSeconds(30))
        signIn.password(email, "wrong", null)
        val succeededBefore = counted("succeeded")

        signIn.totp(id, code, null) shouldBe TotpStep.Accepted(AdminRole.SUPPORT)
        failedAttempts(email) shouldBe 0
        counted("succeeded") - succeededBefore shouldBe 1.0
        signIn.totp(id, code, null) shouldBe TotpStep.Refused
        signIn.totp(UUID.randomUUID(), code, null) shouldBe TotpStep.Refused

        signIn.signOut(id, null)
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ADMIN_SIGN_OUT' AND actor_id = ?", Int::class.java, id) shouldBe
            1
    }

    @Test
    fun `wrong codes sent at once are counted five and then refused as locked, never more`() {
        val email = active()
        val id = idOf(email)
        val wrong = wrongCode(secretOf(email))

        racing(id) { signIn.totp(id, wrong, null) }.forEach { it shouldBe TotpStep.Refused }

        refusalReasons(id, "totp") shouldBe mapOf("wrong_code" to MAX_FAILURES, "locked" to RACERS - MAX_FAILURES)
        lockedUntil(email).shouldNotBeNull()
        failedAttempts(email) shouldBe 0
    }

    @Test
    fun `wrong passwords sent at once are counted five and then refused as locked, never more`() {
        val email = active()
        val id = idOf(email)

        racing(id) { signIn.password(email, "wrong", null) }.forEach { it shouldBe PasswordStep.Refused }

        refusalReasons(id, "password") shouldBe mapOf("wrong_password" to MAX_FAILURES, "locked" to RACERS - MAX_FAILURES)
        lockedUntil(email).shouldNotBeNull()
        failedAttempts(email) shouldBe 0
    }

    /**
     * Runs [RACERS] copies of [step] while another connection holds the account row, waits
     * until every one of them is blocked behind it (on the row lock at the top of the step, or,
     * were that lock gone, on the failure count's UPDATE after deciding from a stale read),
     * then lets them go. No sleep: the release waits on Postgres reporting them blocked.
     */
    private fun <T> racing(
        id: UUID,
        step: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(RACERS)
        try {
            return whileRowIsHeld(id) { holderPid, release ->
                val futures = List(RACERS) { pool.submit<T> { step() } }
                await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { blockedBy(holderPid) == RACERS }
                release()
                futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Runs [block] while another transaction holds the account row; `release` lets it go. */
    private fun <T> whileRowIsHeld(
        id: UUID,
        block: (holderPid: Int, release: () -> Unit) -> T,
    ): T =
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            try {
                lockAccountRow(holder, id)
                block(backendPidOf(holder)) { holder.rollback() }
            } finally {
                holder.rollback()
                holder.autoCommit = true
            }
        }

    private fun lockAccountRow(
        connection: Connection,
        id: UUID,
    ) = connection.prepareStatement("SELECT 1 FROM admin_accounts WHERE id = ? FOR UPDATE").use {
        it.setObject(1, id)
        it.executeQuery().use { rows -> rows.next() shouldBe true }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /**
     * Backends waiting behind the holder, directly or not: once the holder lets go, the queue
     * behind a row lock forms on the first waiter's tuple lock, not the holder.
     */
    private fun blockedBy(holderPid: Int): Int =
        jdbc.queryForObject(
            "WITH RECURSIVE blocked(pid) AS (" +
                "SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)) " +
                "UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid = ANY(pg_blocking_pids(a.pid))" +
                ") SELECT count(*) FROM blocked",
            Int::class.java,
            holderPid,
        )!!

    private fun refusalReasons(
        id: UUID,
        step: String,
    ): Map<String, Int> =
        jdbc
            .queryForList(
                "SELECT metadata->>'reason' AS reason, count(*) AS n FROM audit_log " +
                    "WHERE action = 'ADMIN_SIGN_IN' AND outcome = 'REFUSED' AND actor_id = ? AND metadata->>'step' = ? GROUP BY 1",
                id,
                step,
            ).associate { it["reason"] as String to (it["n"] as Number).toInt() }

    private fun invited(): Pair<String, String> {
        val email = "svc-${UUID.randomUUID().toString().take(8)}@example.com"
        return email to jdbc.queryForObject("SELECT admin_invite(?::citext, 'SUPPORT')", String::class.java, email)!!
    }

    /** Invited and enrolled at the clock's now: the code step of now is spent. */
    private fun active(): String {
        val (email, token) = invited()
        enrolment.begin(token)
        enrolment.complete(token, PASSWORD, PASSWORD, code(secretOf(email), clock.instant()), null) shouldBe EnrolmentResult.Done
        return email
    }

    private fun secretOf(email: String): ByteArray =
        cipher.decrypt(
            jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!,
        )

    private fun idOf(email: String) =
        jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!!

    private fun statusOf(email: String) =
        jdbc.queryForObject("SELECT status FROM admin_accounts WHERE email = ?::citext", String::class.java, email)

    private fun failedAttempts(email: String) =
        jdbc.queryForObject("SELECT failed_attempts FROM admin_accounts WHERE email = ?::citext", Int::class.java, email)

    private fun lockedUntil(email: String) =
        jdbc.queryForObject("SELECT locked_until FROM admin_accounts WHERE email = ?::citext", java.time.OffsetDateTime::class.java, email)

    /** A six-digit code that no step of the window around now produces. */
    private fun wrongCode(secret: ByteArray): String {
        val valid = (-1L..1L).map { code(secret, clock.instant().plusSeconds(it * 30)) }.toSet()
        return generateSequence(0) { it + 1 }.map { "%06d".format(it) }.first { it !in valid }
    }

    private fun counted(outcome: String) = meters.counter("moyi.admin.sign_in", "outcome", outcome).count()

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }

    companion object {
        const val KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        const val MAX_FAILURES = 5

        /** Two more than the lock allows, so a gate that lets the stale ones through is seen. */
        const val RACERS = MAX_FAILURES + 2

        /** The racers, the holder, the poller, and one to spare. */
        const val POOL_SIZE = RACERS + 3
        const val TIMEOUT_SECONDS = 20L
        const val PASSWORD = "a long enough admin passphrase"
        private val cipher = TotpSecretCipher(Base64.getDecoder().decode(KEY))
        private val generator = TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6)

        fun code(
            secret: ByteArray,
            at: Instant,
        ): String = generator.generateOneTimePasswordString(SecretKeySpec(secret, "HmacSHA1"), at)
    }
}
