package com.moyi.app.admin

import com.moyi.app.admin.AdminConsoleDriver.Companion.code
import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.bond.api.ReminderRoster
import com.moyi.common.testing.MutableClock
import com.moyi.export.api.DataExportJobs
import com.moyi.gratitude.api.DailyReminders
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.random.Random

/**
 * A suspension and its lift, the whole path (A3 spec §5, §8, §11; plan Task 7): an `ADMIN` signed
 * in for real acts through the lookup page with a code an authenticator app shows; the members
 * meet it through the API, with every real port between them. Nothing is seeded but the members'
 * verified addresses.
 *
 * [Hold] stops `AuditTrail.record` of an `ACCOUNT_SUSPENDED` row inside the suspension's
 * transaction (after identity's port has done its writes, before the commit), or throws there:
 * the races are synchronised on that stop and on `pg_locks`, each wait with a deadline that
 * fails the test, never a sleep.
 *
 * Every sign-in after an act first moves the clock past the act's next whole second (the
 * `tokens_invalid_before` bump), and every new code one TOTP step on. The clock is put back after
 * each test.
 */
@Import(SuspensionTest.Rig::class)
@Suppress("LargeClass") // The whole path, its races and its atomicity: one rig, one context.
class SuspensionTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val jobs: DataExportJobs,
    @Autowired private val reminders: DailyReminders,
    @Autowired private val roster: ReminderRoster,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val console = AdminConsoleDriver(mockMvc, jdbc)
    private val rig = ModerationRig(mockMvc, jdbc, clock)
    private val json = JsonMapper.builder().build()
    private lateinit var start: Instant

    @BeforeEach
    fun remember() {
        start = clock.instant()
        Hold.reset()
    }

    @AfterEach
    fun putBack() {
        Hold.reset()
        clock.set(start)
    }

    // --- the whole path ----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // One couple, suspended and lifted through the console, and every member-facing answer between.
    fun `an admin suspends a member through the page, the member is locked out and the bond paused, and the lift undoes both`() {
        val ada = member("ada")
        val bea = member("bea")
        val bond = bond(ada, bea)
        val cy = member("cy")
        val dee = member("dee")
        val control = bond(cy, dee)
        val window = reminderWindow(listOf(bond to listOf(ada, bea), control to listOf(cy, dee)))
        val admin = admin()
        val wrongPassword = login(ada, "not the password")
        wrongPassword.status shouldBe 401
        // One disclosed lookup, so that the exports compared below are of a list and not of two empty ones;
        // every later lookup is for SAFETY, which the export withholds.
        lookup(admin, ada.id, "MEMBER_REQUEST").status shouldBe 200
        val exportBefore = ExportArchives.build(jobs, jdbc, clock, ada.id)

        // The lookup offers the act, and the act is posted where the form says.
        val page = lookup(admin, ada.id)
        page.contentAsString shouldContain "Suspend this account"
        page.contentAsString shouldContain """action="/admin/users/${ada.id}/suspend""""
        val suspended = act(admin, "suspend", ada.id)
        suspended.response.status shouldBe 303
        suspended.response.redirectedUrl shouldBe "/admin/users?q=${ada.id}"
        suspended.result() shouldBe SUSPENDED
        rig.follow(admin.session, suspended).contentAsString shouldContain SUSPENDED
        clock.advance(Duration.ofSeconds(2))

        // The member: every token dead, and a sign-in refused with a wrong password's very words.
        api("GET", "/me", ada.access).status shouldBe 401
        refresh(ada.refresh).status shouldBe 401
        val signIn = login(ada, PASSWORD)
        signIn.status shouldBe 401
        comparable(signIn.contentAsString) shouldBe comparable(wrongPassword.contentAsString)

        // The partner: the bond paused, everything in it still readable, nothing new written.
        val refused = write(bea, bond)
        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
        body(api("GET", "/bonds/$bond", bea.access))["paused"].asBoolean() shouldBe true
        body(api("GET", "/bonds/$bond/today", bea.access))["paused"].asBoolean() shouldBe true
        api("GET", "/bonds/$bond/days", bea.access).status shouldBe 200
        api("POST", "/me/export", bea.access).status shouldBe 202
        api("GET", "/me/export", bea.access).status shouldBe 200
        // And the export asked for while paused is built, whole: her account, the paused bond, the admin section.
        val partnerExport = json.readTree(ExportArchives.buildRequested(jobs, jdbc, bea.id))
        partnerExport["account"]["id"].asString() shouldBe bea.id.toString()
        partnerExport["bonds"].toList().map { it["bond"]["id"].asString() } shouldContainExactly listOf(bond)
        partnerExport["bonds"][0]["days"].isArray shouldBe true
        partnerExport["adminAccess"].isObject shouldBe true

        // No reminder for either member of the paused bond at their reminder time; the control couple's comes.
        reminders.remindDue(window.first, window.second)
        remindedAmong(listOf(ada.id, bea.id)).shouldBeEmpty()
        remindedAmong(listOf(cy.id, dee.id)) shouldContainExactlyInAnyOrder listOf(cy.id, dee.id)

        // The lift, from the page, with the next code.
        lookup(admin, ada.id).contentAsString shouldContain """action="/admin/users/${ada.id}/unsuspend""""
        val lifted = act(admin, "unsuspend", ada.id)
        lifted.response.status shouldBe 303
        lifted.result() shouldBe LIFTED
        clock.advance(Duration.ofSeconds(2))

        login(ada, PASSWORD).status shouldBe 200
        write(bea, bond).status shouldBe 201
        body(api("GET", "/bonds/$bond", bea.access))["paused"].asBoolean() shouldBe false

        val rows = rig.audited(admin.id).filter { it.head.startsWith("ACCOUNT_") }
        rows.map { "${it.head} ${it.metadata}" } shouldContainExactly
            listOf(
                """ACCOUNT_SUSPENDED|SUCCEEDED|${ada.id} {"result": "SUSPENDED", "statusBefore": "ACTIVE", "deletionPending": false}""",
                """ACCOUNT_UNSUSPENDED|SUCCEEDED|${ada.id} {"result": "LIFTED", "statusBefore": "SUSPENDED", "deletionPending": false}""",
            )
        rows.forEach { it.justification shouldBe NOTE }
        acts(admin) shouldContainExactly
            listOf(
                "USER_LOOKED_UP",
                "USER_LOOKED_UP",
                "ADMIN_STEP_UP",
                "ACCOUNT_SUSPENDED",
                "USER_LOOKED_UP",
                "ADMIN_STEP_UP",
                "ACCOUNT_UNSUSPENDED",
            )

        // Spec §10: the suspended member's export, built after both acts, discloses exactly what one built before did.
        val exportAfter = ExportArchives.build(jobs, jdbc, clock, ada.id)
        json.readTree(exportAfter)["adminAccess"]["accesses"].toList().map { it["action"].asString() } shouldContainExactly
            listOf("ACCOUNT_LOOKED_UP")
        ExportArchives.adminAccessBytes(exportAfter) shouldBe ExportArchives.adminAccessBytes(exportBefore)
        String(exportAfter) shouldNotContain NOTE
    }

    // --- atomicity ---------------------------------------------------------------------------------------------------

    @Test
    fun `a suspension that fails after its audit row leaves the account active, no span open and no row, and the code spent`() {
        val ada = member("ada")
        val admin = admin()

        Hold.failing = true
        val failed = act(admin, "suspend", ada.id)
        Hold.failing = false

        failed.response.status shouldBe 500
        failed.response.contentAsString shouldNotContain NOTE
        Hold.failed shouldBe true
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, ada.id) shouldBe "ACTIVE"
        openSpans(ada.id) shouldBe 0
        acts(admin) shouldContainExactly listOf("ADMIN_STEP_UP")
        clock.advance(Duration.ofSeconds(2))
        api("GET", "/me", ada.access).status shouldBe 200
        // The rolled-back revocation left her sessions as they were: the refresh token still rotates.
        refresh(ada.refresh).status shouldBe 200
    }

    // --- races -------------------------------------------------------------------------------------------------------

    @Test
    fun `the partner's submit lands while the suspension is uncommitted, and the next one after its commit is refused`() {
        val ada = member("ada")
        val bea = member("bea")
        val bond = bond(ada, bea)
        val admin = admin()
        val code = nextCode(admin)

        val stop = Hold.arm()
        val acting = CompletableFuture.supplyAsync { act(admin, "suspend", ada.id, code) }
        try {
            stop.awaitReached()
            // Identity's writes are done and uncommitted; the span is not visible to the partner's read.
            val during = CompletableFuture.supplyAsync { write(bea, bond) }.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            during.status shouldBe 201
        } finally {
            stop.release()
        }
        acting.get(DEADLINE_SECONDS, TimeUnit.SECONDS).result() shouldBe SUSPENDED

        val after = write(bea, bond)
        after.status shouldBe 409
        after.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
    }

    @Test
    fun `two admins suspending one account at once get one Suspended and one Already suspended, audited so`() {
        val ada = member("ada")
        val first = admin()
        val second = admin()
        val firstCode = nextCode(first)
        val secondCode = code(second.secret, clock.instant().plusSeconds(STEP_SECONDS))

        val stop = Hold.arm()
        val one = CompletableFuture.supplyAsync { act(first, "suspend", ada.id, firstCode) }
        val two: CompletableFuture<MvcResult>
        try {
            stop.awaitReached()
            two = CompletableFuture.supplyAsync { act(second, "suspend", ada.id, secondCode) }
            awaitSessionsLockWaiter(ada.id)
        } finally {
            stop.release()
        }

        listOf(one, two).map { it.get(DEADLINE_SECONDS, TimeUnit.SECONDS).result() } shouldContainExactly
            listOf(SUSPENDED, "Already suspended. Nothing changed.")
        val rows = (rig.audited(first.id) + rig.audited(second.id)).filter { it.head.startsWith("ACCOUNT_") }
        rows.map { it.head } shouldContainExactly
            listOf("ACCOUNT_SUSPENDED|SUCCEEDED|${ada.id}", "ACCOUNT_SUSPENDED|REFUSED|${ada.id}")
        rows.map { it.metadata } shouldContainExactly
            listOf(
                """{"result": "SUSPENDED", "statusBefore": "ACTIVE", "deletionPending": false}""",
                """{"result": "ALREADY_SUSPENDED", "statusBefore": "SUSPENDED", "deletionPending": false}""",
            )
        openSpans(ada.id) shouldBe 1
    }

    // --- the console -------------------------------------------------------------------------------------------------

    private class Admin(
        val id: UUID,
        val secret: ByteArray,
        val session: MockHttpSession,
    )

    /** An `ADMIN` enrolled and signed in through the console's own pages, as an operator does. */
    private fun admin(): Admin {
        val email = "suspender-${UUID.randomUUID().toString().take(8)}@example.com"
        val secret = console.enrol(email, ADMIN_PASSWORD, clock.instant(), role = "ADMIN")
        val session = MockHttpSession()
        console.password(session, email, ADMIN_PASSWORD).status shouldBe 302
        console.code(session, code(secret, clock.instant().plusSeconds(STEP_SECONDS))).status shouldBe 302
        return Admin(console.idOf(email), secret, session)
    }

    /** A code not yet spent: the clock moved one step, and the code of the step after it (the sign-in spent the one before). */
    private fun nextCode(admin: Admin): String {
        clock.advance(Duration.ofSeconds(STEP_SECONDS))
        return code(admin.secret, clock.instant().plusSeconds(STEP_SECONDS))
    }

    /** The lookup page for [user], for [purpose]; `SAFETY` with a note, so that the member's export does not list it. */
    private fun lookup(
        admin: Admin,
        user: UUID,
        purpose: String = "SAFETY",
    ): MockHttpServletResponse =
        rig
            .post(
                admin.session,
                "/admin/users",
                mapOf("q" to user.toString(), "purpose" to purpose) + (if (purpose == "SAFETY") mapOf("note" to NOTE) else emptyMap()),
            ).response

    private fun act(
        admin: Admin,
        route: String,
        user: UUID,
        code: String = nextCode(admin),
    ): MvcResult = rig.post(admin.session, "/admin/users/$user/$route", mapOf("justification" to NOTE, "code" to code))

    /** [admin]'s audit rows' actions, in order, after the enrolment and the sign-in. */
    private fun acts(admin: Admin): List<String> =
        rig.audited(admin.id).map { it.head.substringBefore('|') }.filterNot { it == "ADMIN_ENROLLED" || it == "ADMIN_SIGN_IN" }

    // --- the members -------------------------------------------------------------------------------------------------

    private class Member(
        val id: UUID,
        val email: String,
        val address: String,
        val access: String,
        val refresh: String,
    )

    /** Registered through the API from an address of its own, made verified and `ACTIVE`, and signed in. */
    private fun member(name: String): Member {
        val email = "$name-${UUID.randomUUID().toString().take(8)}@example.com"
        val address = "192.0.2.${Random.nextInt(1, 255)}"
        mockMvc
            .post("/api/v1/auth/register") {
                header("X-Forwarded-For", address)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"${name.take(3)}","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        jdbc.update("UPDATE users SET email_verified_at = now(), status = 'ACTIVE' WHERE email = ?::citext", email) shouldBe 1
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID::class.java, email)!!
        val signedIn = mockMvc.post("/api/v1/auth/login") { loginBody(address, email, PASSWORD) }.andReturn().response
        signedIn.status shouldBe 200
        val tokens = body(signedIn)
        return Member(id, email, address, tokens["accessToken"].asString(), tokens["refreshToken"].asString())
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.loginBody(
        address: String,
        email: String,
        password: String,
    ) {
        header("X-Forwarded-For", address)
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"$password"}"""
    }

    private fun login(
        member: Member,
        password: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/auth/login") { loginBody(member.address, member.email, password) }.andReturn().response

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$token"}"""
            }.andReturn()
            .response

    /** A bond of [creator]'s that [partner] joined; its id. */
    private fun bond(
        creator: Member,
        partner: Member,
    ): String {
        val created = api("POST", "/bonds", creator.access, """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}""")
        created.status shouldBe 201
        val code = body(created)["invite"]["code"].asString()
        api("POST", "/invites/$code/accept", partner.access).status shouldBe 200
        return body(created)["id"].asString()
    }

    private fun write(
        author: Member,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${author.access}")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you for today"}"""
            }.andReturn()
            .response

    private fun api(
        method: String,
        path: String,
        token: String,
        json: String? = null,
    ): MockHttpServletResponse {
        val url = "/api/v1$path"
        val build: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            if (json != null) {
                contentType = MediaType.APPLICATION_JSON
                content = json
            }
        }
        return when (method) {
            "GET" -> mockMvc.get(url, dsl = build)
            "POST" -> mockMvc.post(url, dsl = build)
            "PUT" -> mockMvc.put(url, dsl = build)
            else -> error("no $method")
        }.andReturn().response
    }

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsByteArray)

    /** A 401's body, apart from what differs between any two requests: the trace id and the path typed. */
    private fun comparable(body: String): String = withoutTraceId(body).replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    /**
     * Sets every member's reminder, in each of [bonds], to a whole minute at least a minute from
     * now at which **nobody else's** reminder falls, in `Africa/Abidjan` (UTC all year, a region
     * zone as the API requires), and answers the one-minute window `(at - 1 min, at]` a sweep finds
     * it in. The database is shared by every test class, and `remindDue` sweeps the whole roster:
     * a minute no other member is due in means the sweep reminds this test's members and no one
     * else's, and the roster is asked to prove it before anything is sent.
     */
    private fun reminderWindow(bonds: List<Pair<String, List<Member>>>): Pair<Instant, Instant> {
        val now = clock.instant()
        val first = now.plus(Duration.ofMinutes(2)).truncatedTo(ChronoUnit.MINUTES)
        val at =
            generateSequence(first) { it.plus(Duration.ofMinutes(1)) }
                .take(MINUTES_IN_A_DAY)
                .firstOrNull { roster.remindersDueBetween(it.minus(Duration.ofMinutes(1)), it).isEmpty() }
                ?: error("every minute of the day has somebody's reminder in it")
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(at)
        bonds.forEach { (bond, members) ->
            members.forEach { member ->
                api(
                    "PUT",
                    "/bonds/$bond/members/me/settings",
                    member.access,
                    """{"reminderTimeLocal":"$time","reminderTimezone":"Africa/Abidjan"}""",
                ).status shouldBe 200
            }
        }
        val window = at.minus(Duration.ofMinutes(1)) to at
        roster.remindersDueBetween(window.first, window.second).map { it.userId } shouldContainExactlyInAnyOrder
            bonds.flatMap { (_, members) -> members.map { it.id } }
        return window
    }

    private fun remindedAmong(users: List<UUID>): List<UUID> =
        jdbc
            .queryForList("SELECT user_id FROM notifications WHERE category = 'REMINDER'", UUID::class.java)
            .filterNotNull()
            .filter { it in users }

    private fun openSpans(user: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM account_suspensions WHERE user_id = ? AND lifted_at IS NULL", Int::class.java, user)!!

    /**
     * Until a backend waits for [userId]'s sessions advisory lock, `(1, hashtext(id))` as identity's
     * `RefreshTokenRepository.lockSessions` takes it (the formula of identity's `ConcurrencyTesting`,
     * which this module cannot import), or the deadline fails the test.
     */
    private fun awaitSessionsLockWaiter(userId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = 1 " +
                        "AND objsubid = 2 AND objid::bigint = (hashtext(CAST(? AS text))::bigint & 4294967295)",
                    Int::class.java,
                    userId,
                )!!
            if (waiting >= 1) return
            Thread.onSpinWait()
        }
        error("the second suspension was not seen waiting on the member's sessions lock within $DEADLINE_SECONDS s")
    }

    // --- the stop inside the suspension's transaction ----------------------------------------------------------------

    /** [Hold] on `AuditTrail`. */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor") // Spring instantiates a configuration; an object it cannot.
    class Rig {
        companion object {
            @Bean
            @JvmStatic
            fun holdingTheSuspension(): BeanPostProcessor = Hold
        }
    }

    /**
     * Around `AuditTrail.record` of an `ACCOUNT_SUSPENDED` row, in the suspension's transaction
     * after identity's port has written: when [arm]ed, the first such call writes its row, says it
     * has arrived and waits to be released; when [failing], it writes its row and throws. Every
     * other row passes untouched. Both waits have a deadline that fails.
     */
    internal object Hold : BeanPostProcessor {
        private val armed = AtomicReference<Stop?>(null)

        @Volatile var failing = false

        @Volatile var failed = false

        fun arm(): Stop = Stop().also { armed.set(it) }

        fun reset() {
            armed.getAndSet(null)?.release()
            failing = false
            failed = false
        }

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (bean is Advised && bean.targetSource.targetClass?.simpleName == "AuditTrail") {
                bean.addAdvice(
                    0,
                    MethodInterceptor { invocation ->
                        val answer = invocation.proceed()
                        if (invocation.method.name == "record" && invocation.arguments[0].toString() == "ACCOUNT_SUSPENDED") {
                            if (failing) {
                                failed = true
                                error("forced after the suspension's audit row (test)")
                            }
                            armed.getAndSet(null)?.let { stop ->
                                stop.reached.countDown()
                                check(stop.released.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "the hold was never released" }
                            }
                        }
                        answer
                    },
                )
            }
            return bean
        }

        class Stop {
            internal val reached = CountDownLatch(1)
            internal val released = CountDownLatch(1)

            fun awaitReached() = check(reached.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "no suspension reached the hold" }

            fun release() = released.countDown()
        }
    }

    private companion object {
        const val PASSWORD = "eleven~amber~lanterns~at~dusk"
        const val ADMIN_PASSWORD = "nineteen silver kettles hum"
        const val STEP_SECONDS = 30L
        const val DEADLINE_SECONDS = 10L
        const val MINUTES_IN_A_DAY = 1440
        const val SUSPENDED = "Suspended. They are signed out everywhere and cannot sign in. Their bonds are paused."
        const val LIFTED = "Suspension lifted. They can sign in again; their bonds take entries again now."
        val NOTE = "a report names this account ${UUID.randomUUID()}"
    }
}
