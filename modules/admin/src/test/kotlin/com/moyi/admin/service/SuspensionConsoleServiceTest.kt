package com.moyi.admin.service

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.ScriptedAccountSuspension
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.admin.service.AdminConsoleServiceTest.Companion.code
import com.moyi.admin.web.SecondFactor
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountStatus
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * `SuspensionConsole` (A3 spec §5, §6, §7; plan Task 6) against the real schema and a scripted
 * `AccountSuspension` ([ScriptedAccountSuspension]: identity is not built in this context; the
 * real port's own tests are identity's). The audit rows, the metric, the log line, the order of
 * the checks, the role, and the atomicity of the act and its row.
 *
 * `ContentAccessServiceTest.Rig` is imported only for [ContentAccessServiceTest.FailAfterRecord]:
 * `AuditTrail` can be made to throw just after its row is written (spec §11).
 */
@SpringBootTest(
    classes = [AdminTestApplication::class],
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=${AdminConsoleServiceTest.KEY}",
    ],
)
@Import(AdminConsoleServiceTest.TimeConfiguration::class, ContentAccessServiceTest.Rig::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // The service, the step-up and what sets a case up, and the clock, meters and database it is read in.
internal class SuspensionConsoleServiceTest(
    @Autowired private val suspensions: SuspensionConsole,
    @Autowired private val secondFactor: SecondFactor,
    @Autowired private val enrolment: AdminEnrolment,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val admin = UUID.randomUUID()
    private val member = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        ScriptedAccountSuspension.reset()
        ContentAccessServiceTest.FailAfterRecord.armed = false
        ContentAccessServiceTest.FailAfterRecord.fired = false
    }

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
        ContentAccessServiceTest.FailAfterRecord.armed = false
    }

    // --- the row of each outcome -------------------------------------------------------------------------------------

    @Test
    fun `every outcome of a suspend writes one ACCOUNT_SUSPENDED row with exactly its fields`(output: CapturedOutput) {
        SuspensionOutcome.entries.forEach { outcome ->
            val user = UUID.randomUUID()
            val answer = answerOf(outcome)
            ScriptedAccountSuspension.answer = answer
            val before = counted("suspend", outcome)

            val result = acting(AdminRole.ADMIN) { suspensions.suspend(user, "  $WHY\n", proof(), admin, null) }

            withClue(outcome) {
                result shouldBe answer
                val row = auditedFor(user, outcome).single()
                row.action shouldBe AuditAction.ACCOUNT_SUSPENDED.name
                row.assertFields(outcome, answer, user)
                counted("suspend", outcome) shouldBe before + 1
                output.out shouldContain "suspension admin=$admin user=$user result=$outcome"
            }
        }
        ScriptedAccountSuspension.calls.size shouldBe SuspensionOutcome.entries.size
        ScriptedAccountSuspension.calls.forEach { it shouldContain "suspend:" }
    }

    @Test
    fun `every outcome of a lift writes one ACCOUNT_UNSUSPENDED row with exactly its fields`(output: CapturedOutput) {
        SuspensionOutcome.entries.forEach { outcome ->
            val user = UUID.randomUUID()
            val answer = answerOf(outcome)
            ScriptedAccountSuspension.answer = answer
            val before = counted("lift", outcome)

            val result = acting(AdminRole.ADMIN) { suspensions.lift(user, WHY, proof(), admin, null) }

            withClue(outcome) {
                result shouldBe answer
                val row = auditedFor(user, outcome).single()
                row.action shouldBe AuditAction.ACCOUNT_UNSUSPENDED.name
                row.assertFields(outcome, answer, user)
                counted("lift", outcome) shouldBe before + 1
                output.out shouldContain "suspension admin=$admin user=$user result=$outcome"
            }
        }
        ScriptedAccountSuspension.calls.size shouldBe SuspensionOutcome.entries.size
        ScriptedAccountSuspension.calls.forEach { it shouldContain "lift:" }
    }

    // --- refused before the port -------------------------------------------------------------------------------------

    @Test
    fun `an invalid note throws before the port is called, and writes no row`() {
        listOf("", "   ", "x".repeat(ModerationNote.MAX_CODE_POINTS + 1), "why\u0000not").forEach { bad ->
            acting(AdminRole.ADMIN) {
                shouldThrow<InvalidModerationNote> { suspensions.suspend(member, bad, proof(), admin, null) }
                shouldThrow<InvalidModerationNote> { suspensions.lift(member, bad, proof(), admin, null) }
            }
        }

        ScriptedAccountSuspension.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
    }

    @Test
    fun `a MODERATOR and SUPPORT are refused before the port, with no row`() {
        listOf(AdminRole.MODERATOR, AdminRole.SUPPORT).forEach { role ->
            acting(role) {
                shouldThrow<AccessDeniedException> { suspensions.suspend(member, WHY, proof(), admin, null) }
                shouldThrow<AccessDeniedException> { suspensions.lift(member, WHY, proof(), admin, null) }
            }
        }

        ScriptedAccountSuspension.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
    }

    @Test
    fun `another admin's step-up opens nothing`() {
        acting(AdminRole.ADMIN) {
            val other = FreshStepUp(UUID.randomUUID(), clock.instant())
            shouldThrow<IllegalArgumentException> { suspensions.suspend(member, WHY, other, admin, null) }
            shouldThrow<IllegalArgumentException> { suspensions.lift(member, WHY, other, admin, null) }
        }

        ScriptedAccountSuspension.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
    }

    // --- the note is the row's justification and nothing else --------------------------------------------------------

    @Test
    fun `the note is in the justification column alone, never a log line, a metric tag or the metadata`(output: CapturedOutput) {
        val note = "a private reason ${UUID.randomUUID()}"
        ScriptedAccountSuspension.answer = answerOf(SuspensionOutcome.SUSPENDED)
        acting(AdminRole.ADMIN) { suspensions.suspend(member, note, proof(), admin, null) }
        ScriptedAccountSuspension.answer = answerOf(SuspensionOutcome.LIFTED)
        acting(AdminRole.ADMIN) { suspensions.lift(member, note, proof(), admin, null) }

        audited().map { it.justification } shouldContainExactly listOf(note, note)
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log a WHERE (to_jsonb(a) - 'justification')::text LIKE ?",
            Int::class.java,
            "%$note%",
        ) shouldBe 0
        output.all shouldNotContain note
        meters.meters.flatMap { it.id.tags }.forEach { it.value shouldNotContain note }
        meters.meters.map { it.id.name }.forEach { it shouldNotContain note }
    }

    // --- atomicity ---------------------------------------------------------------------------------------------------

    @Test
    fun `an act that fails after its audit row commits neither, and the step-up it spent stays spent`() {
        val (id, secret) = enrolledAdmin()
        val start = clock.instant()
        try {
            clock.advance(Duration.ofSeconds(STEP_SECONDS))
            val proved = secondFactor.proveNow(id, code(secret, clock.instant()), null).shouldBeInstanceOf<SecondFactor.Fresh.Proved>()
            val spent = lastStep(id)

            ContentAccessServiceTest.FailAfterRecord.armed = true
            acting(AdminRole.ADMIN, id) {
                shouldThrow<IllegalStateException> { suspensions.suspend(member, WHY, proved.stepUp, id, null) }
            }
            ContentAccessServiceTest.FailAfterRecord.armed = false

            ContentAccessServiceTest.FailAfterRecord.fired shouldBe true
            rowsOf(id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED")
            lastStep(id) shouldBe spent
            (spent != null) shouldBe true
        } finally {
            clock.set(start)
        }
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private fun answerOf(outcome: SuspensionOutcome): SuspensionResult =
        when (outcome) {
            SuspensionOutcome.SUSPENDED -> {
                SuspensionResult(outcome, AccountStatus.ACTIVE, null)
            }

            SuspensionOutcome.ALREADY_SUSPENDED -> {
                SuspensionResult(outcome, AccountStatus.SUSPENDED, null)
            }

            SuspensionOutcome.LIFTED -> {
                SuspensionResult(outcome, AccountStatus.SUSPENDED, null)
            }

            SuspensionOutcome.NOT_SUSPENDED -> {
                SuspensionResult(
                    outcome,
                    AccountStatus.PENDING_DELETION,
                    Instant.parse("2026-12-01T00:00:00Z"),
                )
            }

            SuspensionOutcome.GONE -> {
                SuspensionResult(outcome, AccountStatus.DELETED, null)
            }

            SuspensionOutcome.NO_SUCH_ACCOUNT -> {
                SuspensionResult(outcome, null, null)
            }
        }

    private fun <T> acting(
        role: AdminRole,
        who: UUID = admin,
        block: () -> T,
    ): T {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(who, null, listOf(SimpleGrantedAuthority("ROLE_${role.name}")))
        SecurityContextHolder.setContext(context)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun proof(): FreshStepUp = FreshStepUp(admin, clock.instant())

    private fun counted(
        action: String,
        outcome: SuspensionOutcome,
    ): Double = meters.counter(METRIC, "action", action, "result", outcome.name).count()

    /** An enrolled, active `ADMIN`: its id and its TOTP secret. Enrolment spends the code step of now. */
    private fun enrolledAdmin(): Pair<UUID, ByteArray> {
        val email = "suspend-${UUID.randomUUID().toString().take(8)}@example.com"
        val token = jdbc.queryForObject("SELECT admin_invite(?::citext, 'ADMIN')", String::class.java, email)!!
        enrolment.begin(token)
        val secret =
            TotpSecretCipher(Base64.getDecoder().decode(AdminConsoleServiceTest.KEY)).decrypt(
                jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!,
            )
        enrolment.complete(
            token,
            AdminConsoleServiceTest.PASSWORD,
            AdminConsoleServiceTest.PASSWORD,
            code(secret, clock.instant()),
            null,
        ) shouldBe EnrolmentResult.Done
        return jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!! to secret
    }

    private fun lastStep(id: UUID): Long? =
        jdbc.queryForObject("SELECT totp_last_step FROM admin_accounts WHERE id = ?", Long::class.javaObjectType, id)

    /** [id]'s step-up and suspension rows, `action|outcome`, in order. */
    private fun rowsOf(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || outcome FROM audit_log " +
                    "WHERE actor_id = ? AND action IN ('ADMIN_STEP_UP', 'ACCOUNT_SUSPENDED', 'ACCOUNT_UNSUSPENDED') ORDER BY id",
                String::class.java,
                id,
            ).filterNotNull()

    /** This test's admin's audit rows, in the order written. */
    private fun audited(): List<Row> = rows("WHERE actor_id = ?", admin)

    /** The row [outcome] wrote about [user]: NO_SUCH_ACCOUNT's has no target, so it is found by its metadata. */
    private fun auditedFor(
        user: UUID,
        outcome: SuspensionOutcome,
    ): List<Row> =
        audited()
            .filter { it.targetId == user.toString() || (it.targetId == null && it.metadata.contains("\"${outcome.name}\"")) }
            .let { found -> if (outcome == SuspensionOutcome.NO_SUCH_ACCOUNT) found.takeLast(1) else found }

    private fun rows(
        where: String,
        arg: Any,
    ): List<Row> =
        jdbc.query(
            "SELECT action, outcome, target_type, target_id, justification, metadata::text AS metadata FROM audit_log " +
                "$where ORDER BY id",
            { rs, _ ->
                Row(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    targetType = rs.getString("target_type"),
                    targetId = rs.getString("target_id"),
                    justification = rs.getString("justification"),
                    metadata = rs.getString("metadata"),
                )
            },
            arg,
        )

    @Suppress("LongParameterList") // One audit row's columns.
    private class Row(
        val action: String,
        val outcome: String,
        val targetType: String?,
        val targetId: String?,
        val justification: String?,
        val metadata: String,
    ) {
        /** Every field the brief fixes: outcome, target, the trimmed note, and the metadata's three keys (jsonb orders them by length). */
        fun assertFields(
            result: SuspensionOutcome,
            answer: SuspensionResult,
            user: UUID,
        ) {
            val succeeded = result == SuspensionOutcome.SUSPENDED || result == SuspensionOutcome.LIFTED
            outcome shouldBe (if (succeeded) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED).name
            if (result == SuspensionOutcome.NO_SUCH_ACCOUNT) {
                targetType.shouldBeNull()
                targetId.shouldBeNull()
            } else {
                targetType shouldBe "USER"
                targetId shouldBe user.toString()
            }
            justification shouldBe WHY
            val statusBefore = answer.statusBefore?.let { """, "statusBefore": "$it"""" } ?: ""
            metadata shouldBe """{"result": "$result"$statusBefore, "deletionPending": ${answer.deletionDueAt != null}}"""
        }
    }

    private companion object {
        val WHY = "the reports are well founded ${UUID.randomUUID()}"
        const val METRIC = "moyi.admin.suspension"
        const val STEP_SECONDS = 30L
    }
}
