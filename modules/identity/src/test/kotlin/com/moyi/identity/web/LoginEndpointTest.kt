package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Executors
import javax.sql.DataSource

@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
internal class LoginEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `an active user receives an RS256 access token and profile`() {
        registerAndActivate()

        val response = login(email = "ADA@Example.com", password = PASSWORD)

        response.status shouldBe 200
        response.contentType!! shouldStartWith MediaType.APPLICATION_JSON_VALUE
        response.contentAsString shouldContain "\"accessToken\":"
        response.contentAsString shouldContain "\"expiresIn\":900"
        response.contentAsString shouldContain "\"refreshToken\":"
        response.contentAsString shouldContain "\"email\":\"ada@example.com\""
        response.contentAsString shouldContain "\"status\":\"ACTIVE\""
        response.contentAsString shouldNotContain PASSWORD
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe null
    }

    @Test
    fun `a wrong password is the same generic 401 as an unknown address`() {
        registerAndActivate()

        val wrongPassword = login(password = "wrong password")
        val unknownEmail = login(email = "nobody@example.com")

        wrongPassword.status shouldBe 401
        unknownEmail.status shouldBe 401
        wrongPassword.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        unknownEmail.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        wrongPassword.contentAsString shouldBe unknownEmail.contentAsString
    }

    @Test
    fun `an unverified account cannot log in`() {
        registerAndActivate(activate = false)

        val response = login()

        response.status shouldBe 401
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
    }

    @Test
    fun `a malformed email still takes the dummy verification path`() {
        val response = login(email = "not-an-email")

        response.status shouldBe 401
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
    }

    @Test
    fun `five wrong passwords lock the account while responses stay generic`() {
        registerAndActivate()

        repeat(5) { login(password = "wrong password $it").status shouldBe 401 }

        val locked = login(password = PASSWORD)
        locked.status shouldBe 401
        locked.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        jdbc.queryForObject(
            "SELECT failed_attempts FROM credentials c JOIN users u ON u.id = c.user_id WHERE u.email = 'ada@example.com'",
            Int::class.java,
        ) shouldBe 5
        jdbc.queryForObject(
            "SELECT locked_until IS NOT NULL FROM credentials c JOIN users u ON u.id = c.user_id WHERE u.email = 'ada@example.com'",
            Boolean::class.java,
        ) shouldBe true
    }

    @Test
    fun `refresh rotates the token and reuse revokes the family`() {
        registerAndActivate()
        val firstLogin = login()
        val firstRefresh = refreshToken(firstLogin.contentAsString)

        val rotated = refresh(firstRefresh)
        rotated.status shouldBe 200
        val secondRefresh = refreshToken(rotated.contentAsString)
        secondRefresh shouldNotBe firstRefresh

        val reuse = refresh(firstRefresh)
        reuse.status shouldBe 401
        reuse.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""

        refresh(secondRefresh).status shouldBe 401
    }

    @Test
    fun `logout revokes the refresh-token family and is idempotent`() {
        registerAndActivate()
        val refreshToken = refreshToken(login().contentAsString)

        logout(refreshToken).status shouldBe 204
        refresh(refreshToken).status shouldBe 401
        logout(refreshToken).status shouldBe 204
    }

    @Test
    fun `logout all revokes every refresh token for the authenticated user`() {
        registerAndActivate()
        val loginResponse = login()
        val firstRefresh = refreshToken(loginResponse.contentAsString)
        val secondRefresh = refreshToken(refresh(firstRefresh).contentAsString)
        val accessToken = Regex("\"accessToken\":\"([^\"]+)\"").find(loginResponse.contentAsString)!!.groupValues[1]

        mockMvc
            .post("/api/v1/auth/logout-all") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            }.andReturn()
            .response.status shouldBe 204

        refresh(firstRefresh).status shouldBe 401
        refresh(secondRefresh).status shouldBe 401
    }

    @Test
    fun `concurrent refresh requests allow one winner and revoke the family on reuse`() {
        registerAndActivate()
        val firstRefresh = refreshToken(login().contentAsString)
        val executor = Executors.newFixedThreadPool(2)
        val responses =
            try {
                List(2) { executor.submit<Int> { refresh(firstRefresh).status } }.map { it.get() }
            } finally {
                executor.shutdownNow()
            }

        responses.count { it == 200 } shouldBe 1
        responses.count { it == 401 } shouldBe 1
        refresh(firstRefresh).status shouldBe 401
    }

    @Test
    fun `logout all invalidates access tokens after the timestamp boundary`() {
        registerAndActivate()
        val loginResponse = login()
        val accessToken = Regex("\"accessToken\":\"([^\"]+)\"").find(loginResponse.contentAsString)!!.groupValues[1]

        mockMvc
            .post("/api/v1/auth/logout-all") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            }.andReturn()
            .response.status shouldBe 204
        jdbc.update("UPDATE users SET tokens_invalid_before = tokens_invalid_before + interval '2 seconds'")

        mockMvc
            .get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `forgot and reset password invalidate the old password and sessions`() {
        registerAndActivate()
        val oldRefresh = refreshToken(login().contentAsString)
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'ada@example.com'", UUID::class.java)!!
        val secret = "password-reset-secret"
        jdbc.update(
            """
            INSERT INTO verification_tokens (id, user_id, purpose, token_hash, expires_at)
            VALUES (?, ?, 'PASSWORD_RESET', ?, now() + interval '1 hour')
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            VerificationSecret(secret).hash().value,
        )

        mockMvc
            .post("/api/v1/auth/forgot-password") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"email\":\"ada@example.com\"}"
            }.andReturn()
            .response.status shouldBe 202

        mockMvc
            .post("/api/v1/auth/reset-password") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"token\":\"$secret\",\"password\":\"new secure password 2026\"}"
            }.andReturn()
            .response.status shouldBe 200

        login(password = PASSWORD).status shouldBe 401
        login(password = "new secure password 2026").status shouldBe 200
        refresh(oldRefresh).status shouldBe 401
        mockMvc
            .post("/api/v1/auth/reset-password") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"token\":\"$secret\",\"password\":\"another secure password 2026\"}"
            }.andReturn()
            .response.status shouldBe 410
    }

    private fun registerAndActivate(activate: Boolean = true) {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "email": "ada@example.com",
                      "password": "$PASSWORD",
                      "displayName": "Ada",
                      "locale": "en",
                      "acceptedTermsVersion": "2026-09-01",
                      "over18": true
                    }
                    """.trimIndent()
            }.andReturn()
            .response.status shouldBe 201
        if (activate) {
            jdbc.update("UPDATE users SET email_verified_at = now(), status = 'ACTIVE'")
        }
    }

    private fun login(
        email: String = "ada@example.com",
        password: String = PASSWORD,
    ) = mockMvc
        .post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = "{\"email\":\"$email\",\"password\":\"$password\"}"
        }.andReturn()
        .response

    private fun refresh(token: String) =
        mockMvc
            .post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"refreshToken\":\"$token\"}"
            }.andReturn()
            .response

    private fun logout(token: String) =
        mockMvc
            .post("/api/v1/auth/logout") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"refreshToken\":\"$token\"}"
            }.andReturn()
            .response

    private fun refreshToken(body: String): String = Regex("\\\"refreshToken\\\":\\\"([^\\\"]+)\\\"").find(body)!!.groupValues[1]

    private companion object {
        const val PASSWORD = "correct horse battery"

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
