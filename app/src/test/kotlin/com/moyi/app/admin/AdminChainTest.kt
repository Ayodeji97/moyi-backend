package com.moyi.app.admin

import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * The admin chain (spec §4, §6, §7): an allow-list that runs before anything else and,
 * refusing, is the API's own 401; CSRF on every unsafe method; the spec's headers; two
 * factors before any page but the sign-in steps; and a session that ends the moment the
 * account it was opened for changes.
 *
 * The admin internals are `internal` to their module, so this test touches their state the
 * way an operator would: rows through SQL and requests through HTTP (Ruling B). A signed-in
 * session is built as the sign-in controller will build it (Task 5): the security context
 * under Spring's session key, whose principal is the admin's id, and the two attributes the
 * session guard reads.
 */
class AdminChainTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @LocalServerPort private val port: Int,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val http = HttpClient.newHttpClient()

    @Test
    fun `a refused address gets the API's 401 byte for byte, whatever the path and whatever the token`() {
        val api = mockMvc.get("/no-such-path") { with { it.apply { remoteAddr = REFUSED } } }.andReturn()
        api.response.status shouldBe 401
        val token = mockMvc.bearerToken(jdbc)

        listOf("/admin/login", "/admin/static/htmx.min.js", "/admin/nope", "/admin").forEach { path ->
            listOf<String?>(null, token).forEach { bearer ->
                withClue("$path, token=${bearer != null}") {
                    val refused =
                        mockMvc
                            .get(path) {
                                with { it.apply { remoteAddr = REFUSED } }
                                // A forged hop is not believed: the socket is not a trusted proxy.
                                header("X-Forwarded-For", ALLOWED)
                                if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                            }.andReturn()
                    refused.response.status shouldBe 401
                    refused.response.contentType shouldBe api.response.contentType
                    withoutTraceId(refused.response.contentAsString) shouldBe unauthenticatedProblem(path)
                    refused.response.allHeaders() shouldBe api.response.allHeaders()
                    refused.response.setsACookie() shouldBe false
                    refused.request.getSession(false).shouldBeNull()
                }
            }
        }
    }

    @Test
    fun `a refused address on a real socket sets no cookie`() {
        // No X-Forwarded-For: the client is loopback itself, which is not on the list.
        val refused = send("/admin/login", forwardedFor = null)
        refused.statusCode() shouldBe 401
        withoutTraceId(refused.body()) shouldBe unauthenticatedProblem("/admin/login")
        refused.headers().allValues("Set-Cookie").shouldBeEmpty()
    }

    @Test
    fun `an allowed address gets the sign-in page with the spec's headers`() {
        val page = mockMvc.get("/admin/login") { with { it.apply { remoteAddr = ALLOWED } } }.andReturn().response
        page.status shouldBe 200
        page.contentType!! shouldStartWith MediaType.TEXT_HTML_VALUE
        page.getHeaders("Content-Security-Policy") shouldBe listOf(CSP)
        page.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("no-store")
        page.getHeaders("Referrer-Policy") shouldBe listOf("no-referrer")
        page.getHeaders("X-Content-Type-Options") shouldBe listOf("nosniff")
    }

    @Test
    fun `the moyi-admin cookie is set only once a session exists, and only on the admin path`() {
        val script = send("/admin/static/htmx.min.js", ALLOWED)
        script.statusCode() shouldBe 200
        script.body() shouldStartWith "var htmx="
        script.headers().allValues("Set-Cookie").shouldBeEmpty()
        script.headers().allValues("Cache-Control") shouldBe listOf("no-store")

        val first = send("/admin/login", ALLOWED)
        first.statusCode() shouldBe 200
        val cookies = first.headers().allValues("Set-Cookie")
        cookies shouldHaveSize 1
        val cookie = cookies.single()
        cookie shouldStartWith "moyi-admin="
        listOf("Path=/admin", "Secure", "HttpOnly", "SameSite=Strict").forEach { cookie shouldContain it }

        val again = send("/admin/login", ALLOWED, cookie = cookie.substringBefore(';'))
        again.statusCode() shouldBe 200
        again.headers().allValues("Set-Cookie").shouldBeEmpty()
    }

    @Test
    fun `a POST without a CSRF token is 403 and counts nothing against the account`() {
        val email = newAdmin().second
        val response =
            mockMvc
                .post("/admin/login") {
                    with { it.apply { remoteAddr = ALLOWED } }
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    param("email", email)
                    param("password", "not the password at all")
                }.andReturn()
                .response
        response.status shouldBe 403
        jdbc.queryForObject("SELECT failed_attempts FROM admin_accounts WHERE email = ?::citext", Int::class.java, email) shouldBe 0
        jdbc
            .queryForObject("SELECT locked_until FROM admin_accounts WHERE email = ?::citext", OffsetDateTime::class.java, email)
            .shouldBeNull()
    }

    @Test
    fun `no session goes to the sign-in page, and a password-only session goes to the code page`() {
        adminHome(session = null).response.redirectedUrl!! shouldEndWith "/admin/login"

        val (id, _) = newAdmin()
        val passwordOnly = session(id, FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY))
        val result = adminHome(passwordOnly)
        result.response.status shouldBe 302
        // Spring names the missing factor in the query (`factor.type=totp`); the page is what matters.
        result.response.redirectedUrl!!.substringBefore('?') shouldEndWith "/admin/login/totp"
    }

    @Test
    fun `both factors and a role reach the console`() {
        val (id, _) = newAdmin()
        val result = adminHome(signedIn(id))
        result.response.status shouldBe 200
        result.response.contentAsString shouldContain "Signed in"
    }

    @Test
    fun `the guard ends a session whose credentials changed since sign-in`() {
        val (id, _) = newAdmin()
        val session = signedIn(id)
        session.setAttribute(CREDENTIALS_CHANGED_AT, credentialsChangedAt(id).minusSeconds(1))

        adminHome(session).response.redirectedUrl!! shouldEndWith "/admin/login"
        session.isInvalid shouldBe true
    }

    @Test
    fun `the guard ends a session past its absolute deadline`() {
        val (id, _) = newAdmin()
        val session = signedIn(id)
        adminHome(session).response.status shouldBe 200

        val start = clock.instant()
        try {
            clock.advance(ABSOLUTE.plusSeconds(1))
            adminHome(session).response.redirectedUrl!! shouldEndWith "/admin/login"
            session.isInvalid shouldBe true
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `the guard ends the session of an account that is no longer active`() {
        val (id, _) = newAdmin()
        val session = signedIn(id)
        jdbc.update("UPDATE admin_accounts SET status = 'DISABLED' WHERE id = ?", id) shouldBe 1

        adminHome(session).response.redirectedUrl!! shouldEndWith "/admin/login"
        session.isInvalid shouldBe true
    }

    private fun adminHome(session: MockHttpSession?): MvcResult =
        mockMvc
            .get("/admin") {
                with { it.apply { remoteAddr = ALLOWED } }
                if (session != null) this.session = session
            }.andReturn()

    /** An `ACTIVE` account, inserted as `admin_invite` and enrolment together would leave it. */
    private fun newAdmin(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val email = "admin-${id.toString().take(8)}@example.com"
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, password_hash, credentials_changed_at, status, failed_attempts) " +
                "VALUES (?, ?, 'SUPPORT', 'not-a-hash', ?, 'ACTIVE', 0)",
            id,
            email,
            Timestamp.from(clock.instant()),
        )
        return id to email
    }

    private fun credentialsChangedAt(id: UUID): Instant =
        jdbc
            .queryForObject("SELECT credentials_changed_at FROM admin_accounts WHERE id = ?", OffsetDateTime::class.java, id)!!
            .toInstant()

    private fun signedIn(id: UUID): MockHttpSession =
        session(
            id,
            FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
            FactorGrantedAuthority.fromAuthority(FACTOR_TOTP),
            SimpleGrantedAuthority("ROLE_SUPPORT"),
        )

    private fun session(
        id: UUID,
        vararg authorities: GrantedAuthority,
    ): MockHttpSession =
        MockHttpSession().apply {
            val authentication = UsernamePasswordAuthenticationToken.authenticated(id, null, authorities.toList())
            setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(authentication))
            setAttribute(CREDENTIALS_CHANGED_AT, credentialsChangedAt(id))
            setAttribute(ABSOLUTE_DEADLINE, clock.instant().plus(ABSOLUTE))
        }

    private fun send(
        path: String,
        forwardedFor: String?,
        cookie: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .apply { if (forwardedFor != null) header("X-Forwarded-For", forwardedFor) }
                .apply { if (cookie != null) header("Cookie", cookie) }
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val CSP =
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; " +
                "form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

        // The names the sign-in controller and the session guard agree on (plan, Task 4 and 5).
        const val FACTOR_TOTP = "FACTOR_TOTP"
        const val CREDENTIALS_CHANGED_AT = "admin.credentialsChangedAt"
        const val ABSOLUTE_DEADLINE = "admin.absoluteDeadline"
        val ABSOLUTE: Duration = Duration.ofHours(8)
    }
}
