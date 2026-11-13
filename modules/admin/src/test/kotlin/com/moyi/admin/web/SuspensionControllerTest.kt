package com.moyi.admin.web

import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.ScriptedAccountSuspension
import com.moyi.admin.infra.ScriptedLookups
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.admin.service.AdminConsoleServiceTest
import com.moyi.admin.service.AdminConsoleServiceTest.Companion.code
import com.moyi.admin.service.AdminEnrolment
import com.moyi.admin.service.AdminSignIn
import com.moyi.admin.service.EnrolmentResult
import com.moyi.admin.service.TotpStep
import com.moyi.bond.api.BondAdminView
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountStatus
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import com.moyi.identity.api.UserAdminView
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /admin/users/{userId}/suspend` and `/unsuspend`, and the lookup page's half of them (A3
 * spec §5; plan Task 7), through the admin chain with its CSRF check, against the real schema,
 * the real `SuspensionConsole` and `AuditTrail`, and identity's port scripted
 * ([ScriptedAccountSuspension]): the real port's tests are identity's, and the whole path is
 * `app`'s `SuspensionTest`.
 *
 * Each admin is enrolled for real, so its codes are what an authenticator app shows and
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
@Import(AdminConsoleServiceTest.TimeConfiguration::class)
internal class SuspensionControllerTest(
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
        ScriptedAccountSuspension.reset()
        ScriptedLookups.reset()
    }

    @AfterEach
    fun putBack() {
        clock.set(start)
        ScriptedAccountSuspension.reset()
        ScriptedLookups.reset()
    }

    // --- the outcomes ------------------------------------------------------------------------------------------------

    @Test
    fun `each outcome but NO_SUCH_ACCOUNT is a 303 to the lookup with its one line, audited, and no note anywhere`() {
        val due = Instant.parse("2026-12-01T10:15:30Z")
        val cases =
            listOf(
                Case("suspend", SuspensionOutcome.SUSPENDED, AccountStatus.ACTIVE, null, "SUCCEEDED", SUSPENDED),
                Case(
                    "suspend",
                    SuspensionOutcome.SUSPENDED,
                    AccountStatus.PENDING_VERIFICATION,
                    due,
                    "SUCCEEDED",
                    "$SUSPENDED $DELETION_AT$due$DELETION_TAIL",
                ),
                Case("suspend", SuspensionOutcome.ALREADY_SUSPENDED, AccountStatus.SUSPENDED, null, "REFUSED", ALREADY_SUSPENDED),
                Case("suspend", SuspensionOutcome.GONE, AccountStatus.DELETED, null, "REFUSED", GONE),
                Case("unsuspend", SuspensionOutcome.LIFTED, AccountStatus.SUSPENDED, null, "SUCCEEDED", LIFTED),
                Case("unsuspend", SuspensionOutcome.NOT_SUSPENDED, AccountStatus.ACTIVE, null, "REFUSED", NOT_SUSPENDED),
                Case("unsuspend", SuspensionOutcome.GONE, AccountStatus.DELETED, null, "REFUSED", GONE),
            )
        cases.forEach { case ->
            withClue("${case.route} ${case.outcome} ${case.statusBefore}") {
                val admin = enrolled("ADMIN")
                val user = UUID.randomUUID()
                val note = note()
                ScriptedAccountSuspension.answer = SuspensionResult(case.outcome, case.statusBefore, case.deletionDueAt)

                val posted = act(admin, case.route, user, nextCode(admin), note)

                posted.response.status shouldBe 303
                posted.response.redirectedUrl shouldBe "/admin/users?q=$user"
                posted.response.getHeader("Location") shouldBe "/admin/users?q=$user"
                posted.flashMap[Decisions.RESULT] shouldBe case.flash
                posted.flashMap.toString() shouldNotContain note
                posted.response.contentAsString shouldNotContain note
                val port = if (case.route == "suspend") "suspend" else "lift"
                ScriptedAccountSuspension.calls shouldContainExactly listOf("$port:$user")
                val action = if (case.route == "suspend") "ACCOUNT_SUSPENDED" else "ACCOUNT_UNSUSPENDED"
                rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED", "$action|${case.audited}")
                justificationOf(admin.id, action) shouldBe note

                // The page the redirect lands on: the flash, the query filled in, and nothing looked up.
                val followed = follow(admin, posted)
                followed.status shouldBe 200
                followed.page() shouldContain case.flash
                followed.page() shouldContain """value="$user""""
                followed.page() shouldNotContain note
                rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED", "$action|${case.audited}")
                ScriptedAccountSuspension.calls.clear()
            }
        }
    }

    @Test
    fun `NO_SUCH_ACCOUNT is the 404 page, not a redirect, its refusal audited without a target`() {
        val admin = enrolled("ADMIN")
        val user = UUID.randomUUID()
        ScriptedAccountSuspension.answer = SuspensionResult(SuspensionOutcome.NO_SUCH_ACCOUNT, null, null)

        val posted = act(admin, "suspend", user, nextCode(admin))

        posted.response.status shouldBe 404
        posted.response.redirectedUrl.shouldBeNull()
        posted.flashMap.isEmpty() shouldBe true
        posted.response.page() shouldContain "Error 404"
        rows(admin.id) shouldContainExactly listOf("ADMIN_STEP_UP|SUCCEEDED", "ACCOUNT_SUSPENDED|REFUSED")
    }

    // --- the order: id, note, code -----------------------------------------------------------------------------------

    @Test
    fun `a malformed id is the 404 page, and nothing is proved, acted on or audited`() {
        val admin = enrolled("ADMIN")
        val code = nextCode(admin)

        listOf("suspend", "unsuspend").forEach { route ->
            val posted = act(admin, route, "not-a-uuid", code)
            withClue(route) {
                posted.response.status shouldBe 404
                posted.response.redirectedUrl.shouldBeNull()
            }
        }

        rows(admin.id).shouldBeEmpty()
        ScriptedAccountSuspension.calls.shouldBeEmpty()
        signIn.stepUp(admin.id, code, null).shouldBeInstanceOf<TotpStep.Accepted>()
    }

    @Test
    fun `a refused justification is the form again, 422, its note kept, and no code is spent`() {
        val admin = enrolled("ADMIN")
        val user = UUID.randomUUID()
        val code = nextCode(admin)
        val before = lastStep(admin.id)

        listOf("", "   ", "x".repeat(MAX_JUSTIFICATION + 1), "why\u0000not").forEach { bad ->
            listOf("suspend", "unsuspend").forEach { route ->
                val posted = act(admin, route, user, code, justification = bad)
                withClue("$route ${bad.take(8)}") {
                    posted.response.status shouldBe 422
                    posted.modelAndView!!.viewName shouldBe "admin/decide"
                    // The refusal names the field as its label does, not A2's "note".
                    posted.response.page() shouldContain "Write a justification of 1 to $MAX_JUSTIFICATION characters saying why."
                    posted.response.page() shouldNotContain DecisionForm.NOTE_REFUSED
                    posted.response.page() shouldContain """name="justification""""
                    posted.response.page() shouldContain """name="code""""
                    posted.response.redirectedUrl.shouldBeNull()
                }
            }
        }
        val kept = "kept <b>${UUID.randomUUID()}</b> " + "y".repeat(MAX_JUSTIFICATION)
        val again = act(admin, "suspend", user, code, justification = kept)
        again.response.status shouldBe 422
        again.response.contentAsString shouldContain kept.replace("<", "&lt;").replace(">", "&gt;")

        lastStep(admin.id) shouldBe before
        rows(admin.id).shouldBeEmpty()
        ScriptedAccountSuspension.calls.shouldBeEmpty()
        // The code was never touched: the same one now proves, through the sign-in's own step-up.
        signIn.stepUp(admin.id, code, null).shouldBeInstanceOf<TotpStep.Accepted>()
    }

    @Test
    fun `a blank code with the session's window fresh is the code form with plan ruling 3's line, and nothing is acted on`() {
        val admin = enrolled("ADMIN", secondFactorAt = clock.instant())
        val user = UUID.randomUUID()
        val note = note()

        listOf("suspend", "unsuspend").forEach { route ->
            val posted = act(admin, route, user, "", note)
            withClue(route) {
                posted.response.status shouldBe 200
                posted.modelAndView!!.viewName shouldBe "admin/decide"
                posted.response.page() shouldContain CODE_NEEDED_FOR_ACT
                posted.response.page() shouldContain """name="code""""
                posted.response.page() shouldContain note
            }
        }

        rows(admin.id).shouldBeEmpty()
        ScriptedAccountSuspension.calls.shouldBeEmpty()
    }

    @Test
    fun `a wrong code and a replayed one are 401 with the form, and nothing is acted on`() {
        val admin = enrolled("ADMIN")
        val user = UUID.randomUUID()
        val code = nextCode(admin)
        act(admin, "suspend", user, code).response.status shouldBe 303
        ScriptedAccountSuspension.calls.clear()

        val replayed = act(admin, "unsuspend", user, code)
        val wrong = act(admin, "suspend", user, wrongCode(admin))

        listOf(replayed, wrong).forEach { posted ->
            posted.response.status shouldBe 401
            posted.modelAndView!!.viewName shouldBe "admin/decide"
            posted.response.page() shouldContain AdminAuthController.WRONG_CODE
            posted.response.page() shouldContain """name="code""""
        }
        ScriptedAccountSuspension.calls.shouldBeEmpty()
        rows(admin.id) shouldContainExactly
            listOf("ADMIN_STEP_UP|SUCCEEDED", "ACCOUNT_SUSPENDED|SUCCEEDED", "ADMIN_STEP_UP|REFUSED", "ADMIN_STEP_UP|REFUSED")
    }

    // --- the role ----------------------------------------------------------------------------------------------------

    @Test
    fun `a MODERATOR's and SUPPORT's hand-made POST is the 403 page, acts on nothing and spends no code`() {
        listOf("MODERATOR", "SUPPORT").forEach { role ->
            val admin = enrolled(role)
            val code = nextCode(admin)
            listOf("suspend", "unsuspend").forEach { route ->
                val posted = act(admin, route, UUID.randomUUID(), code)
                withClue("$role $route") {
                    posted.response.status shouldBe 403
                    posted.response.page() shouldContain "Error 403"
                }
            }
            withClue(role) {
                rows(admin.id).shouldBeEmpty()
                ScriptedAccountSuspension.calls.shouldBeEmpty()
                signIn.stepUp(admin.id, code, null).shouldBeInstanceOf<TotpStep.Accepted>()
            }
        }
    }

    // --- the lookup page ---------------------------------------------------------------------------------------------

    @Test
    fun `the lookup shows Status and paused to every role, and the suspend form to an ADMIN alone`() {
        val active = known(AccountStatus.ACTIVE, deletionDueAt = Instant.parse("2026-12-24T08:00:00Z"))
        ScriptedLookups.bonds[active] =
            listOf(
                BondAdminView(UUID.randomUUID(), "ACTIVE", Instant.parse("2026-10-01T00:00:00Z"), null, UUID.randomUUID(), paused = true),
            )
        val admin = enrolled("ADMIN")

        val forAdmin = lookup(admin, active)
        forAdmin.status shouldBe 200
        forAdmin.page() shouldContain "ACTIVE"
        forAdmin.page() shouldContain "paused"
        forAdmin.page() shouldContain """action="/admin/users/$active/suspend""""
        forAdmin.page() shouldContain "Suspend this account"
        forAdmin.page() shouldNotContain "/unsuspend"
        forAdmin.page() shouldContain """name="justification""""
        forAdmin.page() shouldContain """name="code""""
        forAdmin.page() shouldContain "A deletion this person asked for runs on 2026-12-24T08:00:00Z, suspended or not."
        forAdmin.page() shouldNotContain ONLY_ADMIN

        listOf("MODERATOR", "SUPPORT").forEach { role ->
            val page = lookup(enrolled(role), active)
            withClue(role) {
                page.status shouldBe 200
                page.page() shouldContain "ACTIVE"
                page.page() shouldContain "paused"
                page.page() shouldContain ONLY_ADMIN
                page.page() shouldNotContain "/suspend"
                page.page() shouldNotContain "Suspend this account"
                page.page() shouldNotContain """name="code""""
            }
        }
    }

    @Test
    fun `a suspended account shows since when, the lift form to an ADMIN, and no paused on a bond that is not`() {
        val since = Instant.parse("2026-11-02T09:30:00Z")
        val suspended = known(AccountStatus.SUSPENDED, suspendedSince = since)
        ScriptedLookups.bonds[suspended] =
            listOf(BondAdminView(UUID.randomUUID(), "ARCHIVED", since, since, UUID.randomUUID(), paused = false))

        val forAdmin = lookup(enrolled("ADMIN"), suspended)
        forAdmin.page() shouldContain "SUSPENDED since $since"
        forAdmin.page() shouldContain """action="/admin/users/$suspended/unsuspend""""
        forAdmin.page() shouldContain "Lift the suspension"
        forAdmin.page() shouldNotContain "Suspend this account"
        forAdmin.page() shouldNotContain "paused"
        forAdmin.page() shouldNotContain "A deletion this person asked for"

        val forSupport = lookup(enrolled("SUPPORT"), suspended)
        forSupport.page() shouldContain "SUSPENDED since $since"
        forSupport.page() shouldContain ONLY_ADMIN
        forSupport.page() shouldNotContain "Lift the suspension"

        // A deleted account: its status, and nothing an ADMIN can do to it.
        val deleted = known(AccountStatus.DELETED)
        val gone = lookup(enrolled("ADMIN"), deleted)
        gone.page() shouldContain "DELETED"
        gone.page() shouldNotContain "Suspend this account"
        gone.page() shouldNotContain "Lift the suspension"
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private class Case(
        val route: String,
        val outcome: SuspensionOutcome,
        val statusBefore: AccountStatus,
        val deletionDueAt: Instant?,
        val audited: String,
        val flash: String,
    )

    /** An enrolled admin: its id, its TOTP secret, and a two-factor session as the sign-in leaves it. */
    private class Admin(
        val id: UUID,
        val secret: ByteArray,
        val session: MockHttpSession,
    )

    private fun enrolled(
        role: String,
        secondFactorAt: Instant? = null,
    ): Admin {
        val email = "suspend-${UUID.randomUUID().toString().take(8)}@example.com"
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

    private fun wrongCode(admin: Admin): String {
        val valid = (-1L..1L).map { code(admin.secret, clock.instant().plusSeconds(it * STEP_SECONDS)) }.toSet()
        return generateSequence(0) { it + 1 }.map { "%06d".format(it) }.first { it !in valid }
    }

    /** An account the lookup's stand-in knows, with [status]; its id. */
    private fun known(
        status: AccountStatus,
        suspendedSince: Instant? = null,
        deletionDueAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        ScriptedLookups.users[id] =
            UserAdminView(
                id = id,
                email = "member-$id@example.com",
                displayName = "Member",
                locale = "en",
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                emailVerified = true,
                lockedUntil = null,
                liveSessions = 0,
                tokensInvalidBefore = null,
                status = status,
                suspendedSince = suspendedSince,
                deletionDueAt = deletionDueAt,
            )
        return id
    }

    private fun lookup(
        admin: Admin,
        user: UUID,
    ): MockHttpServletResponse = post(admin.session, "/admin/users", mapOf("q" to user.toString(), "purpose" to "MEMBER_REQUEST")).response

    @Suppress("LongParameterList") // The form's two fields, the route and its id, and whose session posts them.
    private fun act(
        admin: Admin,
        route: String,
        user: Any,
        code: String,
        justification: String = note(),
    ): MvcResult = post(admin.session, "/admin/users/$user/$route", mapOf("justification" to justification, "code" to code))

    private fun follow(
        admin: Admin,
        posted: MvcResult,
    ): MockHttpServletResponse =
        mockMvc
            .get(posted.response.redirectedUrl!!) {
                with { it.apply { remoteAddr = ALLOWED } }
                session = admin.session
                flashAttrs = posted.flashMap
            }.andReturn()
            .response

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

    /** The body as a reader sees it: Thymeleaf writes an apostrophe it prints as `&#39;`. */
    private fun MockHttpServletResponse.page(): String = contentAsString.replace("&#39;", "'")

    private fun note(): String = "a report names this account ${UUID.randomUUID()}"

    private fun lastStep(id: UUID): Long? =
        jdbc.queryForObject("SELECT totp_last_step FROM admin_accounts WHERE id = ?", Long::class.javaObjectType, id)

    /** [id]'s step-up and suspension rows, `action|outcome`, in order. */
    private fun rows(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || outcome FROM audit_log WHERE actor_id = ? " +
                    "AND action IN ('ADMIN_STEP_UP', 'ACCOUNT_SUSPENDED', 'ACCOUNT_UNSUSPENDED') ORDER BY id",
                String::class.java,
                id,
            ).filterNotNull()

    private fun justificationOf(
        id: UUID,
        action: String,
    ): String? =
        jdbc.queryForObject(
            "SELECT justification FROM audit_log WHERE actor_id = ? AND action = ?",
            String::class.java,
            id,
            action,
        )

    private companion object {
        const val ALLOWED = "10.9.1.1"
        const val STEP_SECONDS = 30L
        const val MAX_JUSTIFICATION = 400
        const val SUSPENDED = "Suspended. They are signed out everywhere and cannot sign in. Their bonds are paused."
        const val LIFTED = "Suspension lifted. They can sign in again; their bonds take entries again now."
        const val NOT_SUSPENDED = "This account is not suspended. Nothing changed."
        const val ALREADY_SUSPENDED = "Already suspended. Nothing changed."
        const val GONE = "This account was deleted. There is nothing to suspend."
        const val DELETION_AT = "A deletion this person asked for runs on "
        const val DELETION_TAIL = ", suspended or not."
        const val ONLY_ADMIN = "Only an ADMIN can suspend an account."
        const val CODE_NEEDED_FOR_ACT = "Enter a code from your authenticator app. Each suspension, and each lift, needs a new one."
        val CSRF = Regex("""name="_csrf" value="([^"]+)"""")
    }
}
