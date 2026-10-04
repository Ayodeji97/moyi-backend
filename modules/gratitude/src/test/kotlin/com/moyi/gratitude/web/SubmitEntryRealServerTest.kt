package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
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
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * A chunked `POST /bonds/{bondId}/entries` against a **real** Tomcat, behind
 * the real bearer chain.
 *
 * `common:web`'s `IdempotencyRealServerTest` proves a body with no
 * `Content-Length` works — on a probe controller, with a header standing in
 * for the caller. That leaves the real route unproven in the two places it
 * differs: Spring Security's filter chain wraps the request *outside* the
 * idempotency filter's own wrapper, and the body the interceptor buffered has
 * to be read again by `@Valid @RequestBody SubmitEntryRequest`. MockMvc cannot
 * send a chunked request, so every other test of this endpoint declares a
 * length.
 *
 * `java.net.http.HttpClient` with `BodyPublishers.ofInputStream` on HTTP/1.1
 * has no length to declare. That is not taken on trust here:
 * [FramingConfiguration] records what the server was actually sent.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SubmitEntryRealServerTest.FramingConfiguration::class)
internal class SubmitEntryRealServerTest(
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val framing: RecordedFraming,
    @LocalServerPort private val port: Int,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        framing.seen.clear()
    }

    @Test
    fun `a chunked POST to the real entries route is 201, stored, and replayed from its key`() {
        val ada = users.verified("Ada")
        val created = post("/api/v1/bonds", ada, sized("""{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""))
        created.statusCode() shouldBe 201
        val bondId = Regex(""""id":"([^"]+)"""").find(created.body())!!.groupValues[1]
        val key = UUID.randomUUID().toString()
        val body = """{"text":"thank you… for the coffee"}"""

        val first = post("/api/v1/bonds/$bondId/entries", ada, chunked(body), key)

        first.statusCode() shouldBe 201
        first.body() shouldContain "\"text\":\"thank you… for the coffee\""
        // What the server was sent: no declared length, and a chunked body.
        framing.seen.last() shouldBe Framing(contentLength = -1, transferEncoding = "chunked")
        jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe "thank you… for the coffee"

        // The same bytes with a declared length are the same request.
        val replay = post("/api/v1/bonds/$bondId/entries", ada, sized(body), key)
        replay.statusCode() shouldBe 201
        replay.headers().firstValue(IdempotencyInterceptor.REPLAYED_HEADER).orElse(null) shouldBe "true"
        replay.body() shouldBe first.body()
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 1
    }

    private fun chunked(body: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body.toByteArray()) }

    private fun sized(body: String): HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofString(body)

    private fun post(
        path: String,
        caller: UUID,
        body: HttpRequest.BodyPublisher,
        key: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
        key?.let { request.header(IdempotencyInterceptor.HEADER, it) }
        return client.send(request.POST(body).build(), HttpResponse.BodyHandlers.ofString())
    }

    data class Framing(
        val contentLength: Long,
        val transferEncoding: String?,
    )

    class RecordedFraming {
        val seen = CopyOnWriteArrayList<Framing>()
    }

    /** Records how each `POST …/entries` was framed, before anything else sees the request. */
    @TestConfiguration
    class FramingConfiguration {
        @Bean
        fun recordedFraming(): RecordedFraming = RecordedFraming()

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        fun framingRecorder(recorded: RecordedFraming): OncePerRequestFilter =
            object : OncePerRequestFilter() {
                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    if (request.requestURI.endsWith("/entries")) {
                        recorded.seen += Framing(request.contentLengthLong, request.getHeader(HttpHeaders.TRANSFER_ENCODING))
                    }
                    filterChain.doFilter(request, response)
                }
            }
    }
}
