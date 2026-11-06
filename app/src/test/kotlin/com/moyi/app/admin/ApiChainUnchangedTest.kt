package com.moyi.app.admin

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * **The API chain is untouched by switching the console on** (Review Focus 1). A second
 * `SecurityFilterChain` ordered first, a global session cookie name and an admin-scoped
 * multi-factor manager are each a way the JWT API could change without any API test
 * noticing, so this asks the API itself, over a real socket, with the console on: a bearer
 * token still works, its absence is still the same 401, no response sets a cookie, and the
 * OpenAPI document names no admin route.
 */
class ApiChainUnchangedTest(
    @LocalServerPort private val port: Int,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    @Test
    fun `a bearer token still reaches the API, its absence is the same 401, and nothing sets a cookie`() {
        val address = "198.51.100.${Random.nextInt(1, MAX_OCTET)}"
        val email = "api-${UUID.randomUUID().toString().take(RUN_ID_LENGTH)}@example.com"
        val registered =
            send(
                "POST",
                "/api/v1/auth/register",
                address,
                body =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Api","locale":"en",""" +
                        """"acceptedTermsVersion":"2026-09-01","over18":true}""",
            )
        registered.statusCode() shouldBe 201
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE email = ?::citext", email) shouldBe 1
        val login = send("POST", "/api/v1/auth/login", address, body = """{"email":"$email","password":"$PASSWORD"}""")
        login.statusCode() shouldBe 200
        val token = json.readTree(login.body())["accessToken"].asString()

        val me = send("GET", "/api/v1/me", address, token = token)
        me.statusCode() shouldBe 200

        val anonymous = send("GET", "/api/v1/me", address)
        anonymous.statusCode() shouldBe 401
        anonymous.body() shouldBe unauthenticatedProblem("/api/v1/me")

        val docs = send("GET", "/v3/api-docs", address, token = token)
        docs.statusCode() shouldBe 200
        docs.body() shouldNotContain "/admin"

        listOf(registered, login, me, anonymous, docs).forEach { response ->
            withClue(response.uri()) { response.headers().allValues("Set-Cookie").shouldBeEmpty() }
        }
    }

    private fun send(
        method: String,
        path: String,
        address: String,
        token: String? = null,
        body: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("X-Forwarded-For", address)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .apply { if (body != null) header("Content-Type", "application/json") }
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val PASSWORD = "eleven~amber~lanterns~at~dusk"
        const val RUN_ID_LENGTH = 8
        const val MAX_OCTET = 255
    }
}
