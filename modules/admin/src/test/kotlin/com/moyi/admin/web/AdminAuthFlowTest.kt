package com.moyi.admin.web

import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.admin.service.AdminConsoleServiceTest
import com.moyi.admin.service.AdminConsoleServiceTest.Companion.code
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Every admin page, in the order an operator meets them, inside this module's context: the
 * enrolment form and its refusals, the two sign-in steps and theirs, the console, sign-out,
 * and the error page for a form without its CSRF token. `app`'s tests hold the same pages to
 * the real password port, the audit rows and the concurrency of the code step.
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
internal class AdminAuthFlowTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val cipher = TotpSecretCipher(Base64.getDecoder().decode(AdminConsoleServiceTest.KEY))

    @Test
    fun `enrol, sign in with both factors, reach the console, and sign out`() {
        val email = "flow-${UUID.randomUUID().toString().take(8)}@example.com"
        val token = jdbc.queryForObject("SELECT admin_invite(?::citext, 'SUPPORT')", String::class.java, email)!!
        val session = MockHttpSession()

        get("/admin/enrol?token=$token", session).status shouldBe 200
        val secret =
            cipher.decrypt(
                jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts WHERE email = ?::citext", String::class.java, email)!!,
            )
        val enrolCode = code(secret, clock.instant())
        val form = mapOf("token" to token, "password" to PASSWORD, "confirm" to PASSWORD, "code" to enrolCode)

        post("/admin/enrol", session, form + ("confirm" to "something else")).let {
            it.status shouldBe 422
            it.contentAsString shouldContain "The two passwords are not the same."
        }
        post("/admin/enrol", session, form + ("password" to "short") + ("confirm" to "short")).contentAsString shouldContain
            "Use a password of 15 to 128 characters."
        val breached = AdminTestApplication.BREACHED
        post("/admin/enrol", session, form + ("password" to breached) + ("confirm" to breached)).contentAsString shouldContain
            "appeared in a data breach"
        post("/admin/enrol", session, form + ("code" to "abcdef")).contentAsString shouldContain "That code is not right."
        post("/admin/enrol", session, form).redirectedUrl!! shouldEndWith "/admin/login?enrolled"
        post("/admin/enrol", session, form).let {
            it.status shouldBe 404
            it.contentAsString shouldContain "This link is not valid."
        }
        get("/admin/enrol", session).status shouldBe 404

        get("/admin/login?enrolled", session).contentAsString shouldContain "Your account is ready."
        post("/admin/login", session, mapOf("email" to email, "password" to "wrong")).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "do not match an active account"
        }
        post("/admin/login", session, mapOf("email" to email, "password" to PASSWORD)).redirectedUrl!! shouldEndWith "/admin/login/totp"
        get("/admin/login/totp", session).status shouldBe 200
        post("/admin/login/totp", session, mapOf("code" to "abcdef")).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "That code is not right."
        }
        post("/admin/login/totp", session, mapOf("code" to code(secret, clock.instant().plusSeconds(30)))).redirectedUrl!! shouldEndWith
            "/admin"
        get("/admin", session).contentAsString shouldContain "Signed in"

        post("/admin/logout", session, emptyMap()).redirectedUrl!! shouldEndWith "/admin/login?signedOut"
        session.isInvalid shouldBe true
        post("/admin/logout", MockHttpSession(), emptyMap()).redirectedUrl!! shouldEndWith "/admin/login?signedOut"
    }

    @Test
    fun `a form without its CSRF token gets the admin error page, which says nothing of why`() {
        val refused =
            mockMvc
                .post("/admin/login") {
                    with { it.apply { remoteAddr = ALLOWED } }
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    param("email", "someone@example.com")
                    param("password", PASSWORD)
                }.andReturn()
                .response
        refused.status shouldBe 403
        refused.contentType!! shouldContain MediaType.TEXT_HTML_VALUE
        refused.contentAsString shouldContain "Error 403"
        refused.contentAsString shouldNotContain "CSRF"
    }

    @Test
    fun `an exception out of an admin controller is the error page, with none of its message`() {
        val page = AdminErrorAdvice().failed(IllegalStateException("the password was hunter2"))
        page.viewName shouldBe AdminErrorPage.VIEW
        page.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
        page.model.values.joinToString() shouldNotContain "hunter2"
    }

    private fun get(
        path: String,
        session: MockHttpSession,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
            }.andReturn()
            .response

    /** A form post, with the CSRF token the session's sign-in page carries. */
    private fun post(
        path: String,
        session: MockHttpSession,
        fields: Map<String, String>,
    ): MockHttpServletResponse {
        val csrf = CSRF.find(get("/admin/login", session).contentAsString)?.groupValues?.get(1) ?: ""
        return mockMvc
            .post(path) {
                with { it.apply { remoteAddr = ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", csrf)
                fields.forEach { (name, value) -> param(name, value) }
            }.andReturn()
            .response
    }

    private companion object {
        const val ALLOWED = "10.9.1.1"
        const val PASSWORD = AdminConsoleServiceTest.PASSWORD
        val CSRF = Regex("""name="_csrf" value="([^"]+)"""")
    }
}
