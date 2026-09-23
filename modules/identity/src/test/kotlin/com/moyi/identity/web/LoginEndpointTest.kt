package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.post
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

    private companion object {
        const val PASSWORD = "correct horse battery"

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
