package com.moyi.admin.service

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.admin.service.AdminConsoleServiceTest.Companion.code
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * The step-up (A2 spec §10.2): the five-minute rule, and [AdminSignIn.stepUp] sharing the
 * sign-in code step's one path, so it has the same lockout, the same replay check and its own
 * `ADMIN_STEP_UP` rows. The same context as [AdminConsoleServiceTest] (same properties, same
 * clock), so Spring builds it once.
 *
 * Here, not in `app`: [AdminSignIn] and [StepUp] are `internal` to this module, and A2 has no
 * step-up route until the moderation pages (plan Task 6). `app`'s `AdminStepUpTest` holds the
 * HTTP half that exists now: the sign-in's code step opening the window.
 */
@SpringBootTest(
    classes = [AdminTestApplication::class],
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=${AdminConsoleServiceTest.KEY}",
        "spring.datasource.hikari.maximum-pool-size=${AdminConsoleServiceTest.POOL_SIZE}",
    ],
)
@Import(AdminConsoleServiceTest.TimeConfiguration::class)
internal class AdminStepUpTest(
    @Autowired private val enrolment: AdminEnrolment,
    @Autowired private val signIn: AdminSignIn,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `the window is fresh at 4 min 59 s after the second factor and stale at 5 min 1 s`() {
        val start = clock.instant()
        try {
            val provenAt = clock.instant()
            clock.advance(Duration.ofMinutes(4).plusSeconds(59))
            StepUp.isFresh(provenAt, clock.instant()) shouldBe true
            clock.advance(Duration.ofSeconds(1))
            StepUp.isFresh(provenAt, clock.instant()) shouldBe true
            clock.advance(Duration.ofSeconds(1))
            StepUp.isFresh(provenAt, clock.instant()) shouldBe false
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `no second factor, or one dated after now, is never fresh`() {
        StepUp.isFresh(null, clock.instant()) shouldBe false
        StepUp.isFresh(clock.instant().plusSeconds(1), clock.instant()) shouldBe false
    }

    @Test
    fun `a correct code opens a fresh window and is spent, so the same code again is a replay`() {
        val email = active()
        val id = idOf(email)
        val secret = secretOf(email)
        val start = clock.instant()
        try {
            val signInCode = code(secret, clock.instant().plusSeconds(30))
            signIn.totp(id, signInCode, null) shouldBe TotpStep.Accepted(AdminRole.SUPPORT)
            val signedInAt = clock.instant()
            // The code spent signing in cannot be spent again on a step-up.
            signIn.stepUp(id, signInCode, null) shouldBe TotpStep.Refused

            clock.advance(Duration.ofMinutes(6))
            StepUp.isFresh(signedInAt, clock.instant()) shouldBe false
            val stepUpCode = code(secret, clock.instant())

            signIn.stepUp(id, stepUpCode, null) shouldBe TotpStep.Accepted(AdminRole.SUPPORT)
            val steppedUpAt = clock.instant()
            StepUp.isFresh(steppedUpAt, clock.instant().plus(Duration.ofMinutes(4).plusSeconds(59))) shouldBe true

            signIn.stepUp(id, stepUpCode, null) shouldBe TotpStep.Refused
            stepUpRows(id).map { it["outcome"] to it["reason"] } shouldBe
                listOf("REFUSED" to "replayed_code", "SUCCEEDED" to null, "REFUSED" to "replayed_code")
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `five wrong step-up codes lock the account exactly as five wrong sign-in codes do`() {
        val bySignIn = active()
        val byStepUp = active()
        val signInId = idOf(bySignIn)
        val stepUpId = idOf(byStepUp)
        val signInWrong = wrongCode(secretOf(bySignIn))
        val stepUpWrong = wrongCode(secretOf(byStepUp))

        repeat(MAX_FAILURES) {
            signIn.totp(signInId, signInWrong, null) shouldBe TotpStep.Refused
            signIn.stepUp(stepUpId, stepUpWrong, null) shouldBe TotpStep.Refused
            lockState(byStepUp) shouldBe lockState(bySignIn)
        }
        lockedUntil(byStepUp).shouldNotBeNull()

        // Locked: a correct code is refused by both steps, uncounted, until the lock lapses.
        val right = code(secretOf(byStepUp), clock.instant().plusSeconds(30))
        signIn.stepUp(stepUpId, right, null) shouldBe TotpStep.Refused
        signIn.totp(stepUpId, right, null) shouldBe TotpStep.Refused
        signIn.password(byStepUp, AdminConsoleServiceTest.PASSWORD, null) shouldBe PasswordStep.Refused
        stepUpRows(stepUpId).map { it["reason"] } shouldBe List(MAX_FAILURES) { "wrong_code" } + "locked"

        val start = clock.instant()
        try {
            clock.advance(Duration.ofMinutes(15).plusSeconds(1))
            signIn.stepUp(stepUpId, code(secretOf(byStepUp), clock.instant()), null) shouldBe TotpStep.Accepted(AdminRole.SUPPORT)
            lockState(byStepUp) shouldBe (0 to null)
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `step-up and sign-in failures count toward one lock`() {
        val email = active()
        val id = idOf(email)
        val wrong = wrongCode(secretOf(email))

        repeat(2) { signIn.password(email, "wrong", null) }
        repeat(2) { signIn.totp(id, wrong, null) }
        lockedUntil(email).shouldBeNull()
        signIn.stepUp(id, wrong, null) shouldBe TotpStep.Refused

        lockedUntil(email).shouldNotBeNull()
    }

    @Test
    fun `each step-up answer is an ADMIN_STEP_UP row with the reason and never the code, and no sign-in row`() {
        val email = active()
        val id = idOf(email)
        val secret = secretOf(email)
        val wrong = wrongCode(secret)
        val right = code(secret, clock.instant().plusSeconds(30))
        val signInRowsBefore = signInRows(id)
        val signInsCounted = meters.counter("moyi.admin.sign_in", "outcome", "succeeded").count()
        val unknownBefore = unknownStepUps()

        signIn.stepUp(id, wrong, null) shouldBe TotpStep.Refused
        signIn.stepUp(id, right, null) shouldBe TotpStep.Accepted(AdminRole.SUPPORT)
        signIn.stepUp(UUID.randomUUID(), right, null) shouldBe TotpStep.Refused

        val rows = stepUpRows(id)
        rows shouldHaveSize 2
        rows.map { it["outcome"] to it["reason"] } shouldBe listOf("REFUSED" to "wrong_code", "SUCCEEDED" to null)
        rows.forEach {
            it["actor_type"] shouldBe "ADMIN"
            it["target_type"].shouldBeNull()
            (it["metadata"] as String) shouldNotContain wrong
            (it["metadata"] as String) shouldNotContain right
        }
        unknownStepUps() shouldBe unknownBefore + 1
        signInRows(id) shouldBe signInRowsBefore
        meters.counter("moyi.admin.sign_in", "outcome", "succeeded").count() shouldBe signInsCounted
        // A correct step-up ends the run of failures, as a completed sign-in does.
        lockState(email) shouldBe (0 to null)
    }

    /** Refused step-ups for an id that names no account: no actor, reason `not_active`. */
    private fun unknownStepUps(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'ADMIN_STEP_UP' AND actor_id IS NULL AND metadata->>'reason' = 'not_active'",
            Int::class.java,
        )!!

    private fun stepUpRows(id: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT outcome, metadata->>'reason' AS reason, actor_type, target_type, metadata::text AS metadata " +
                "FROM audit_log WHERE action = 'ADMIN_STEP_UP' AND actor_id = ? ORDER BY id",
            id,
        )

    private fun signInRows(id: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ADMIN_SIGN_IN' AND actor_id = ?", Int::class.java, id)!!

    /** Invited and enrolled at the clock's now: the code step of now is spent. */
    private fun active(): String {
        val email = "stepup-${UUID.randomUUID().toString().take(8)}@example.com"
        val token = jdbc.queryForObject("SELECT admin_invite(?::citext, 'SUPPORT')", String::class.java, email)!!
        enrolment.begin(token)
        enrolment.complete(
            token,
            AdminConsoleServiceTest.PASSWORD,
            AdminConsoleServiceTest.PASSWORD,
            code(secretOf(email), clock.instant()),
            null,
        ) shouldBe EnrolmentResult.Done
        return email
    }

    private fun secretOf(email: String): ByteArray =
        cipher.decrypt(
            jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!,
        )

    private fun idOf(email: String) =
        jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!!

    private fun lockedUntil(email: String) =
        jdbc.queryForObject("SELECT locked_until FROM admin_accounts WHERE email = ?::citext", OffsetDateTime::class.java, email)

    /** The failure count and the lock's end, the two things the lockout writes. */
    private fun lockState(email: String): Pair<Int, Instant?> =
        jdbc.queryForObject(
            "SELECT failed_attempts, locked_until FROM admin_accounts WHERE email = ?::citext",
            { row, _ -> row.getInt(1) to row.getObject(2, OffsetDateTime::class.java)?.toInstant() },
            email,
        )!!

    /** A six-digit code that no step of the window around now produces. */
    private fun wrongCode(secret: ByteArray): String {
        val valid = (-1L..1L).map { code(secret, clock.instant().plusSeconds(it * 30)) }.toSet()
        return generateSequence(0) { it + 1 }.map { "%06d".format(it) }.first { it !in valid }
    }

    private companion object {
        const val MAX_FAILURES = 5
        val cipher = TotpSecretCipher(Base64.getDecoder().decode(AdminConsoleServiceTest.KEY))
    }
}
