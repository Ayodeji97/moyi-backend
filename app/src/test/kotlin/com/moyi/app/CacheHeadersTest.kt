package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **What a client or a proxy is told it may keep**, at the composition root
 * with the configuration that ships.
 *
 * Every response of this API is one person's: their tokens, their bond, what
 * somebody wrote to them. None of them sets a `Cache-Control`, and all of
 * them are `no-store` because Spring Security's header writer adds
 * `no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache` and
 * `Expires: 0` to a response that has none of the three. **Nothing in this
 * codebase asks for that; it is a default**, and until this test nothing
 * asserted it: `headers { cacheControl { disable() } }` in
 * `SecurityConfiguration` was tried by a reviewer and every test stayed
 * green.
 *
 * The archive's two reads are the exception, and the reason the default now
 * needs holding: they send `Cache-Control: private, no-cache` beside an
 * `ETag`, which works *because* the writer leaves alone a response that has
 * a `Cache-Control` already. So both halves are asked here: the exception is
 * exactly those two routes' `200` and `304`, and everything else, their own
 * refusals included, is still never to be stored.
 *
 * In `app`, not in a module: `SecurityConfiguration` is one class, but which
 * filter chain a context ends up with is the composition's, and it is this
 * one that is deployed.
 */
@SpringBootTest(
    properties = [
        // Each account registers from its own address: registration is three an hour per address, and
        // other classes in this JVM share the Redis.
        "moyi.security.client-address.trusted-proxies=127.0.0.0/8,::1/128",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CacheHeadersTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    @Test
    @Suppress("LongMethod") // One couple, made once, and every kind of response asked of it.
    fun `every response is no-store, but for the archive's 200 and 304, which are private no-cache and nothing else`() {
        val run = UUID.randomUUID().toString().take(RUN_ID_LENGTH)

        // An auth route: the response that carries the tokens.
        val (ada, adaLogin) = account("ada-$run", "203.0.113.211")
        neverStored("POST /auth/login 200", adaLogin, 200)
        val (bea, _) = account("bea-$run", "203.0.113.212")
        val (eve, _) = account("eve-$run", "203.0.113.213")

        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $ada")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        neverStored("POST /bonds 201", created, 201)
        val bond = created.body()["id"].asString()
        val code = created.body()["invite"]["code"].asString()
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer $bea") }
            .andReturn()
            .response.status shouldBe 200
        listOf(ada to "kept~by~ada", bea to "kept~by~bea").forEach { (who, words) ->
            mockMvc
                .post("/api/v1/bonds/$bond/entries") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $who")
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"$words"}"""
                }.andReturn()
                .response.status shouldBe 201
        }

        // Ordinary authenticated reads.
        neverStored("GET /me", get("/me", ada), 200)
        neverStored("GET /bonds/{id}", get("/bonds/$bond", ada), 200)
        val today = get("/bonds/$bond/today", ada)
        neverStored("GET /today", today, 200)
        val date = today.body()["bondDay"]["date"].asString()
        neverStored("GET /streak", get("/bonds/$bond/streak", ada), 200)

        // The archive: the two responses a client may keep, and must ask about before it uses them again.
        val days = "/bonds/$bond/days"
        val day = "/bonds/$bond/days/$date"
        listOf(days, day).forEach { path ->
            val whole = get(path, ada)
            keptAndRevalidated("GET $path 200", whole, 200)
            val tag = whole.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            keptAndRevalidated("GET $path 304", get(path, ada, mapOf(HttpHeaders.IF_NONE_MATCH to tag)), 304)
        }

        // And every way the same two routes refuse.
        val neverWritten = LocalDate.parse(date).minusDays(1)
        neverStored("the day, a date with nothing on it", get("/bonds/$bond/days/$neverWritten", ada), 404)
        neverStored("the day, not a date", get("/bonds/$bond/days/yesterday", ada), 404)
        neverStored("the day, a stranger", get(day, eve), 404)
        neverStored("the feed, a stranger", get(days, eve), 404)
        neverStored("the feed, no such bond", get("/bonds/${UUID.randomUUID()}/days", ada), 404)
        neverStored("the day, no token", get(day, null), 401)
        neverStored("the feed, no token", get(days, null), 401)
        neverStored("the feed, a token that is not one", get(days, "not.a.token"), 401)
        neverStored("the feed, a limit that cannot be read", get("$days?limit=0", ada), 422)
        neverStored("the feed, an Accept JSON cannot answer", get(days, ada, mapOf(HttpHeaders.ACCEPT to "text/plain")), 406)
        neverStored("the day, an Accept JSON cannot answer", get(day, ada, mapOf(HttpHeaders.ACCEPT to "text/plain")), 406)
        // A stale tag is the whole response again, and that one may be kept.
        keptAndRevalidated("the day, a stale tag", get(day, ada, mapOf(HttpHeaders.IF_NONE_MATCH to "\"${"0".repeat(TAG_LENGTH)}\"")), 200)
    }

    private fun neverStored(
        what: String,
        response: MockHttpServletResponse,
        status: Int,
    ) = withClue(what) {
        response.status shouldBe status
        response.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("no-cache, no-store, max-age=0, must-revalidate")
        response.getHeaders(HttpHeaders.PRAGMA) shouldBe listOf("no-cache")
        response.getHeaders(HttpHeaders.EXPIRES) shouldBe listOf("0")
    }

    private fun keptAndRevalidated(
        what: String,
        response: MockHttpServletResponse,
        status: Int,
    ) = withClue(what) {
        response.status shouldBe status
        response.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("private, no-cache")
        response.getHeader(HttpHeaders.PRAGMA).shouldBeNull()
        response.getHeader(HttpHeaders.EXPIRES).shouldBeNull()
        response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun get(
        path: String,
        token: String?,
        headers: Map<String, String> = emptyMap(),
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1$path") {
                if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                headers.forEach { (name, value) -> header(name, value) }
            }.andReturn()
            .response

    /** Registers, verifies in the database, signs in: the access token, and the response it came in. */
    private fun account(
        name: String,
        address: String,
    ): Pair<String, MockHttpServletResponse> {
        val email = "$name@example.com"
        mockMvc
            .post("/api/v1/auth/register") {
                header("X-Forwarded-For", address)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"${name.take(3)}","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE email = ?::citext", email) shouldBe 1
        val login =
            mockMvc
                .post("/api/v1/auth/login") {
                    header("X-Forwarded-For", address)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"$email","password":"$PASSWORD"}"""
                }.andReturn()
                .response
        login.status shouldBe 200
        return login.body()["accessToken"].asString() to login
    }

    private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsByteArray)

    private companion object {
        const val PASSWORD = "eleven~amber~lanterns~at~dusk"
        const val RUN_ID_LENGTH = 8
        const val TAG_LENGTH = 64
    }
}
