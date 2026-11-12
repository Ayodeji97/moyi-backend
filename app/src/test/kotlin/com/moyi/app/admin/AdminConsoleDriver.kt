package com.moyi.app.admin

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Drives the console the way an operator does (Ruling B): the invitation through the
 * operator's SQL function, everything else through HTTP from an allowed address, with the
 * CSRF token read off the page that rendered the form. A TOTP code is what an authenticator
 * app would show: computed from the stored secret, unsealed with the test's key.
 */
internal class AdminConsoleDriver(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcTemplate,
) {
    /** `admin_invite`, as the runbook runs it: the one-time token. */
    fun invite(
        email: String,
        role: String = "SUPPORT",
    ): String = jdbc.queryForObject("SELECT admin_invite(?::citext, ?)", String::class.java, email, role)!!

    fun enrolPage(
        token: String,
        session: MockHttpSession = MockHttpSession(),
    ): MockHttpServletResponse =
        mockMvc
            .get("/admin/enrol") {
                with { it.apply { remoteAddr = ALLOWED } }
                param("token", token)
                this.session = session
            }.andReturn()
            .response

    @Suppress("LongParameterList")
    fun submitEnrolment(
        session: MockHttpSession,
        token: String,
        password: String,
        confirm: String = password,
        code: String,
        // The sign-in page's form carries the session's token too, and asks for no new secret.
        csrf: String = csrfFrom(loginPage(session)),
    ): MockHttpServletResponse =
        mockMvc
            .post("/admin/enrol") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", csrf)
                param("token", token)
                param("password", password)
                param("confirm", confirm)
                param("code", code)
            }.andReturn()
            .response

    /** Invites [email] as [role], enrols it with [password] at the clock's [now], and returns its TOTP secret. */
    fun enrol(
        email: String,
        password: String,
        now: Instant,
        role: String = "SUPPORT",
    ): ByteArray {
        val token = invite(email, role)
        val session = MockHttpSession()
        enrolPage(token, session).status shouldBe 200
        val secret = secretOf(email)
        val done = submitEnrolment(session, token, password, code = code(secret, now))
        done.status shouldBe 302
        return secret
    }

    fun loginPage(session: MockHttpSession): MockHttpServletResponse =
        mockMvc
            .get("/admin/login") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
            }.andReturn()
            .response

    fun password(
        session: MockHttpSession,
        email: String,
        password: String,
    ): MockHttpServletResponse {
        val csrf = csrfFrom(loginPage(session))
        return mockMvc
            .post("/admin/login") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", csrf)
                param("email", email)
                param("password", password)
            }.andReturn()
            .response
    }

    fun totpPage(session: MockHttpSession): MockHttpServletResponse =
        mockMvc
            .get("/admin/login/totp") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
            }.andReturn()
            .response

    fun code(
        session: MockHttpSession,
        code: String,
        csrf: String = csrfFrom(totpPage(session)),
    ): MockHttpServletResponse =
        mockMvc
            .post("/admin/login/totp") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", csrf)
                param("code", code)
            }.andReturn()
            .response

    fun home(session: MockHttpSession): MockHttpServletResponse =
        mockMvc
            .get("/admin") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
            }.andReturn()
            .response

    fun logout(session: MockHttpSession): MockHttpServletResponse {
        val csrf = csrfFrom(home(session))
        return mockMvc
            .post("/admin/logout") {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", csrf)
            }.andReturn()
            .response
    }

    /** The account's TOTP secret, as the authenticator app holds it. */
    fun secretOf(email: String): ByteArray =
        unseal(jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!)

    fun idOf(email: String): UUID = jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!!

    companion object {
        const val ALLOWED = AdminEnabledTest.ALLOWED

        /** The `moyi.admin.totp-key` of [AdminEnabledTest]. */
        private val KEY = SecretKeySpec(Base64.getDecoder().decode("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="), "AES")
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val generator = TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6)
        private val CSRF = Regex("""name="_csrf" value="([^"]+)"""")

        /** Stored form: base64(nonce12 ‖ ciphertext ‖ tag16), AES/GCM/NoPadding (Ruling B). */
        fun unseal(sealed: String): ByteArray {
            val raw = Base64.getDecoder().decode(sealed)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, KEY, GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
            return cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)
        }

        fun code(
            secret: ByteArray,
            at: Instant,
        ): String = generator.generateOneTimePasswordString(SecretKeySpec(secret, "HmacSHA1"), at)

        fun csrfFrom(page: MockHttpServletResponse): String =
            checkNotNull(CSRF.find(page.contentAsString)?.groupValues?.get(1)) { "no CSRF field on the page (status ${page.status})" }
    }
}
