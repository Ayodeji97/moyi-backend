package com.moyi.app.admin

import com.moyi.app.admin.AdminConsoleDriver.Companion.code
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.random.Random

/**
 * The first visit, by invitation (spec §6): a fresh secret on every `GET`, a password policy
 * and one current code on the `POST`, and only a correct code activates the account, spends
 * the invitation and writes `ADMIN_ENROLLED`. Any other link gets one page, with no reason.
 */
class AdminEnrolmentTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val dataSource: DataSource,
    @LocalServerPort private val port: Int,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val console = AdminConsoleDriver(mockMvc, jdbc)

    @Test
    fun `the enrolment page shows a QR code and the key, and a second visit shows a new key`() {
        val email = newEmail()
        val token = console.invite(email)

        val first = console.enrolPage(token)
        first.status shouldBe 200
        first.contentAsString shouldContain """<img class="qr" src="data:image/png;base64,"""
        val firstKey = keyOn(first)
        firstKey shouldBe base32(console.secretOf(email))

        val second = console.enrolPage(token)
        second.status shouldBe 200
        keyOn(second) shouldNotBe firstKey
        keyOn(second) shouldBe base32(console.secretOf(email))
    }

    /**
     * Found by the smoke run, not by `MockMvc`: the page is larger than the response buffer
     * (the QR code), so a visitor with no session yet had the response committed before the
     * form's CSRF token needed one, and Tomcat cut the page off mid-form. Only a real server,
     * and a first visit with no cookie, shows it.
     */
    @Test
    fun `a first visit with no session gets the whole page, with its form and a session cookie`() {
        val token = console.invite(newEmail())

        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/admin/enrol?token=$token"))
                .header("X-Forwarded-For", ALLOWED)
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        response.statusCode() shouldBe 200
        response.body().trim() shouldEndWith "</html>"
        response.body() shouldContain "name=\"_csrf\""
        response.headers().allValues("Set-Cookie").single() shouldContain "moyi-admin="
    }

    @Test
    fun `a refused password re-renders the form with the field's error, and the account stays invited`() {
        val email = newEmail()
        val token = console.invite(email)
        val session = MockHttpSession()
        console.enrolPage(token, session)
        val code = code(console.secretOf(email), clock.instant())

        val cases =
            listOf(
                Triple(PASSWORD, "$PASSWORD-but-not", "confirm-error"),
                Triple("fourteen-chars", "fourteen-chars", "password-error"),
                Triple("passwordpassword", "passwordpassword", "password-error"),
            )
        cases.forEach { (password, confirm, errorId) ->
            val page = console.submitEnrolment(session, token, password, confirm, code)
            page.status shouldBe 422
            page.contentAsString shouldContain """id="$errorId""""
            page.contentAsString shouldContain """aria-invalid="true""""
            statusOf(email) shouldBe "INVITED"
        }
        totpLastStep(email).shouldBeNull()
        consumedAt(token).shouldBeNull()
    }

    @Test
    fun `a wrong code consumes nothing, and the same link then works`() {
        val email = newEmail()
        val token = console.invite(email)
        val session = MockHttpSession()
        console.enrolPage(token, session)
        val secret = console.secretOf(email)

        val wrong = console.submitEnrolment(session, token, PASSWORD, code = wrongCode(secret))
        wrong.status shouldBe 422
        wrong.contentAsString shouldContain "That code is not right. Codes change every 30 seconds."
        statusOf(email) shouldBe "INVITED"
        totpLastStep(email).shouldBeNull()
        consumedAt(token).shouldBeNull()
        jdbc.queryForObject("SELECT password_hash FROM admin_accounts WHERE email = ?::citext", String::class.java, email).shouldBeNull()

        console.submitEnrolment(session, token, PASSWORD, code = code(secret, clock.instant())).status shouldBe 302
    }

    @Test
    fun `a correct code activates the account, spends the invitation and the step, and is audited`() {
        val email = newEmail()
        val token = console.invite(email)
        val session = MockHttpSession()
        console.enrolPage(token, session)
        val secret = console.secretOf(email)
        val id = console.idOf(email)

        val done = console.submitEnrolment(session, token, PASSWORD, code = code(secret, clock.instant()))

        done.status shouldBe 302
        done.redirectedUrl!!.substringBefore('?') shouldEndWith "/admin/login"
        statusOf(email) shouldBe "ACTIVE"
        consumedAt(token).shouldNotBeNull()
        totpLastStep(email) shouldBe clock.instant().epochSecond / 30
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'ADMIN_ENROLLED' AND outcome = 'SUCCEEDED' " +
                "AND actor_type = 'ADMIN' AND actor_id = ? AND target_id = ?",
            Int::class.java,
            id,
            id.toString(),
        ) shouldBe 1
    }

    @Test
    fun `a used, an expired or a made-up link is not valid, and says no more`() {
        val email = newEmail()
        val used = console.invite(email)
        val session = MockHttpSession()
        console.enrolPage(used, session)
        console.submitEnrolment(session, used, PASSWORD, code = code(console.secretOf(email), clock.instant())).status shouldBe 302
        shouldBeInvalid(console.enrolPage(used))
        shouldBeInvalid(console.submitEnrolment(MockHttpSession(), used, PASSWORD, code = "123456"))

        val expiring = console.invite(newEmail())
        val start = clock.instant()
        try {
            // Its expiry is the database's now() + 24 h; the test clock started a little earlier.
            clock.set(expiresAt(expiring).plus(Duration.ofSeconds(1)))
            shouldBeInvalid(console.enrolPage(expiring))
        } finally {
            clock.set(start)
        }
        console.enrolPage(expiring).status shouldBe 200

        val madeUp = Base64.getUrlEncoder().withoutPadding().encodeToString(Random.nextBytes(32))
        shouldBeInvalid(console.enrolPage(madeUp))
        shouldBeInvalid(console.enrolPage("not base64 at all"))
    }

    @Test
    fun `a page load queued behind a finishing enrolment does not undo it`() {
        val email = newEmail()
        val token = console.invite(email)
        console.enrolPage(token).status shouldBe 200
        val id = console.idOf(email)
        val pool = Executors.newSingleThreadExecutor()

        val reload =
            try {
                dataSource.connection.use { holder ->
                    holder.autoCommit = false
                    try {
                        // What `complete` holds and writes until it commits: the invitation
                        // locked, the account active, the invitation spent.
                        holder.finishEnrolment(token, id)
                        val holderPid = backendPidOf(holder)
                        val page = pool.submit<MockHttpServletResponse> { console.enrolPage(token) }
                        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { blockedBy(holderPid) >= 1 }
                        holder.commit()
                        page.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } finally {
                        holder.rollback()
                        holder.autoCommit = true
                    }
                }
            } finally {
                pool.shutdownNow()
            }

        shouldBeInvalid(reload)
        statusOf(email) shouldBe "ACTIVE"
        jdbc.queryForObject(
            "SELECT password_hash FROM admin_accounts WHERE id = ?",
            String::class.java,
            id,
        ) shouldBe "held-hash"
        totpLastStep(email) shouldBe 1L
    }

    private fun Connection.finishEnrolment(
        token: String,
        id: UUID,
    ) {
        prepareStatement("SELECT 1 FROM admin_invitations WHERE token_hash = $TOKEN_HASH FOR UPDATE").use {
            it.setString(1, token)
            it.executeQuery().use { rows -> rows.next() shouldBe true }
        }
        prepareStatement(
            "UPDATE admin_accounts SET status = 'ACTIVE', password_hash = 'held-hash', totp_last_step = 1, " +
                "activated_at = now(), credentials_changed_at = now() WHERE id = ?",
        ).use {
            it.setObject(1, id)
            it.executeUpdate() shouldBe 1
        }
        prepareStatement("UPDATE admin_invitations SET consumed_at = now() WHERE token_hash = $TOKEN_HASH").use {
            it.setString(1, token)
            it.executeUpdate() shouldBe 1
        }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun blockedBy(holderPid: Int): Int =
        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, holderPid)!!

    private fun shouldBeInvalid(page: MockHttpServletResponse) {
        page.status shouldBe 404
        page.contentAsString shouldContain "This link is not valid."
        page.contentAsString shouldNotContain "<form"
    }

    private fun keyOn(page: MockHttpServletResponse): String = KEY.find(page.contentAsString)!!.groupValues[1]

    private fun statusOf(email: String) =
        jdbc.queryForObject("SELECT status FROM admin_accounts WHERE email = ?::citext", String::class.java, email)

    private fun totpLastStep(email: String): Long? =
        jdbc.queryForObject("SELECT totp_last_step FROM admin_accounts WHERE email = ?::citext", Long::class.javaObjectType, email)

    private fun consumedAt(token: String): OffsetDateTime? =
        jdbc.queryForObject(
            "SELECT consumed_at FROM admin_invitations WHERE token_hash = $TOKEN_HASH",
            OffsetDateTime::class.java,
            token,
        )

    private fun expiresAt(token: String) =
        jdbc
            .queryForObject(
                "SELECT expires_at FROM admin_invitations WHERE token_hash = $TOKEN_HASH",
                OffsetDateTime::class.java,
                token,
            )!!
            .toInstant()

    /** A six-digit code no step of the window produces. */
    private fun wrongCode(secret: ByteArray): String {
        val now = clock.instant()
        val valid = (-1L..1L).map { code(secret, now.plusSeconds(it * 30)) }.toSet()
        return generateSequence(0) { it + 1 }.map { "%06d".format(it) }.first { it !in valid }
    }

    private fun base32(secret: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = secret.joinToString("") { (it.toInt() and 0xFF).toString(2).padStart(8, '0') }
        return bits.chunked(5).joinToString("") { alphabet[it.padEnd(5, '0').toInt(2)].toString() }
    }

    private fun newEmail() = "enrol-${UUID.randomUUID().toString().take(8)}@example.com"

    private companion object {
        const val PASSWORD = "seventeen quiet harbour lamps"
        const val TIMEOUT_SECONDS = 20L

        /** The stored hash of a token, as `admin_invite` made it: SHA-256 of the 32 raw bytes. */
        const val TOKEN_HASH = "sha256(decode(translate(?, '-_', '+/') || '=', 'base64'))"
        val KEY = Regex("""<code id="secret">([A-Z2-7]+)</code>""")
    }
}
