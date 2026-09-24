package com.moyi.identity.web

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-007 end to end: a session is a live refresh-token family with the device
 * that started it; the caller sees theirs, marks the current one, and can end
 * any of them — and only theirs (doc 06 §2: not yours is 404, never 403).
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
internal class SessionsEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: JsonMapper,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE refresh_tokens, devices, verification_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `a sign-in records its device, and the sessions list shows it as the current one`() {
        register("ada@example.com")
        val phone = login("ada@example.com", device = PIXEL)

        val sessions = sessionsOf(phone.accessToken)

        sessions.size() shouldBe 1
        val session = sessions[0]
        session["current"].asBoolean() shouldBe true
        session["device"]["platform"].asText() shouldBe "ANDROID"
        session["device"]["appVersion"].asText() shouldBe "1.0.3"
        session["device"]["osVersion"].asText() shouldBe "16"
        session["createdAt"].asText() shouldBe clock.instant().toString()
        session["lastSeenAt"].asText() shouldBe clock.instant().toString()
        UUID.fromString(session["id"].asText()) shouldNotBe null

        jdbc.queryForObject("SELECT platform || '|' || app_version || '|' || os_version FROM devices", String::class.java) shouldBe
            "ANDROID|1.0.3|16"
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE device_id IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `two sign-ins are two sessions, most recently seen first, and nobody else's are listed`() {
        register("ada@example.com")
        register("grace@example.com")
        val phone = login("ada@example.com", device = PIXEL)
        clock.advance(Duration.ofMinutes(1))
        val watch = login("ada@example.com", device = WATCH)
        login("grace@example.com", device = PIXEL)

        val sessions = sessionsOf(watch.accessToken)

        sessions.size() shouldBe 2
        sessions[0]["device"]["platform"].asText() shouldBe "WEAR"
        sessions[0]["current"].asBoolean() shouldBe true
        sessions[1]["device"]["platform"].asText() shouldBe "ANDROID"
        sessions[1]["current"].asBoolean() shouldBe false
        sessionsOf(phone.accessToken)[0]["current"].asBoolean() shouldBe false
    }

    @Test
    fun `a refresh moves the session's last seen, and the new access token still marks it current`() {
        register("ada@example.com")
        val phone = login("ada@example.com", device = PIXEL)
        clock.advance(Duration.ofHours(2))

        val rotated = refresh(phone.refreshToken)

        rotated.status shouldBe 200
        val sessions = sessionsOf(accessTokenIn(rotated.contentAsString))
        sessions.size() shouldBe 1
        sessions[0]["current"].asBoolean() shouldBe true
        sessions[0]["lastSeenAt"].asText() shouldBe clock.instant().toString()
        sessions[0]["createdAt"].asText() shouldBe clock.instant().minus(Duration.ofHours(2)).toString()
    }

    @Test
    fun `ending another session kills its refresh token and leaves the caller's alone`() {
        register("ada@example.com")
        val phone = login("ada@example.com", device = PIXEL)
        val watch = login("ada@example.com", device = WATCH)
        val phoneSessionId = sessionsOf(watch.accessToken).first { !it["current"].asBoolean() }["id"].asText()

        revoke(watch.accessToken, phoneSessionId).status shouldBe 204

        refresh(phone.refreshToken).let {
            it.status shouldBe 401
            it.contentAsString shouldContain "\"code\":\"REFRESH_TOKEN_INVALID\""
        }
        refresh(watch.refreshToken).status shouldBe 200
        sessionsOf(watch.accessToken).size() shouldBe 1
    }

    @Test
    fun `ending the current session is a logout, and the list is then empty`() {
        register("ada@example.com")
        val phone = login("ada@example.com", device = PIXEL)
        val current = sessionsOf(phone.accessToken)[0]["id"].asText()

        revoke(phone.accessToken, current).status shouldBe 204

        refresh(phone.refreshToken).status shouldBe 401
        // The access token lives out its fifteen minutes (doc 09 §3); it just sees nothing.
        sessionsOf(phone.accessToken).size() shouldBe 0
    }

    @Test
    fun `a session that is not yours, does not exist, or is already over is 404 — never 403`() {
        register("ada@example.com")
        register("grace@example.com")
        val ada = login("ada@example.com", device = PIXEL)
        val grace = login("grace@example.com", device = PIXEL)
        val gracesSession = sessionsOf(grace.accessToken)[0]["id"].asText()

        val notMine = revoke(ada.accessToken, gracesSession)
        notMine.status shouldBe 404
        notMine.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        // `instance` is the path the caller asked for and so carries the id
        // they typed; the sentence must not repeat it.
        json.readTree(notMine.contentAsString)["detail"].asText() shouldNotContain gracesSession
        revoke(ada.accessToken, UUID.randomUUID().toString()).status shouldBe 404
        revoke(ada.accessToken, "not-a-uuid").status shouldBe 404

        val mine = sessionsOf(ada.accessToken)[0]["id"].asText()
        revoke(ada.accessToken, mine).status shouldBe 204
        revoke(ada.accessToken, mine).status shouldBe 404
        // Grace's session was never touched.
        sessionsOf(grace.accessToken).size() shouldBe 1
    }

    @Test
    fun `the device is optional, and a platform outside the three is a validation failure`() {
        register("ada@example.com")

        val bare = login("ada@example.com", device = null)
        sessionsOf(bare.accessToken)[0]["device"].isNull shouldBe true

        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"ada@example.com","password":"$PASSWORD","device":$BLACKBERRY}"""
                }.andReturn()
                .response
        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"device.platform\""
    }

    @Test
    fun `sessions need a bearer`() {
        mockMvc
            .get("/api/v1/auth/sessions")
            .andReturn()
            .response.status shouldBe 401
        mockMvc
            .delete("/api/v1/auth/sessions/${UUID.randomUUID()}")
            .andReturn()
            .response.status shouldBe 401
    }

    // ---- helpers -----------------------------------------------------------

    private class Signed(
        val accessToken: String,
        val refreshToken: String,
    )

    private fun register(email: String) {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
    }

    private fun login(
        email: String,
        device: String?,
    ): Signed {
        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"$email","password":"$PASSWORD"${device?.let { ""","device":$it""" } ?: ""}}"""
                }.andReturn()
                .response
        response.status shouldBe 200
        val body = json.readTree(response.contentAsString)
        return Signed(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$token"}"""
            }.andReturn()
            .response

    private fun sessionsOf(accessToken: String): JsonNode {
        val response =
            mockMvc
                .get(
                    "/api/v1/auth/sessions",
                ) { header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }
                .andReturn()
                .response
        response.status shouldBe 200
        return json.readTree(response.contentAsString)["sessions"]
    }

    private fun revoke(
        accessToken: String,
        sessionId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/auth/sessions/$sessionId") { header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }
            .andReturn()
            .response

    private fun accessTokenIn(body: String): String = json.readTree(body)["accessToken"].asText()

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val PIXEL = """{"platform":"ANDROID","appVersion":"1.0.3","osVersion":"16"}"""
        const val WATCH = """{"platform":"WEAR","appVersion":"1.0.3","osVersion":"5.1"}"""
        const val BLACKBERRY = """{"platform":"BLACKBERRY","appVersion":"1","osVersion":"7"}"""

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
