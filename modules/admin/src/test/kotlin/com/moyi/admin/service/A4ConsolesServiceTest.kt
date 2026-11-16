package com.moyi.admin.service

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.ScriptedJobs
import com.moyi.common.core.FeatureFlag
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.RebuildResult
import com.moyi.gratitude.api.StreakFigures
import com.moyi.gratitude.api.StreakRebuild
import com.moyi.scheduling.api.CloseRun
import com.moyi.support.api.TicketCloseOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * A4's three consoles (spec §3, §8; plan Task 6) against the real schema: the jobs (scripted ports,
 * [ScriptedJobs]), the flags (the real table) and the support inbox (the real functions). Each
 * outcome's audit row, the role, the order of the checks, and that the note reaches only the row.
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
@Suppress("LongParameterList", "LargeClass") // The three consoles and what a case is read in; one class per slice, as A3's.
internal class A4ConsolesServiceTest(
    @Autowired private val jobs: JobsConsole,
    @Autowired private val flags: FlagConsole,
    @Autowired private val support: SupportConsole,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        ScriptedJobs.reset()
        ContentAccessServiceTest.FailAfterRecord.armed = false
        jdbc.update("DELETE FROM feature_flags")
        jdbc.update("DELETE FROM support_ticket_log")
        jdbc.update("DELETE FROM support_tickets")
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, credentials_changed_at, status) VALUES (?, ?, 'ADMIN', now(), 'ACTIVE')",
            admin,
            "a4-$admin@example.com",
        )
    }

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
        ContentAccessServiceTest.FailAfterRecord.armed = false
    }

    // --- the jobs ----------------------------------------------------------------------------------------------------

    @Test
    fun `every role may see the jobs and a date's check, and neither writes a row`() {
        AdminRole.entries.forEach { role ->
            acting(role) {
                jobs
                    .page()
                    .jobs
                    .single()
                    .name shouldBe "close-days"
                jobs.settlement(LocalDate.of(2026, 11, 10)).pastDue shouldBe 2
            }
        }
        audited().shouldBeEmpty()
    }

    @Test
    fun `a run by hand writes its STARTED row, committed before the work begins, and answers the counts`(output: CapturedOutput) {
        var seenDuringRun: List<String> = emptyList()
        ScriptedJobs.duringRun = { seenDuringRun = audited().map { it.summary() } }
        val before = counted("moyi.admin.job.run", "job", "close-days", "result", "STARTED")

        val run = acting(AdminRole.ADMIN) { jobs.runClose("  $WHY\n", admin, null) }

        (run as CloseRun.Ran).evaluated shouldBe 4
        seenDuringRun shouldBe listOf("JOB_RUN|SUCCEEDED|null|null")
        val row = audited().single()
        row.justification shouldBe WHY
        row.metadata shouldBe """{"job": "close-days", "result": "STARTED"}"""
        counted("moyi.admin.job.run", "job", "close-days", "result", "STARTED") shouldBe before + 1
        output.out shouldContain "job run by hand admin=$admin job=close-days Ran(created=1"
        output.out shouldNotContain WHY
    }

    @Test
    fun `a run while the lock is held elsewhere is one REFUSED BUSY row and no work`() {
        ScriptedJobs.next = CloseRun.Busy

        acting(AdminRole.ADMIN) { jobs.runClose(WHY, admin, null) } shouldBe CloseRun.Busy

        ScriptedJobs.calls shouldBe listOf("run")
        audited().single().let {
            it.summary() shouldBe "JOB_RUN|REFUSED|null|null"
            it.metadata shouldBe """{"job": "close-days", "result": "BUSY"}"""
        }
    }

    @Test
    fun `a run whose audit row cannot be written does no work`() {
        ContentAccessServiceTest.FailAfterRecord.armed = true

        shouldThrow<IllegalStateException> { acting(AdminRole.ADMIN) { jobs.runClose(WHY, admin, null) } }

        ScriptedJobs.calls shouldBe listOf("run")
        audited().shouldBeEmpty()
    }

    @Test
    fun `a run needs ADMIN and a note, each refused before the job is asked`() {
        listOf(AdminRole.SUPPORT, AdminRole.MODERATOR).forEach { role ->
            withClue(role) { shouldThrow<AccessDeniedException> { acting(role) { jobs.runClose(WHY, admin, null) } } }
        }
        listOf("", "   ", "\u0000x", "x".repeat(401)).forEach { note ->
            shouldThrow<InvalidModerationNote> { acting(AdminRole.ADMIN) { jobs.runClose(note, admin, null) } }
        }
        ScriptedJobs.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
    }

    @Test
    fun `a rebuild writes STREAK_RECALCULATED with its result and figures, NO_DAYS refused`() {
        val bond = UUID.randomUUID()
        val before = StreakFigures(9, 9, 0, 0)
        val after = StreakFigures(3, 3, 0, 3)
        ScriptedJobs.rebuild = StreakRebuild(RebuildResult.CHANGED, before, after)

        acting(AdminRole.ADMIN) { jobs.rebuild(bond, WHY, admin, null) }.result shouldBe RebuildResult.CHANGED
        ScriptedJobs.rebuild = StreakRebuild(RebuildResult.NO_DAYS, null, null)
        acting(AdminRole.ADMIN) { jobs.rebuild(bond, WHY, admin, null) }.result shouldBe RebuildResult.NO_DAYS

        val (changed, none) = audited()
        changed.summary() shouldBe "STREAK_RECALCULATED|SUCCEEDED|BOND|$bond"
        changed.metadata shouldContain """"result": "CHANGED""""
        changed.metadata shouldContain """"before": {"current": 9, "longest": 9, "completeDays": 0, "freezesAvailable": 0}"""
        changed.metadata shouldContain """"after": {"current": 3, "longest": 3, "completeDays": 3, "freezesAvailable": 0}"""
        changed.justification shouldBe WHY
        none.summary() shouldBe "STREAK_RECALCULATED|REFUSED|BOND|$bond"
        none.metadata shouldBe """{"result": "NO_DAYS"}"""
    }

    @Test
    fun `a rebuild needs ADMIN and a note, refused before the port`() {
        shouldThrow<AccessDeniedException> { acting(AdminRole.MODERATOR) { jobs.rebuild(UUID.randomUUID(), WHY, admin, null) } }
        shouldThrow<InvalidModerationNote> { acting(AdminRole.ADMIN) { jobs.rebuild(UUID.randomUUID(), " ", admin, null) } }
        ScriptedJobs.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
    }

    // --- the flags ---------------------------------------------------------------------------------------------------

    @Test
    fun `switching a flag writes it and a FEATURE_FLAG_CHANGED row, and to its own value is UNCHANGED, nothing written to it`(
        output: CapturedOutput,
    ) {
        acting(AdminRole.ADMIN) { flags.set(FeatureFlag.DAILY_REMINDERS, false, WHY, admin, null) } shouldBe FlagChange.CHANGED
        acting(AdminRole.ADMIN) { flags.set(FeatureFlag.DAILY_REMINDERS, false, WHY, admin, null) } shouldBe FlagChange.UNCHANGED
        acting(AdminRole.ADMIN) { flags.set(FeatureFlag.AI_EMBEDDINGS, true, WHY, admin, null) } shouldBe FlagChange.UNCHANGED

        jdbc.queryForList("SELECT key || '=' || enabled FROM feature_flags", String::class.java) shouldBe listOf("DAILY_REMINDERS=false")
        val rows = audited()
        rows.map { it.summary() } shouldBe
            listOf(
                "FEATURE_FLAG_CHANGED|SUCCEEDED|FLAG|DAILY_REMINDERS",
                "FEATURE_FLAG_CHANGED|REFUSED|FLAG|DAILY_REMINDERS",
                "FEATURE_FLAG_CHANGED|REFUSED|FLAG|AI_EMBEDDINGS",
            )
        rows[0].metadata shouldBe """{"to": false, "from": true, "result": "CHANGED"}"""
        rows[1].metadata shouldBe """{"to": false, "from": false, "result": "UNCHANGED"}"""
        rows.all { it.justification == WHY } shouldBe true
        output.out shouldContain "feature flag admin=$admin flag=DAILY_REMINDERS from=true to=false result=CHANGED"
        output.out shouldNotContain WHY
        acting(AdminRole.ADMIN) { flags.states() }.single { it.flag == FeatureFlag.DAILY_REMINDERS }.on shouldBe false
    }

    @Test
    fun `the flags are ADMIN's alone, and a bad note writes nothing`() {
        listOf(AdminRole.SUPPORT, AdminRole.MODERATOR).forEach { role ->
            shouldThrow<AccessDeniedException> { acting(role) { flags.states() } }
            shouldThrow<AccessDeniedException> { acting(role) { flags.set(FeatureFlag.DAILY_REMINDERS, false, WHY, admin, null) } }
        }
        shouldThrow<InvalidModerationNote> { acting(AdminRole.ADMIN) { flags.set(FeatureFlag.DAILY_REMINDERS, false, "", admin, null) } }
        jdbc.queryForObject("SELECT count(*) FROM feature_flags", Int::class.java) shouldBe 0
        audited().shouldBeEmpty()
    }

    // --- the support inbox -------------------------------------------------------------------------------------------

    @Test
    fun `every role may list and read, each writing its log row and its audit row, and an unknown ticket is REFUSED`() {
        val ticket = ticket()
        AdminRole.entries.forEach { role ->
            acting(role) {
                support.openCount() shouldBe 1
                support.inbox(admin, null).single().ticketId shouldBe ticket
                support.ticket(ticket, admin, null)!!.body shouldBe "my streak broke"
            }
        }
        val unknown = UUID.randomUUID()
        acting(AdminRole.SUPPORT) { support.ticket(unknown, admin, null) } shouldBe null

        val rows = audited()
        rows.count { it.summary() == "SUPPORT_INBOX_VIEWED|SUCCEEDED|null|null" } shouldBe 3
        rows.count { it.summary() == "SUPPORT_TICKET_READ|SUCCEEDED|SUPPORT_TICKET|$ticket" } shouldBe 3
        rows.last().summary() shouldBe "SUPPORT_TICKET_READ|REFUSED|SUPPORT_TICKET|$unknown"
        rows.last().metadata shouldBe """{"result": "NOT_FOUND"}"""
        jdbc.queryForList("SELECT action FROM support_ticket_log ORDER BY at, id", String::class.java).distinct() shouldBe
            listOf("LISTED", "READ")
        jdbc.queryForObject("SELECT count(*) FROM support_ticket_log WHERE actor = ?", Int::class.java, "admin:$admin") shouldBe 6
    }

    @Test
    fun `a close inside the step-up window empties the ticket, a second is NOT_OPEN, and both rows carry the note`() {
        val ticket = ticket()

        acting(AdminRole.SUPPORT) { support.close(ticket, WHY, clock.instant(), admin, null) } shouldBe TicketCloseOutcome.CLOSED
        acting(AdminRole.SUPPORT) { support.close(ticket, WHY, clock.instant(), admin, null) } shouldBe TicketCloseOutcome.NOT_OPEN

        val rows = audited()
        rows.map { it.summary() } shouldBe
            listOf("SUPPORT_TICKET_CLOSED|SUCCEEDED|SUPPORT_TICKET|$ticket", "SUPPORT_TICKET_CLOSED|REFUSED|SUPPORT_TICKET|$ticket")
        rows.map { it.metadata } shouldBe listOf("""{"result": "CLOSED"}""", """{"result": "NOT_OPEN"}""")
        rows.all { it.justification == WHY } shouldBe true
        jdbc.queryForObject("SELECT body IS NULL FROM support_tickets WHERE id = ?", Boolean::class.java, ticket) shouldBe true
    }

    @Test
    fun `a close with a stale or missing proof is refused before the note or the ticket is looked at`() {
        val ticket = ticket()
        val stale = clock.instant().minus(Duration.ofMinutes(5)).minusSeconds(1)

        listOf(null, stale).forEach { at ->
            shouldThrow<StepUpRequired> { acting(AdminRole.SUPPORT) { support.close(ticket, "", at, admin, null) } }
        }
        shouldThrow<InvalidModerationNote> { acting(AdminRole.SUPPORT) { support.close(ticket, "", clock.instant(), admin, null) } }

        audited().shouldBeEmpty()
        jdbc.queryForObject("SELECT status FROM support_tickets WHERE id = ?", String::class.java, ticket) shouldBe "OPEN"
        jdbc.queryForObject("SELECT count(*) FROM support_ticket_log", Int::class.java) shouldBe 0
    }

    @Test
    fun `nothing of a ticket's words reaches the audit row`() {
        val ticket = ticket()
        acting(AdminRole.SUPPORT) {
            support.ticket(ticket, admin, null)
            support.close(ticket, WHY, clock.instant(), admin, null)
        }
        audited().forEach { row -> listOf("streak", "Help", "MOYI-7").forEach { row.metadata shouldNotContain it } }
    }

    private fun ticket(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO support_tickets (id, user_id, subject, body, reference, status, created_at) " +
                "VALUES (?, ?, 'Help', 'my streak broke', 'MOYI-7', 'OPEN', now())",
            id,
            UUID.randomUUID(),
        )
        return id
    }

    private fun <T> acting(
        role: AdminRole,
        block: () -> T,
    ): T {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(admin, null, listOf(SimpleGrantedAuthority("ROLE_${role.name}")))
        SecurityContextHolder.setContext(context)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun counted(
        name: String,
        vararg tags: String,
    ): Double = meters.counter(name, *tags).count()

    private fun audited(): List<Row> =
        jdbc.query(
            "SELECT action, outcome, target_type, target_id, justification, metadata::text AS metadata FROM audit_log " +
                "WHERE actor_id = ? AND action IN (${A4.joinToString { "'$it'" }}) ORDER BY id",
            { rs, _ ->
                Row(
                    rs.getString("action"),
                    rs.getString("outcome"),
                    rs.getString("target_type"),
                    rs.getString("target_id"),
                    rs.getString("justification"),
                    rs.getString("metadata"),
                )
            },
            admin,
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
        fun summary(): String = "$action|$outcome|$targetType|$targetId"
    }

    private companion object {
        val WHY = "the job missed a quarter ${UUID.randomUUID()}"
        val A4 =
            listOf(
                AuditAction.JOB_RUN,
                AuditAction.STREAK_RECALCULATED,
                AuditAction.FEATURE_FLAG_CHANGED,
                AuditAction.SUPPORT_INBOX_VIEWED,
                AuditAction.SUPPORT_TICKET_READ,
                AuditAction.SUPPORT_TICKET_CLOSED,
            )
    }
}
