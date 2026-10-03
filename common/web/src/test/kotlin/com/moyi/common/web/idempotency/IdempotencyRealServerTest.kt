package com.moyi.common.web.idempotency

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.Principal
import java.util.UUID

/**
 * `@Idempotent` against a **real** Tomcat, for the one request MockMvc cannot
 * send: a body with no `Content-Length` (A5, whole-branch review).
 *
 * `Transfer-Encoding: chunked` is ordinary — it is what a streaming HTTP
 * client sends when it does not know the body's size before it starts, and
 * the KMP client's engines (Ktor, OkHttp) do that. The servlet container
 * reports such a request's length as `-1`. The caching filter used to
 * decline to wrap anything without a declared length, and
 * [IdempotencyInterceptor] treated an unwrapped request as a wiring bug: an
 * `IllegalStateException`, a `500`, for a well-formed `POST`.
 *
 * `java.net.http.HttpClient` with `BodyPublishers.ofInputStream` on HTTP/1.1
 * sends exactly that: a publisher of unknown length has no `Content-Length`
 * to send. That these requests really arrive without one was observed, not
 * assumed — before the fix each of them was the `500` above, and the same
 * body sent with a length was not.
 *
 * The caller is a header here, turned into a `Principal` by a test filter:
 * `common:web` cannot reach `common:security`'s bearer chain, and what is
 * under test is the body, not the token.
 */
@SpringBootTest(classes = [IdempotencyTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdempotencyRealServerTest.CallerConfiguration::class)
class IdempotencyRealServerTest(
    @Autowired private val controller: IdempotencyProbeController,
    @Autowired private val jdbc: JdbcTemplate,
    @LocalServerPort private val port: Int,
) : PostgresIntegrationTest() {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val ada = UUID.randomUUID()

    @AfterEach
    fun reset() {
        controller.reset()
        jdbc.execute("TRUNCATE TABLE idempotency_keys, probe_results")
    }

    @Test
    fun `a chunked POST runs once, and its replay - chunked or not - is answered from the key`() {
        val key = UUID.randomUUID().toString()
        val body = """{"text":"thank you"}"""

        val first = send(chunked(body.toByteArray()), key)
        first.statusCode() shouldBe 201
        first.body() shouldContain "thank you"

        // The same bytes under the same key are the same request, however framed.
        val chunkedReplay = send(chunked(body.toByteArray()), key)
        chunkedReplay.statusCode() shouldBe 201
        chunkedReplay.headers().firstValue(IdempotencyInterceptor.REPLAYED_HEADER).orElse(null) shouldBe "true"
        val sizedReplay = send(HttpRequest.BodyPublishers.ofString(body), key)
        sizedReplay.statusCode() shouldBe 201
        sizedReplay.headers().firstValue(IdempotencyInterceptor.REPLAYED_HEADER).orElse(null) shouldBe "true"

        controller.handlerRuns.get() shouldBe 1
    }

    /**
     * A mebibyte and a half: over the bound, and under Tomcat's default
     * `maxSwallowSize` (2 MiB), so the container drains the unread rest and
     * the client reads its `413` on the same connection rather than a reset.
     */
    @Test
    fun `a chunked body over one mebibyte is 413 - read no further than the bound, and never run`() {
        val oversized = ("{\"text\":\"" + "a".repeat(1024 * 1024 + 512 * 1024) + "\"}").toByteArray()

        val response = send(chunked(oversized), UUID.randomUUID().toString())

        response.statusCode() shouldBe 413
        response.body() shouldContain "\"code\":\"MALFORMED_REQUEST\""
        controller.handlerRuns.get() shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 0
    }

    @Test
    fun `a chunked body just inside the bound is accepted`() {
        // Exactly 1 MiB of JSON: the bound is inclusive.
        val padding = "a".repeat(1024 * 1024 - """{"text":""}""".length)
        val atTheBound = """{"text":"$padding"}""".toByteArray()
        atTheBound.size shouldBe 1024 * 1024

        send(chunked(atTheBound), UUID.randomUUID().toString()).statusCode() shouldBe 201
    }

    private fun chunked(bytes: ByteArray): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }

    private fun send(
        body: HttpRequest.BodyPublisher,
        key: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/probe/entries"))
                .header("Content-Type", "application/json")
                .header(IdempotencyInterceptor.HEADER, key)
                .header(CALLER_HEADER, ada.toString())
                .POST(body)
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @TestConfiguration
    class CallerConfiguration {
        /** Outermost, so the principal is in place whatever wraps the request afterwards. */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        fun probeCallerFilter(): OncePerRequestFilter =
            object : OncePerRequestFilter() {
                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    val caller = request.getHeader(CALLER_HEADER)
                    val identified =
                        if (caller == null) {
                            request
                        } else {
                            object : HttpServletRequestWrapper(request) {
                                override fun getUserPrincipal(): Principal = Principal { caller }
                            }
                        }
                    filterChain.doFilter(identified, response)
                }
            }
    }

    private companion object {
        const val CALLER_HEADER = "X-Probe-Caller"
    }
}
