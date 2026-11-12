package com.moyi.app.admin

import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * **Switched off, the console does not exist** (spec §4). With the shipped defaults there is
 * no admin chain, no admin bean, and every path under `/admin` answers exactly as a path nobody wrote does:
 * `401` without a token, `404` with one. The vendored htmx is part of that: a file served
 * from Boot's default static location would answer a token holder `200` and say the console
 * is installed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDisabledTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `without a token, an admin path is the same 401 problem as a path that does not exist`() {
        val nowhere = get("/no-such-path", token = null)
        listOf("/admin/login", "/admin", "/admin/static/htmx.min.js").forEach { path ->
            withClue(path) {
                val admin = get(path, token = null)
                admin.status shouldBe 401
                admin.contentType shouldBe nowhere.contentType
                withoutTraceId(admin.contentAsString) shouldBe unauthenticatedProblem(path)
                admin.allHeaders() shouldBe nowhere.allHeaders()
            }
        }
        withoutTraceId(nowhere.contentAsString) shouldBe unauthenticatedProblem("/no-such-path")
    }

    @Test
    fun `with a token, an admin path is the same 404 as a path that does not exist, htmx included`() {
        val token = mockMvc.bearerToken(jdbc)
        val nowhere = get("/no-such-path", token)
        nowhere.status shouldBe 404
        listOf("/admin/login", "/admin/static/htmx.min.js").forEach { path ->
            withClue(path) {
                val admin = get(path, token)
                admin.status shouldBe 404
                admin.contentType shouldBe nowhere.contentType
                withoutTraceId(admin.contentAsString) shouldBe
                    withoutTraceId(nowhere.contentAsString).replace("/no-such-path", path)
            }
        }
    }

    @Test
    fun `without a token, every A2 route is the same 401 problem as a path that does not exist`() {
        val nowhere = get("/no-such-path", token = null)
        val nowherePost = post("/no-such-path", token = null)
        A2_GETS.forEach { path ->
            withClue("GET $path") {
                val admin = get(path, token = null)
                admin.status shouldBe 401
                withoutTraceId(admin.contentAsString) shouldBe unauthenticatedProblem(path.substringBefore('?'))
                admin.allHeaders() shouldBe nowhere.allHeaders()
            }
        }
        A2_POSTS.forEach { path ->
            withClue("POST $path") {
                val admin = post(path, token = null)
                admin.status shouldBe 401
                withoutTraceId(admin.contentAsString) shouldBe unauthenticatedProblem(path)
                admin.allHeaders() shouldBe nowherePost.allHeaders()
            }
        }
    }

    @Test
    fun `with a token, every A2 route is the same 404 as a path that does not exist`() {
        val token = mockMvc.bearerToken(jdbc)
        val nowhere = get("/no-such-path", token)
        val nowherePost = post("/no-such-path", token)
        nowhere.status shouldBe 404
        nowherePost.status shouldBe 404
        A2_GETS.forEach { path ->
            withClue("GET $path") {
                val admin = get(path, token)
                admin.status shouldBe 404
                admin.contentType shouldBe nowhere.contentType
                withoutTraceId(admin.contentAsString) shouldBe
                    withoutTraceId(nowhere.contentAsString).replace("/no-such-path", path.substringBefore('?'))
            }
        }
        A2_POSTS.forEach { path ->
            withClue("POST $path") {
                val admin = post(path, token)
                admin.status shouldBe 404
                admin.contentType shouldBe nowherePost.contentType
                withoutTraceId(admin.contentAsString) shouldBe
                    withoutTraceId(nowherePost.contentAsString).replace("/no-such-path", path)
            }
        }
    }

    @Test
    fun `no admin bean exists`() {
        context.getBeanNamesForType(Class.forName("com.moyi.admin.service.AdminProperties")).toList().shouldBeEmpty()
        context.containsBean("adminSecurityFilterChain") shouldBe false
        // A2's console, by class name: the types are `internal` to the admin module.
        A2_BEANS.forEach { name ->
            withClue(name) { context.getBeanNamesForType(Class.forName(name)).toList().shouldBeEmpty() }
        }
    }

    @Test
    fun `the export's disclosure exists with the console off`() {
        // ADR-0055 ruling D: the audit log is persistence, so the export reads it either way.
        context.getBeanNamesForType(Class.forName(DISCLOSURE)).toList().size shouldBe 1
    }

    private fun get(
        path: String,
        token: String?,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
            .andReturn()
            .response

    /** A form POST, as the console's pages send one, its CSRF field included. */
    private fun post(
        path: String,
        token: String?,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", UUID.randomUUID().toString())
                param("note", "a note")
            }.andReturn()
            .response

    private companion object {
        val ID: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-0000000a2a2a")

        val A2_GETS =
            listOf(
                "/admin/reports",
                "/admin/reports/$ID",
                "/admin/quarantine",
                "/admin/quarantine/$ID",
                "/admin/media/$ID/review",
                "/admin/users?q=x",
            )

        val A2_POSTS =
            listOf(
                "/admin/users",
                "/admin/reports/$ID/dismiss",
                "/admin/quarantine/$ID/delete",
            )

        val A2_BEANS =
            listOf(
                "com.moyi.admin.service.ModerationConsole",
                "com.moyi.admin.web.ReportsController",
                "com.moyi.admin.web.QuarantineController",
                "com.moyi.admin.web.MediaReviewController",
                "com.moyi.admin.service.UserLookup",
                "com.moyi.admin.service.AdminSignIn",
            )

        const val DISCLOSURE = "com.moyi.admin.service.AdminAccessDisclosure"
    }
}
