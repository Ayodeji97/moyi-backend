package com.moyi.admin.web

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.admin.service.AdminConsoleServiceTest
import com.moyi.admin.service.AdminConsoleServiceTest.Companion.code
import com.moyi.admin.service.AdminEnrolment
import com.moyi.admin.service.AdminSignIn
import com.moyi.admin.service.EnrolmentResult
import com.moyi.admin.service.TotpStep
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.validation.BindingResult
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /admin/reports/{id}/entry-text` and the report page's half of it (A5 spec §5; plan Task 4),
 * through the admin chain with its CSRF check, against the real schema and gratitude's real port.
 *
 * Each admin here is enrolled for real, so its codes are what an authenticator app shows and
 * `AdminSignIn.stepUp`'s replay check sees them. The clock moves one TOTP step before each new
 * code and is put back after each test.
 */
@SpringBootTest(
    classes = [AdminTestApplication::class],
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=${AdminConsoleServiceTest.KEY}",
    ],
)
@AutoConfigureMockMvc
@Import(AdminConsoleServiceTest.TimeConfiguration::class, EntryTextControllerTest.Rig::class)
internal class EntryTextControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val enrolment: AdminEnrolment,
    @Autowired private val signIn: AdminSignIn,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var start: Instant

    @BeforeEach
    fun remember() {
        start = clock.instant()
        FailOnRead.armed = false
    }

    @AfterEach
    fun putBack() {
        clock.set(start)
        FailOnRead.armed = false
    }

    // --- the outcomes ------------------------------------------------------------------------------------------------

    @Test
    fun `SHOWN is the words page itself, escaped, with only the words, the date and the report, and never cached`() {
        val admin = enrolled("ADMIN")
        val text = "${marker()} $PLANTED"
        val (entry, date) = entry(text)
        val report = report(entry, "HARASSMENT")

        val posted = read(admin, report, "HARASSMENT", nextCode(admin))

        val response = posted.response
        response.status shouldBe 200
        posted.modelAndView!!.viewName shouldBe "admin/entry-text"
        // Spring adds a BindingResult beside each model object; the handler's own keys are these three.
        posted.modelAndView!!
            .model.keys
            .filterNot { it.startsWith(BindingResult.MODEL_KEY_PREFIX) }
            .toSet() shouldBe
            setOf("words", "date", "reportId")
        posted.modelAndView!!.model["date"] shouldBe date
        posted.modelAndView!!.model.toString() shouldNotContain text
        neverKept(posted)
        response.page() shouldContain ESCAPED
        response.page() shouldNotContain PLANTED
        response.page() shouldContain date.toString()
        readRows(admin.id) shouldContainExactly listOf("SUCCEEDED")
        wordsRead(report) shouldContainExactly listOf("admin:${admin.id}")
        // A proved code opens no window for the acts the session's step-up gates.
        admin.session.getAttribute(AdminAuthorities.SECOND_FACTOR_AT).shouldBeNull()
    }

    @Test
    fun `ERASED is the words page with the spec's sentence and no words`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val entry = entry(marker).first
        val report = report(entry, "OTHER")
        jdbc.update("UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = now() WHERE id = ?", entry)

        val posted = read(admin, report, "OTHER", nextCode(admin))

        posted.response.status shouldBe 200
        posted.modelAndView!!.viewName shouldBe "admin/entry-text"
        posted.modelAndView!!.model.containsKey("words") shouldBe false
        posted.response.page() shouldContain "Its author erased this entry. There are no words to read."
        neverKept(posted)
        readRows(admin.id) shouldContainExactly listOf("SUCCEEDED")
        wordsRead(report) shouldContainExactly listOf("admin:${admin.id}")
    }

    @Test
    fun `NOT_OPEN and STALE are the report's page with A2's lines, and nothing is read`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val decided = report(entry(marker).first, "OTHER")
        jdbc.update("UPDATE entry_reports SET status = 'DISMISSED', resolved_at = now(), detail = NULL WHERE id = ?", decided)
        val stale = report(entry(marker).first, "OTHER")

        val notOpen = read(admin, decided, "OTHER", nextCode(admin))
        val changed = read(admin, stale, "HARASSMENT", nextCode(admin))

        mapOf(notOpen to ReportsController.ALREADY_DECIDED, changed to ReportsController.STALE).forEach { (posted, line) ->
            withClue(line) {
                posted.response.status shouldBe 200
                posted.modelAndView!!.viewName shouldBe "admin/report"
                posted.response.page() shouldContain line
                posted.response.page() shouldNotContain marker
                neverKept(posted)
            }
        }
        readRows(admin.id) shouldContainExactly listOf("REFUSED", "REFUSED")
        wordsRead(decided).shouldBeEmpty()
        wordsRead(stale).shouldBeEmpty()
    }

    @Test
    fun `NOT_ABOUT_WORDS is the report's page saying why, for an image reason and for a member`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val image = report(entry(marker).first, "NON_CONSENSUAL")
        val member = memberReport()

        val images = read(admin, image, "NON_CONSENSUAL", nextCode(admin))
        val members = read(admin, member, "HARASSMENT", nextCode(admin))

        mapOf(images to IMAGE_REASON, members to MEMBER_REPORT).forEach { (posted, line) ->
            withClue(line) {
                posted.response.status shouldBe 200
                posted.modelAndView!!.viewName shouldBe "admin/report"
                posted.response.page() shouldContain line
                posted.response.page() shouldNotContain marker
                posted.response.page() shouldNotContain BUTTON
                neverKept(posted)
            }
        }
        readRows(admin.id) shouldContainExactly listOf("REFUSED", "REFUSED")
        wordsRead(image).shouldBeEmpty()
    }

    @Test
    fun `a malformed id is the 404 page, never cached, and nothing is proved or read`() {
        val admin = enrolled("ADMIN")

        val posted = read(admin, "not-a-uuid", "HARASSMENT", nextCode(admin))

        posted.response.status shouldBe 404
        neverKept(posted)
        rows(admin.id).shouldBeEmpty()
    }

    /**
     * A well-formed id no report has: the code is proved and spent first (step 3), the read answers
     * NOT_OPEN with no entry and is audited REFUSED on the REPORT target, and the page is the 404 page,
     * not the report's "already decided" (there is no report to show).
     */
    @Test
    fun `an unknown report is the 404 page after the code is spent, its refusal audited on the report`() {
        val admin = enrolled("ADMIN")
        val unknown = UUID.randomUUID()
        val code = nextCode(admin)

        val posted = read(admin, unknown, "HARASSMENT", code)

        posted.response.status shouldBe 404
        posted.response.page() shouldNotContain ReportsController.ALREADY_DECIDED
        neverKept(posted)
        rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED", "ENTRY_TEXT_READ|REFUSED")
        jdbc.queryForObject(
            "SELECT target_type || '|' || target_id || '|' || (metadata ->> 'result') FROM audit_log " +
                "WHERE actor_id = ? AND action = 'ENTRY_TEXT_READ'",
            String::class.java,
            admin.id,
        ) shouldBe "REPORT|$unknown|NOT_OPEN"
        read(admin, unknown, "HARASSMENT", code).response.status shouldBe 401
    }

    // --- the code and the justification ------------------------------------------------------------------------------

    @Test
    fun `a blank code with the session's window fresh is the code form, and nothing is proved or read`() {
        val admin = enrolled("ADMIN", secondFactorAt = clock.instant())
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")

        val posted = read(admin, report, "HARASSMENT", "")

        posted.response.status shouldBe 200
        posted.modelAndView!!.viewName shouldBe "admin/read-words"
        posted.response.page() shouldContain ReportsController.CODE_NEEDED_TO_READ
        posted.response.page() shouldContain """name="code""""
        posted.response.page() shouldNotContain marker
        neverKept(posted)
        rows(admin.id).shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()
    }

    @Test
    fun `a wrong code is 401 with the form, its refusal audited, and nothing read`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")

        val posted = read(admin, report, "HARASSMENT", "000000")

        posted.response.status shouldBe 401
        posted.modelAndView!!.viewName shouldBe "admin/read-words"
        posted.response.page() shouldContain AdminAuthController.WRONG_CODE
        posted.response.page() shouldNotContain marker
        neverKept(posted)
        rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|REFUSED")
        wordsRead(report).shouldBeEmpty()
    }

    @Test
    fun `an invalid justification is 422 before the code is touched, and the same code then reads`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")
        val code = nextCode(admin)
        val before = lastStep(admin.id)

        listOf("", "   ", "x".repeat(MAX_JUSTIFICATION + 1), "why\u0000not").forEach { bad ->
            val posted = read(admin, report, "HARASSMENT", code, justification = bad)
            withClue(bad.take(8)) {
                posted.response.status shouldBe 422
                posted.modelAndView!!.viewName shouldBe "admin/read-words"
                posted.response.page() shouldContain ReportsController.WHY_REFUSED
                neverKept(posted)
            }
        }

        lastStep(admin.id) shouldBe before
        rows(admin.id).shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()

        val accepted = read(admin, report, "HARASSMENT", code)
        accepted.response.status shouldBe 200
        accepted.modelAndView!!.viewName shouldBe "admin/entry-text"
        rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED", "ENTRY_TEXT_READ|SUCCEEDED")
    }

    @Test
    fun `a code just spent on a block opens nothing, though the session's window is fresh`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")
        val other = report(entry(marker()).first, "OTHER")
        val code = nextCode(admin)
        val uphold = mapOf("expectedReason" to "OTHER", "takedown" to "BLOCK", "note" to WHY, "code" to code)
        post(admin.session, "/admin/reports/$other/uphold", uphold).response.status shouldBe 302
        admin.session.getAttribute(AdminAuthorities.SECOND_FACTOR_AT).shouldNotBeNull()

        val posted = read(admin, report, "HARASSMENT", code)

        posted.response.status shouldBe 401
        posted.response.page() shouldContain AdminAuthController.WRONG_CODE
        posted.response.page() shouldNotContain marker
        readRows(admin.id).shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()
    }

    // --- the role ----------------------------------------------------------------------------------------------------

    @Test
    fun `a MODERATOR's and SUPPORT's hand-made POST is the 403 page, reads nothing and spends no code`() {
        listOf("MODERATOR", "SUPPORT").forEach { role ->
            val admin = enrolled(role)
            val marker = marker()
            val report = report(entry(marker).first, "HARASSMENT")
            val code = nextCode(admin)

            val posted = read(admin, report, "HARASSMENT", code)

            withClue(role) {
                posted.response.status shouldBe 403
                posted.response.page() shouldContain "Error 403"
                posted.response.page() shouldNotContain marker
                rows(admin.id).shouldBeEmpty()
                wordsRead(report).shouldBeEmpty()
                signIn.stepUp(admin.id, code, null).shouldBeInstanceOf<TotpStep.Accepted>()
            }
        }
    }

    // --- the step-up commits on its own ------------------------------------------------------------------------------

    @Test
    fun `a read that fails after the step-up leaves the code spent and its row committed, and shows nothing`() {
        val admin = enrolled("ADMIN")
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")
        val code = nextCode(admin)
        val before = lastStep(admin.id)

        FailOnRead.armed = true
        val posted = read(admin, report, "HARASSMENT", code)
        FailOnRead.armed = false

        posted.response.status shouldBe 500
        posted.response.page() shouldNotContain marker
        neverKept(posted)
        rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED")
        (lastStep(admin.id) != before) shouldBe true
        wordsRead(report).shouldBeEmpty()
        read(admin, report, "HARASSMENT", code).response.status shouldBe 401
    }

    // --- the report's page -------------------------------------------------------------------------------------------

    @Test
    fun `the report's page offers an ADMIN the form, tells a MODERATOR an admin can read them, and shows no words`() {
        val admin = enrolled("ADMIN")
        val moderator = enrolled("MODERATOR")
        val marker = marker()
        val report = report(entry(marker).first, "HARASSMENT")
        val image = report(entry(marker).first, "MINOR_SEXUAL_CONTENT")

        val forAdmin = get(admin.session, "/admin/reports/$report")
        forAdmin.status shouldBe 200
        forAdmin.page() shouldContain BUTTON
        forAdmin.page() shouldContain "Why you need to read them"
        forAdmin.page() shouldContain """action="/admin/reports/$report/entry-text""""
        forAdmin.page() shouldNotContain MODERATOR_HINT
        forAdmin.page() shouldNotContain marker

        val forModerator = get(moderator.session, "/admin/reports/$report")
        forModerator.status shouldBe 200
        forModerator.page() shouldContain MODERATOR_HINT
        forModerator.page() shouldNotContain BUTTON
        forModerator.page() shouldNotContain "/entry-text"
        forModerator.page() shouldNotContain marker

        val imageForAdmin = get(admin.session, "/admin/reports/$image")
        imageForAdmin.page() shouldContain IMAGE_REASON
        imageForAdmin.page() shouldNotContain BUTTON
        imageForAdmin.page() shouldNotContain marker
        get(admin.session, "/admin/reports/${memberReport()}").page() shouldContain MEMBER_REPORT
        readRows(admin.id).shouldBeEmpty()
        wordsRead(report).shouldBeEmpty()
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    /** An enrolled admin: its id, its TOTP secret, and a two-factor session as the sign-in leaves it. */
    private class Admin(
        val id: UUID,
        val secret: ByteArray,
        val session: MockHttpSession,
    )

    /** An active admin of [role], enrolled through `AdminEnrolment` (which spends the code step of now). */
    private fun enrolled(
        role: String,
        secondFactorAt: Instant? = null,
    ): Admin {
        val email = "words-${UUID.randomUUID().toString().take(8)}@example.com"
        val token = jdbc.queryForObject("SELECT admin_invite(?::citext, ?)", String::class.java, email, role)!!
        enrolment.begin(token)
        val sealed = jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!
        val secret = TotpSecretCipher(Base64.getDecoder().decode(AdminConsoleServiceTest.KEY)).decrypt(sealed)
        enrolment.complete(
            token,
            AdminConsoleServiceTest.PASSWORD,
            AdminConsoleServiceTest.PASSWORD,
            code(secret, clock.instant()),
            null,
        ) shouldBe EnrolmentResult.Done
        val id = jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!!
        val changed =
            jdbc.queryForObject("SELECT credentials_changed_at FROM admin_accounts WHERE id = ?", OffsetDateTime::class.java, id)!!
        val authorities: List<GrantedAuthority> =
            listOf(AdminAuthorities.FACTOR_PASSWORD, AdminAuthorities.FACTOR_TOTP).map { FactorGrantedAuthority.fromAuthority(it) } +
                SimpleGrantedAuthority("ROLE_$role")
        val session =
            MockHttpSession().apply {
                val authentication = UsernamePasswordAuthenticationToken.authenticated(id, null, authorities)
                setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(authentication))
                setAttribute(AdminAuthorities.CREDENTIALS_CHANGED_AT, changed.toInstant())
                setAttribute(AdminAuthorities.ABSOLUTE_DEADLINE, clock.instant().plus(Duration.ofHours(1)))
                if (secondFactorAt != null) setAttribute(AdminAuthorities.SECOND_FACTOR_AT, secondFactorAt)
            }
        return Admin(id, secret, session)
    }

    /** The code an authenticator shows one step later than the clock now says, the clock moved there. */
    private fun nextCode(admin: Admin): String {
        clock.advance(Duration.ofSeconds(STEP_SECONDS))
        return code(admin.secret, clock.instant())
    }

    @Suppress("LongParameterList") // The form's four fields, and whose session posts them.
    private fun read(
        admin: Admin,
        report: Any,
        expectedReason: String,
        code: String,
        justification: String = WHY,
    ): MvcResult =
        post(
            admin.session,
            "/admin/reports/$report/entry-text",
            mapOf("expectedReason" to expectedReason, "justification" to justification, "code" to code),
        )

    private fun get(
        session: MockHttpSession,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
            }.andReturn()
            .response

    /** A form's POST with the CSRF token the console's home page carries for [session]. */
    private fun post(
        session: MockHttpSession,
        path: String,
        fields: Map<String, String>,
    ): MvcResult {
        val csrf = checkNotNull(CSRF.find(get(session, "/admin").contentAsString)) { "no CSRF field" }.groupValues[1]
        return mockMvc
            .post(path) {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", csrf)
                fields.forEach { (name, value) -> param(name, value) }
            }.andReturn()
    }

    /** Spec §5: the response is never stored, never sends a referrer, and is not a redirect or a flash. */
    private fun neverKept(posted: MvcResult) {
        val response = posted.response
        response.getHeader("Cache-Control") shouldBe "no-store"
        response.getHeader("Pragma") shouldBe "no-cache"
        response.getHeader("Referrer-Policy") shouldBe "no-referrer"
        response.redirectedUrl.shouldBeNull()
        posted.flashMap.shouldBeEmpty()
    }

    /** The body as a reader sees it: Thymeleaf writes an apostrophe it prints as `&#39;`. */
    private fun MockHttpServletResponse.page(): String = contentAsString.replace("&#39;", "'")

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

    private fun lastStep(id: UUID): Long? =
        jdbc.queryForObject("SELECT totp_last_step FROM admin_accounts WHERE id = ?", Long::class.javaObjectType, id)

    /** [id]'s step-up and read rows, `action|outcome`, in order. */
    private fun rows(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || outcome FROM audit_log " +
                    "WHERE actor_id = ? AND action IN ('ADMIN_STEP_UP', 'ENTRY_TEXT_READ') ORDER BY id",
                String::class.java,
                id,
            ).filterNotNull()

    /** [id]'s `ENTRY_TEXT_READ` outcomes, in order. */
    private fun readRows(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT outcome FROM audit_log WHERE actor_id = ? AND action = ? ORDER BY id",
                String::class.java,
                id,
                AuditAction.ENTRY_TEXT_READ.name,
            ).filterNotNull()

    private fun wordsRead(report: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT actor FROM entry_report_log WHERE report_id = ? AND action = 'WORDS_READ' ORDER BY at, id",
                String::class.java,
                report,
            ).filterNotNull()

    /** [FailOnRead] on `AuditTrail`. */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor") // Spring instantiates a configuration; an object it cannot.
    class Rig {
        companion object {
            @Bean
            @JvmStatic
            fun failingOnRead(): BeanPostProcessor = FailOnRead
        }
    }

    /**
     * When [armed], `AuditTrail.record` writes an `ENTRY_TEXT_READ` row and then throws: a read
     * whose transaction fails after the step-up committed (spec §11). The step-up's own row is
     * left alone, so the code is spent as it would be.
     */
    internal object FailOnRead : BeanPostProcessor {
        @Volatile var armed = false

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (bean is Advised && bean.targetSource.targetClass?.simpleName == "AuditTrail") {
                bean.addAdvice(
                    0,
                    MethodInterceptor { invocation ->
                        val answer = invocation.proceed()
                        if (armed && invocation.method.name == "record" && invocation.arguments[0] == AuditAction.ENTRY_TEXT_READ) {
                            error("forced after the read's audit row (test)")
                        }
                        answer
                    },
                )
            }
            return bean
        }
    }

    private companion object {
        const val ALLOWED = "10.9.1.1"
        const val STEP_SECONDS = 30L
        const val MAX_JUSTIFICATION = 400
        const val PLANTED = "<script>alert(1)</script><b>x</b>"
        const val ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;&lt;b&gt;x&lt;/b&gt;"
        const val BUTTON = "Read the entry's words"
        const val IMAGE_REASON = "This report's reason is about images. If its detail is about words, reclassify it first."
        const val MEMBER_REPORT = "This report is about a member, not an entry. There are no words to read."
        const val MODERATOR_HINT = "The entry's words are not shown here. An admin can read them, with a reason and a code."
        val WHY = "the reporter's detail is not enough ${UUID.randomUUID()}"
        val CSRF = Regex("""name="_csrf" value="([^"]+)"""")
    }
}
