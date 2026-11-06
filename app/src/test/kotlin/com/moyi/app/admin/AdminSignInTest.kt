package com.moyi.app.admin

import com.moyi.app.admin.AdminConsoleDriver.Companion.code
import com.moyi.app.admin.AdminConsoleDriver.Companion.csrfFrom
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The two sign-in steps and sign-out (spec §6, §8): password, then a code, each changing the
 * session id; a code's step spent once, even by two requests at the same instant; five
 * failures lock the account for fifteen minutes behind the same words as a wrong password;
 * every attempt audited and counted, with nothing typed reaching a row or a log line.
 */
class AdminSignInTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val dataSource: DataSource,
    @Autowired private val meters: MeterRegistry,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val console = AdminConsoleDriver(mockMvc, jdbc)

    @Test
    fun `password, then code, then the console, with a new session id at each step`() {
        val (email, secret) = enrolled()
        val session = MockHttpSession()
        console.loginPage(session)
        val first = session.id

        val password = console.password(session, email, PASSWORD)
        password.status shouldBe 302
        password.redirectedUrl!! shouldEndWith "/admin/login/totp"
        val second = session.id
        second shouldNotBe first

        val code = console.code(session, nextCode(secret))
        code.status shouldBe 302
        code.redirectedUrl!! shouldEndWith "/admin"
        session.id shouldNotBe second

        val home = console.home(session)
        home.status shouldBe 200
        home.contentAsString shouldContain "Signed in"
    }

    @Test
    fun `a code already used is refused, even on a new session`() {
        val (email, secret) = enrolled()
        val code = nextCode(secret)
        signIn(email, code).status shouldBe 200

        val again = MockHttpSession()
        console.password(again, email, PASSWORD).status shouldBe 302
        val refused = console.code(again, code)
        refused.status shouldBe 401
        refused.contentAsString shouldContain CODE_REFUSED
        console.home(again).redirectedUrl!!.substringBefore('?') shouldEndWith "/admin/login/totp"
    }

    @Test
    fun `five wrong passwords lock the account, the right one is then refused alike, and fifteen minutes free it`() {
        val (email, _) = enrolled()
        repeat(5) {
            val wrong = console.password(MockHttpSession(), email, "not the password, attempt $it")
            wrong.status shouldBe 401
            wrong.contentAsString shouldContain LOGIN_REFUSED
        }

        val locked = console.password(MockHttpSession(), email, PASSWORD)
        locked.status shouldBe 401
        locked.contentAsString shouldContain LOGIN_REFUSED

        val start = clock.instant()
        try {
            clock.advance(Duration.ofMinutes(15).plusSeconds(1))
            console.password(MockHttpSession(), email, PASSWORD).status shouldBe 302
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `an unknown email is refused, audited with no actor, and its row holds no email`() {
        val email = "nobody-${UUID.randomUUID().toString().take(8)}@example.com"
        val before = signInRowIds()

        val refused = console.password(MockHttpSession(), email, "whatever it was they typed")
        refused.status shouldBe 401
        refused.contentAsString shouldContain LOGIN_REFUSED

        val rows =
            jdbc
                .queryForList(
                    "SELECT id, actor_id, outcome, metadata::text AS metadata, ip_hash FROM audit_log WHERE action = 'ADMIN_SIGN_IN'",
                ).filter { it["id"] !in before }
        rows shouldHaveSize 1
        val row = rows.single()
        row["outcome"] shouldBe "REFUSED"
        row["actor_id"].shouldBeNull()
        (row["ip_hash"] as ByteArray?) shouldNotBe null
        (row["metadata"] as String) shouldNotContain email
        (row["metadata"] as String) shouldNotContain "whatever"
    }

    @Test
    fun `two requests with one valid code at the same moment, and exactly one is accepted`() {
        val (email, secret) = enrolled()
        val id = console.idOf(email)
        val sessions = List(2) { MockHttpSession().also { console.password(it, email, PASSWORD).status shouldBe 302 } }
        val csrf = sessions.map { csrfFrom(console.totpPage(it)) }
        val code = nextCode(secret)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val answers =
            try {
                whileRowIsHeld(id) { holderPid, release ->
                    val futures =
                        sessions.indices.map { i ->
                            pool.submit<MockHttpServletResponse> {
                                start.await()
                                console.code(sessions[i], code, csrf[i])
                            }
                        }
                    start.countDown()
                    // Both are blocked on the row, which is to say both are at the CAS.
                    await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { blockedBy(holderPid) == 2 }
                    release()
                    futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                }
            } finally {
                pool.shutdownNow()
            }

        answers.map { it.status } shouldContainExactlyInAnyOrder listOf(302, 401)
    }

    @Test
    fun `disabling the account ends its signed-in session on the next request`() {
        val (email, secret) = enrolled()
        val session = signedIn(email, nextCode(secret))
        console.home(session).status shouldBe 200

        jdbc.queryForObject("SELECT admin_disable(?::citext)::text", String::class.java, email)

        console.home(session).redirectedUrl!! shouldEndWith "/admin/login"
        session.isInvalid shouldBe true
    }

    @Test
    fun `sign-out ends the session and is audited`() {
        val (email, secret) = enrolled()
        val session = signedIn(email, nextCode(secret))
        val id = console.idOf(email)

        val out = console.logout(session)

        out.status shouldBe 302
        out.redirectedUrl!!.substringBefore('?') shouldEndWith "/admin/login"
        session.isInvalid shouldBe true
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'ADMIN_SIGN_OUT' AND outcome = 'SUCCEEDED' AND actor_id = ?",
            Int::class.java,
            id,
        ) shouldBe 1
    }

    @Test
    fun `every sign-in row moves the sign-in counter, by its outcome`() {
        val (email, secret) = enrolled()
        val succeeded = counted("succeeded")
        val refused = counted("refused")

        console.password(MockHttpSession(), email, "not the password at all").status shouldBe 401
        signIn(email, nextCode(secret)).status shouldBe 200

        counted("refused") - refused shouldBe 1.0
        counted("succeeded") - succeeded shouldBe 1.0
    }

    @Test
    fun `a POST without a CSRF token gets the admin error page, not a bare status or JSON`() {
        val (email, _) = enrolled()
        val response =
            mockMvc
                .post("/admin/login") {
                    with { it.apply { remoteAddr = ALLOWED } }
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    param("email", email)
                    param("password", PASSWORD)
                }.andReturn()
                .response
        response.status shouldBe 403
        response.contentType!! shouldContain MediaType.TEXT_HTML_VALUE
        response.contentAsString shouldContain "Error 403"
        response.contentAsString shouldNotContain "Exception"
        response.getHeader("Content-Security-Policy") shouldNotBe null
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `no log line carries the password, a code, the token or the secret`(output: CapturedOutput) {
        val email = "logs-${UUID.randomUUID().toString().take(8)}@example.com"
        val token = console.invite(email)
        val session = MockHttpSession()
        val page = console.enrolPage(token, session)
        val secret = console.secretOf(email)
        val key = Regex("""<code id="secret">([A-Z2-7]+)</code>""").find(page.contentAsString)!!.groupValues[1]
        val enrolCode = code(secret, clock.instant())
        console.submitEnrolment(session, token, PASSWORD, code = enrolCode).status shouldBe 302
        console.password(MockHttpSession(), email, "a wrong password for the log").status shouldBe 401
        val signInCode = nextCode(secret)
        signIn(email, signInCode).status shouldBe 200

        listOf(PASSWORD, "a wrong password for the log", token, key, enrolCode, signInCode).forEach { secretText ->
            withClue("captured output") { output.all shouldNotContain secretText }
        }
    }

    /** An enrolled, active account; its code step is now's, so a sign-in uses the next one. */
    private fun enrolled(): Pair<String, ByteArray> {
        val email = "signin-${UUID.randomUUID().toString().take(8)}@example.com"
        return email to console.enrol(email, PASSWORD, clock.instant())
    }

    /** A code for the step after now's: inside the ±1 window, and later than enrolment's step. */
    private fun nextCode(secret: ByteArray): String = code(secret, clock.instant().plusSeconds(30))

    private fun signedIn(
        email: String,
        code: String,
    ): MockHttpSession {
        val session = MockHttpSession()
        console.password(session, email, PASSWORD).status shouldBe 302
        console.code(session, code).status shouldBe 302
        return session
    }

    /** Signs in and returns the console's front page. */
    private fun signIn(
        email: String,
        code: String,
    ): MockHttpServletResponse = console.home(signedIn(email, code))

    private fun signInRowIds(): Set<Any?> =
        jdbc.queryForList("SELECT id FROM audit_log WHERE action = 'ADMIN_SIGN_IN'").map { it["id"] }.toSet()

    private fun counted(outcome: String): Double = meters.counter("moyi.admin.sign_in", "outcome", outcome).count()

    /**
     * Runs [block] while another transaction holds the account's row lock, the one the
     * compare-and-set must take; `release` commits it. Rolled back however [block] ends, so a
     * failed assertion does not leave requests queued behind it.
     */
    private fun <T> whileRowIsHeld(
        id: UUID,
        block: (holderPid: Int, release: () -> Unit) -> T,
    ): T =
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            try {
                lockAccountRow(holder, id)
                block(backendPidOf(holder)) { holder.commit() }
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
     * Backends waiting behind the holder, directly or not: the second request to reach a held
     * row queues behind the first one's tuple lock, so it names the first, not the holder.
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

    private companion object {
        const val PASSWORD = "nineteen silver kettles hum"
        const val LOGIN_REFUSED = "That email and password do not match an active account, or the account is locked for a few minutes."
        const val CODE_REFUSED = "That code is not right. Codes change every 30 seconds."
        const val TIMEOUT_SECONDS = 20L
    }
}
