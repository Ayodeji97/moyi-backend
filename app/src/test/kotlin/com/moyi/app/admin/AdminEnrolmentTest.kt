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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
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
    @Autowired dataSource: DataSource,
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
            "SELECT consumed_at FROM admin_invitations WHERE token_hash = sha256(decode(translate(?, '-_', '+/') || '=', 'base64'))",
            OffsetDateTime::class.java,
            token,
        )

    private fun expiresAt(token: String) =
        jdbc
            .queryForObject(
                "SELECT expires_at FROM admin_invitations WHERE token_hash = sha256(decode(translate(?, '-_', '+/') || '=', 'base64'))",
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
        val KEY = Regex("""<code id="secret">([A-Z2-7]+)</code>""")
    }
}
