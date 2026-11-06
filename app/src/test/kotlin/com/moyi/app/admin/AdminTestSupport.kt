package com.moyi.app.admin

import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.random.Random

/**
 * The API's answer to a request without a token, byte for byte, as the API chain writes it
 * with the console switched off (spec §4). `AdminDisabledTest` holds it against the real
 * thing, `ApiChainUnchangedTest` holds the API to it with the console switched on, and
 * `AdminChainTest` holds a refused address to it. One literal, so that the three cannot
 * each agree with a different body.
 *
 * Only `instance` varies: it is the request's own path.
 */
internal fun unauthenticatedProblem(path: String): String =
    """{"detail":"Sign in to continue.","instance":"$path","status":401,"title":"Unauthenticated",""" +
        """"type":"https://api.getmoyi.app/problems/unauthenticated","code":"UNAUTHENTICATED"}"""

/** Every header, name by name, in a form two responses can be compared in. */
internal fun MockHttpServletResponse.allHeaders(): Map<String, List<String>> = headerNames.sorted().associateWith { getHeaders(it) }

/** True when the response tries to set any cookie, a session's included. */
internal fun MockHttpServletResponse.setsACookie(): Boolean = getHeader(HttpHeaders.SET_COOKIE) != null || cookies.isNotEmpty()

/**
 * A bearer token for a real, verified account: the API refuses a well-signed token whose
 * subject is nobody. Registered from a documentation address picked at random, since
 * registration is limited per address and the Redis is shared by every class in the JVM.
 */
internal fun MockMvc.bearerToken(jdbc: JdbcTemplate): String {
    val email = "chain-${UUID.randomUUID().toString().take(8)}@example.com"
    val address = "198.51.100.${Random.nextInt(1, 255)}"
    val password = "eleven~amber~lanterns~at~dusk"
    post("/api/v1/auth/register") {
        with { it.apply { remoteAddr = address } }
        contentType = MediaType.APPLICATION_JSON
        content =
            """{"email":"$email","password":"$password","displayName":"Chain","locale":"en",""" +
            """"acceptedTermsVersion":"2026-09-01","over18":true}"""
    }.andReturn().response.status shouldBe 201
    jdbc.update("UPDATE users SET email_verified_at = now() WHERE email = ?::citext", email) shouldBe 1
    val login =
        post("/api/v1/auth/login") {
            with { it.apply { remoteAddr = address } }
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn().response
    login.status shouldBe 200
    return JsonMapper
        .builder()
        .build()
        .readTree(login.contentAsByteArray)["accessToken"]
        .asString()
}
