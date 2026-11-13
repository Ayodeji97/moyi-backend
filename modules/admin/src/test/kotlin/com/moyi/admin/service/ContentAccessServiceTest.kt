package com.moyi.admin.service

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.FakeMediaStore
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.admin.service.AdminConsoleServiceTest.Companion.code
import com.moyi.admin.web.AdminAuthorities
import com.moyi.admin.web.SecondFactor
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.ReportedEntryRead
import com.moyi.gratitude.api.WordsRefusal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.ClassUtils
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * `ContentAccess` (A5 spec §5, §6, §7; plan Task 3) and the per-read step-up `SecondFactor.proveNow`
 * makes, against the real schema and gratitude's real `ReportedEntryText` port.
 *
 * Here, not in `app`: both are `internal` to this module. The port is wrapped by [Spy], found by
 * name (only `ContentAccess` may import it, `ArchitectureTest`), so that a refusal can be shown
 * never to have reached it; `AuditTrail` can be made to throw just after its row is written
 * ([FailAfterRecord]), the forced failure of spec §11.
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
internal class ContentAccessServiceTest(
    @Autowired private val content: ContentAccess,
    @Autowired private val console: ModerationConsole,
    @Autowired private val secondFactor: SecondFactor,
    @Autowired private val enrolment: AdminEnrolment,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        Spy.calls.clear()
        FailAfterRecord.armed = false
        FailAfterRecord.fired = false
        FakeMediaStore.reset()
    }

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
        FailAfterRecord.armed = false
    }

    // --- the read's row ----------------------------------------------------------------------------------------------

    @Test
    fun `shown writes one ENTRY_TEXT_READ row and the report's WORDS_READ row`(output: CapturedOutput) {
        val marker = marker()
        val (entry, date) = entry(marker)
        val report = report(entry, "HARASSMENT")
        val shown = counted("SHOWN")

        val result = acting(AdminRole.ADMIN) { content.readReportedEntry(report, "HARASSMENT", "  $WHY\n", proof(), admin, null) }

        result.outcome shouldBe EntryTextOutcome.SHOWN
        val read = result.read.shouldBeInstanceOf<ReportedEntryRead.Shown>()
        read.entryId shouldBe entry
        read.date shouldBe date
        read.words.value shouldBe marker
        val row = audited().single()
        row.action shouldBe AuditAction.ENTRY_TEXT_READ.name
        row.outcome shouldBe AuditOutcome.SUCCEEDED.name
        row.targetType shouldBe "ENTRY"
        row.targetId shouldBe entry.toString()
        row.justification shouldBe WHY
        row.metadata shouldBe """{"result": "SHOWN", "reportId": "$report", "reportDriven": true}"""
        wordsRead(report) shouldContainExactly listOf("admin:$admin")
        Spy.calls shouldContainExactly listOf("read")
        counted("SHOWN") shouldBe shown + 1
        output.out shouldContain "content read admin=$admin report=$report outcome=SHOWN"
        nowhere(marker, output)
    }

    @Test
    fun `an erased entry is SUCCEEDED, read nothing, and is logged WORDS_READ`(output: CapturedOutput) {
        val marker = marker()
        val entry = entry(marker).first
        val report = report(entry, "OTHER")
        jdbc.update("UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = now() WHERE id = ?", entry)

        val result = acting(AdminRole.ADMIN) { content.readReportedEntry(report, "OTHER", WHY, proof(), admin, null) }

        result.outcome shouldBe EntryTextOutcome.ERASED
        result.read shouldBe ReportedEntryRead.Erased(entry)
        audited().single().assert(AuditOutcome.SUCCEEDED, "ENTRY", entry, "ERASED", report)
        wordsRead(report) shouldContainExactly listOf("admin:$admin")
        nowhere(marker, output)
    }

    @Test
    fun `each refusal is REFUSED on its target and logs no WORDS_READ`(output: CapturedOutput) {
        val marker = marker()
        val decided = report(entry(marker).first, "OTHER")
        acting(AdminRole.MODERATOR) { console.dismiss(decided, "OTHER", "decided first", admin, null) } shouldBe DismissOutcome.DISMISSED
        val stale = report(entry(marker).first, "OTHER")
        val image = report(entry(marker).first, "NON_CONSENSUAL")
        val member = memberReport()
        val unknown = UUID.randomUUID()
        val entryOf = { id: UUID -> jdbc.queryForObject("SELECT entry_id FROM entry_reports WHERE id = ?", UUID::class.java, id)!! }

        val answers =
            acting(AdminRole.ADMIN) {
                listOf(
                    content.readReportedEntry(decided, "OTHER", WHY, proof(), admin, null),
                    content.readReportedEntry(stale, "HARASSMENT", WHY, proof(), admin, null),
                    content.readReportedEntry(image, "NON_CONSENSUAL", WHY, proof(), admin, null),
                    content.readReportedEntry(member, "HARASSMENT", WHY, proof(), admin, null),
                    content.readReportedEntry(unknown, "OTHER", WHY, proof(), admin, null),
                )
            }

        answers.map { it.outcome } shouldContainExactly
            listOf(
                EntryTextOutcome.NOT_OPEN,
                EntryTextOutcome.STALE,
                EntryTextOutcome.NOT_ABOUT_WORDS,
                EntryTextOutcome.NOT_ABOUT_WORDS,
                EntryTextOutcome.NOT_OPEN,
            )
        val rows = audited().filter { it.action == AuditAction.ENTRY_TEXT_READ.name }
        rows[0].assert(AuditOutcome.REFUSED, "ENTRY", entryOf(decided), "NOT_OPEN", decided)
        rows[1].assert(AuditOutcome.REFUSED, "ENTRY", entryOf(stale), "STALE", stale)
        rows[2].assert(AuditOutcome.REFUSED, "ENTRY", entryOf(image), "NOT_ABOUT_WORDS", image)
        rows[3].assert(AuditOutcome.REFUSED, "REPORT", member, "NOT_ABOUT_WORDS", member)
        rows[4].assert(AuditOutcome.REFUSED, "REPORT", unknown, "NOT_OPEN", unknown)
        listOf(decided, stale, image, member, unknown).forEach { wordsRead(it).shouldBeEmpty() }
        nowhere(marker, output)
    }

    // --- refused before the port -------------------------------------------------------------------------------------

    @Test
    fun `an invalid justification throws before the port is called, and writes no row of either kind`() {
        val report = report(entry(marker()).first, "HARASSMENT")

        listOf("", "   ", "x".repeat(ModerationNote.MAX_CODE_POINTS + 1), "why\u0000not").forEach { bad ->
            acting(AdminRole.ADMIN) {
                shouldThrow<InvalidModerationNote> { content.readReportedEntry(report, "HARASSMENT", bad, proof(), admin, null) }
            }
        }

        Spy.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()
    }

    @Test
    fun `another admin's step-up opens nothing`() {
        val report = report(entry(marker()).first, "HARASSMENT")

        acting(AdminRole.ADMIN) {
            shouldThrow<IllegalArgumentException> {
                content.readReportedEntry(report, "HARASSMENT", WHY, FreshStepUp(UUID.randomUUID(), clock.instant()), admin, null)
            }
        }

        Spy.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()
    }

    @Test
    fun `a MODERATOR and SUPPORT are refused before the port, with no row of either kind`() {
        val report = report(entry(marker()).first, "HARASSMENT")

        listOf(AdminRole.MODERATOR, AdminRole.SUPPORT).forEach { role ->
            acting(role) {
                shouldThrow<AccessDeniedException> { content.readReportedEntry(report, "HARASSMENT", WHY, proof(), admin, null) }
            }
        }

        Spy.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()
    }

    @Test
    fun `whyNot is the page's question for a MODERATOR, writes nothing, and SUPPORT is refused`() {
        val words = report(entry(marker()).first, "HARASSMENT")
        val image = report(entry(marker()).first, "MINOR_SEXUAL_CONTENT")
        val member = memberReport()

        acting(AdminRole.MODERATOR) {
            content.whyNot(words).shouldBeNull()
            content.whyNot(image) shouldBe WordsRefusal.IMAGE_REASON
            content.whyNot(member) shouldBe WordsRefusal.MEMBER_REPORT
            content.whyNot(UUID.randomUUID()) shouldBe WordsRefusal.NOT_OPEN
        }
        acting(AdminRole.SUPPORT) { shouldThrow<AccessDeniedException> { content.whyNot(words) } }

        Spy.calls shouldContainExactly List(4) { "whyNot" }
        audited().shouldBeEmpty()
        wordsRead(words).shouldBeEmpty()
    }

    // --- atomicity ---------------------------------------------------------------------------------------------------

    @Test
    fun `a read that fails after its audit row commits neither row, and the step-up it spent stays spent`(output: CapturedOutput) {
        val (id, secret) = enrolledAdmin()
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")
        val start = clock.instant()
        try {
            clock.advance(Duration.ofSeconds(STEP_SECONDS))
            val fresh = code(secret, clock.instant())
            val proved = secondFactor.proveNow(id, fresh, null).shouldBeInstanceOf<SecondFactor.Fresh.Proved>()
            val spent = lastStep(id)

            FailAfterRecord.armed = true
            acting(AdminRole.ADMIN, id) {
                shouldThrow<IllegalStateException> { content.readReportedEntry(report, "HARASSMENT", WHY, proved.stepUp, id, null) }
            }
            FailAfterRecord.armed = false

            Spy.calls shouldContainExactly listOf("read")
            FailAfterRecord.fired shouldBe true
            rowsOf(id).shouldContainExactly(listOf("ADMIN_STEP_UP|SUCCEEDED"))
            lastStep(id) shouldBe spent
            (spent != null) shouldBe true
            wordsRead(report).shouldBeEmpty()
            nowhere(marker, output)
        } finally {
            clock.set(start)
        }
    }

    // --- proveNow ----------------------------------------------------------------------------------------------------

    @Test
    fun `proveNow asks for a code on a blank one whatever the session's window says, and never writes the window`() {
        val (id, secret) = enrolledAdmin()
        val start = clock.instant()
        try {
            clock.advance(Duration.ofSeconds(STEP_SECONDS))
            val windowOpen = clock.instant().minusSeconds(1)
            val session = sessionWith(windowOpen)

            secondFactor.proveNow(id, "", null) shouldBe SecondFactor.Fresh.Missing
            secondFactor.proveNow(id, "   ", null) shouldBe SecondFactor.Fresh.Missing
            rowsOf(id).shouldBeEmpty()

            val fresh = code(secret, clock.instant())
            val proved = secondFactor.proveNow(id, " $fresh ", null).shouldBeInstanceOf<SecondFactor.Fresh.Proved>()
            proved.stepUp.adminId shouldBe id
            proved.stepUp.at shouldBe clock.instant()
            session.getAttribute(AdminAuthorities.SECOND_FACTOR_AT) shouldBe windowOpen

            secondFactor.proveNow(id, fresh, null) shouldBe SecondFactor.Fresh.Refused
            rowsOf(id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED", "ADMIN_STEP_UP|REFUSED")

            clock.advance(Duration.ofSeconds(STEP_SECONDS))
            val unset = sessionWith(null)
            secondFactor.proveNow(id, code(secret, clock.instant()), null).shouldBeInstanceOf<SecondFactor.Fresh.Proved>()
            unset.getAttribute(AdminAuthorities.SECOND_FACTOR_AT).shouldBeNull()
        } finally {
            clock.set(start)
        }
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

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

    /** A request bound to this thread whose session holds [secondFactorAt], as a signed-in admin's would. */
    private fun sessionWith(secondFactorAt: Instant?): MockHttpSession {
        val request = MockHttpServletRequest()
        val session = MockHttpSession()
        secondFactorAt?.let { session.setAttribute(AdminAuthorities.SECOND_FACTOR_AT, it) }
        request.setSession(session)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        return session
    }

    private fun marker(): String = "words only the author wrote ${UUID.randomUUID()}"

    /** A revealed entry holding [text], on a bond-day of its own: its id and the day's date. */
    private fun entry(text: String): Pair<UUID, LocalDate> {
        val bond = UUID.randomUUID()
        val day = UUID.randomUUID()
        val entry = UUID.randomUUID()
        val date = LocalDate.of(2026, 1, 1).plusDays((0L..3000L).random())
        jdbc.update(
            "INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, " +
                "closed_at, version) VALUES (?, ?, ?, 'REVEALED', 'UTC', now(), now(), 2, now(), now(), 0)",
            day,
            bond,
            date,
        )
        jdbc.update(
            "INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at, " +
                "revealed_at) VALUES (?, ?, ?, ?, ?, 'REVEALED', now(), now(), now(), now())",
            entry,
            day,
            bond,
            UUID.randomUUID(),
            text,
        )
        return entry to date
    }

    private fun report(
        entry: UUID,
        reason: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, (SELECT bond_id FROM entries WHERE id = ?), ?, ?, NULL, 'OPEN', ?)",
            id,
            entry,
            entry,
            UUID.randomUUID(),
            reason,
            Timestamp.from(clock.instant()),
        )
        return id
    }

    private fun memberReport(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, reported_member_id, bond_id, reporter_member_id, reason, status, created_at) " +
                "VALUES (?, 'MEMBER', ?, ?, ?, 'HARASSMENT', 'OPEN', ?)",
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Timestamp.from(clock.instant()),
        )
        return id
    }

    /** An enrolled, active `ADMIN`: its id and its TOTP secret. Enrolment spends the code step of now. */
    private fun enrolledAdmin(): Pair<UUID, ByteArray> {
        val email = "content-${UUID.randomUUID().toString().take(8)}@example.com"
        val token = jdbc.queryForObject("SELECT admin_invite(?::citext, 'ADMIN')", String::class.java, email)!!
        enrolment.begin(token)
        val secret = secretOf(email)
        enrolment.complete(
            token,
            AdminConsoleServiceTest.PASSWORD,
            AdminConsoleServiceTest.PASSWORD,
            code(secret, clock.instant()),
            null,
        ) shouldBe EnrolmentResult.Done
        return jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!! to secret
    }

    private fun secretOf(email: String): ByteArray =
        TotpSecretCipher(Base64.getDecoder().decode(AdminConsoleServiceTest.KEY)).decrypt(
            jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!,
        )

    private fun lastStep(id: UUID): Long? =
        jdbc.queryForObject("SELECT totp_last_step FROM admin_accounts WHERE id = ?", Long::class.javaObjectType, id)

    /** [id]'s step-up and read rows, `action|outcome`, in order. */
    private fun rowsOf(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || outcome FROM audit_log " +
                    "WHERE actor_id = ? AND action IN ('ADMIN_STEP_UP', 'ENTRY_TEXT_READ') ORDER BY id",
                String::class.java,
                id,
            ).filterNotNull()

    private fun wordsRead(report: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT actor FROM entry_report_log WHERE report_id = ? AND action = 'WORDS_READ' ORDER BY at, id",
                String::class.java,
                report,
            ).filterNotNull()

    private fun counted(outcome: String): Double = meters.counter(METRIC, "outcome", outcome).count()

    /** [marker] is in no column of any `audit_log` or `entry_report_log` row, and in no log line; nor is the justification. */
    private fun nowhere(
        marker: String,
        output: CapturedOutput,
    ) {
        jdbc.queryForObject("SELECT count(*) FROM audit_log a WHERE a::text LIKE ?", Int::class.java, "%$marker%") shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM entry_report_log l WHERE l::text LIKE ?", Int::class.java, "%$marker%") shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM entry_report_log l WHERE l::text LIKE ?", Int::class.java, "%$WHY%") shouldBe 0
        output.all shouldNotContain marker
        output.all shouldNotContain WHY
    }

    /** This test's admin's audit rows, in the order written. */
    private fun audited(): List<Row> =
        jdbc.query(
            "SELECT action, outcome, target_type, target_id, justification, metadata::text AS metadata FROM audit_log " +
                "WHERE actor_id = ? ORDER BY id",
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
        fun assert(
            outcome: AuditOutcome,
            targetType: String,
            target: UUID,
            result: String,
            report: UUID,
        ) {
            action shouldBe AuditAction.ENTRY_TEXT_READ.name
            this.outcome shouldBe outcome.name
            this.targetType shouldBe targetType
            targetId shouldBe target.toString()
            justification shouldBe WHY
            metadata shouldBe """{"result": "$result", "reportId": "$report", "reportDriven": true}"""
        }
    }

    /** The test's two hooks: [Spy] on the port, [FailAfterRecord] on `AuditTrail`. */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor") // Spring instantiates a configuration; an object it cannot.
    class Rig {
        companion object {
            @Bean
            @JvmStatic
            fun spyingOnThePort(): BeanPostProcessor = Spy

            @Bean
            @JvmStatic
            fun failingAfterRecord(): BeanPostProcessor = FailAfterRecord
        }
    }

    /**
     * Wraps gratitude's `ReportedEntryText`, found by name (no file here may import it), and
     * notes each call. Runs after the transaction proxy, so the port keeps its `MANDATORY`.
     */
    internal object Spy : BeanPostProcessor {
        private const val PORT = "com.moyi.gratitude.api.ReportedEntryText"
        val calls: MutableList<String> = CopyOnWriteArrayList()

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            val port = ClassUtils.getAllInterfacesForClass(bean.javaClass).firstOrNull { it.name == PORT } ?: return bean
            return Proxy.newProxyInstance(port.classLoader, arrayOf(port)) { _, method, args ->
                if (method.declaringClass == port) calls += method.name
                try {
                    method.invoke(bean, *(args ?: emptyArray()))
                } catch (thrown: InvocationTargetException) {
                    throw thrown.targetException
                }
            }
        }
    }

    /**
     * When [armed], `AuditTrail.record` writes (and flushes) its row and then throws: a read whose
     * transaction fails after both its rows were written (spec §11).
     */
    internal object FailAfterRecord : BeanPostProcessor {
        @Volatile var armed = false

        @Volatile var fired = false

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (bean is Advised && bean.targetSource.targetClass == AuditTrail::class.java) {
                bean.addAdvice(
                    0,
                    MethodInterceptor { invocation ->
                        val answer = invocation.proceed()
                        if (armed && invocation.method.name == "record") {
                            fired = true
                            error("forced after the audit row (test)")
                        }
                        answer
                    },
                )
            }
            return bean
        }
    }

    private companion object {
        val WHY = "the reporter's detail is not enough ${UUID.randomUUID()}"
        const val METRIC = "moyi.admin.content.read"
        const val STEP_SECONDS = 30L
    }
}
