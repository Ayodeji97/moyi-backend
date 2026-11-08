package com.moyi.app

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import javax.sql.DataSource

/**
 * Doc 06 §2 and ADR-0024 (amended 2026-11-08): every error body carries the id of the trace its request was
 * served under, and that id is the one the server's log lines for the request carry. Run against the composition
 * root, because the tracer, the filter that opens a trace per request and the problem builder are wired there and
 * nowhere else.
 *
 * Each kind of producer is asked once: Spring Security's entry point (a 401 before any controller), the global
 * handler for an `ApiException` (a 404 the guard refuses), Bean Validation (a 422), Spring MVC's own refusals
 * rebuilt in our shape (a 405 and a 415), and a module's own advice (identity's 401 for a refused sign-in).
 */
@SpringBootTest(
    properties = [
        // Each account registers from its own address: registration is three an hour per address, and other
        // classes in this JVM share the Redis (CacheHeadersTest's reason).
        "moyi.security.client-address.trusted-proxies=127.0.0.0/8,::1/128",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class ProblemTraceIdTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val issuer: AccessTokenIssuer,
    @Autowired private val json: JsonMapper,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `every kind of error body carries a W3C trace id, and no two requests share one`() {
        val bearer = "Bearer ${token("trace-reader@example.com", "203.0.113.241")}"
        val bodies =
            mapOf(
                "401 from the filter chain" to mockMvc.get("/api/v1/me").andReturn().response,
                "404 from the global handler" to
                    mockMvc.get("/api/v1/bonds/${UUID.randomUUID()}") { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response,
                "422 from Bean Validation" to
                    mockMvc
                        .post("/api/v1/auth/login") {
                            header(FORWARDED_FOR, "203.0.113.241")
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"email":"","password":""}"""
                        }.andReturn()
                        .response,
                "405 from Spring MVC" to mockMvc.patch("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response,
                "415 from Spring MVC" to
                    mockMvc
                        .post("/api/v1/auth/login") {
                            header(FORWARDED_FOR, "203.0.113.241")
                            contentType = MediaType.TEXT_PLAIN
                            content = "x"
                        }.andReturn()
                        .response,
                "401 from identity's advice" to
                    mockMvc
                        .post("/api/v1/auth/login") {
                            header(FORWARDED_FOR, "203.0.113.241")
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"email":"trace-reader@example.com","password":"not the password at all"}"""
                        }.andReturn()
                        .response,
            )

        val ids =
            bodies.map { (kind, response) ->
                withClue(kind) {
                    response.contentType.shouldNotBeNull() shouldStartWith MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    traceIdOf(response) shouldMatch TRACE_ID
                }
            }
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `the trace id in the body is the one the log line for that request carries`(output: CapturedOutput) {
        val bearer = "Bearer ${token("trace-logged@example.com", "203.0.113.242")}"

        // A bond that does not exist: the guard's 404, which the global handler logs at WARN.
        val response = mockMvc.get("/api/v1/bonds/${UUID.randomUUID()}") { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
        response.status shouldBe 404
        val traceId = traceIdOf(response)

        val logged = output.all.lines().filter { it.contains("NOT_FOUND -> 404") }
        withClue("the WARN line for the refusal") {
            logged.map { line -> TRACE_ID_IN_LOG.find(line)?.groupValues?.get(1) } shouldContain traceId
        }
        traceId shouldNotBe "0".repeat(TRACE_ID_LENGTH)
    }

    private fun traceIdOf(response: MockHttpServletResponse): String =
        json
            .readTree(response.contentAsString)
            .path("traceId")
            .asString()

    /** Registers from an address of this class's own, so its buckets are no other test's (registration is 3 an hour). */
    private fun token(
        email: String,
        address: String,
    ): String {
        mockMvc
            .post("/api/v1/auth/register") {
                header(FORWARDED_FOR, address)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"correct horse battery","displayName":"Trace",""" +
                    """"locale":"en","acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!!
        return issuer.issue(userId).token
    }

    private companion object {
        const val TRACE_ID_LENGTH = 32
        const val FORWARDED_FOR = "X-Forwarded-For"
        val TRACE_ID = Regex("^[0-9a-f]{$TRACE_ID_LENGTH}$")

        /** The ECS log line's trace field, whatever key the formatter gives it. */
        val TRACE_ID_IN_LOG = Regex("\"(?:trace\\.id|traceId)\":\"([0-9a-f]{$TRACE_ID_LENGTH})\"")
    }
}
