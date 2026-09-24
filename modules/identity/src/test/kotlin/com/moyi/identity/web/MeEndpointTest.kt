package com.moyi.identity.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * The first endpoint behind the filter chain, against the real context and a
 * real Postgres: the revocation port is the identity adapter reading
 * `users.tokens_invalid_before`, not a stub.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
internal class MeEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `a signed-in user sees their own profile and nothing internal`() {
        val userId = registerAda()

        val response = me(tokens.issue(userId).token)

        response.status shouldBe 200
        response.contentAsString shouldContain "\"id\":\"$userId\""
        response.contentAsString shouldContain "\"email\":\"ada@example.com\""
        response.contentAsString shouldContain "\"displayName\":\"Ada\""
        response.contentAsString shouldContain "\"emailVerified\":false"
        response.contentAsString shouldContain "\"status\":\"PENDING_VERIFICATION\""
        response.contentAsString shouldNotContain "tokensInvalidBefore"
        response.contentAsString shouldNotContain "createdAt"
    }

    @Test
    fun `no token is 401 in the problem shape`() {
        val response = mockMvc.get("/api/v1/me").andReturn().response

        response.status shouldBe 401
        response.contentType!! shouldStartWith MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
    }

    @Test
    fun `bumping tokens_invalid_before kills every outstanding token`() {
        // Doc 09 §3: this is what makes "revoke all sessions" include the
        // access tokens still in flight. The column is set the way logout-all
        // (slice E3) will set it; a token issued a moment earlier dies.
        val userId = registerAda()
        val token = tokens.issue(userId).token
        me(token).status shouldBe 200

        jdbc.update("UPDATE users SET tokens_invalid_before = now() + interval '2 seconds' WHERE id = ?", userId)

        val response = me(token)
        response.status shouldBe 401
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE)!! shouldContain "invalid_token"
    }

    @Test
    fun `a valid token for a user who does not exist is 401`() {
        // Signed by us, well-formed, and for nobody. The revocation port
        // answers "no such user" and the verifier refuses it, so an erased
        // account's tokens do not survive it by fifteen minutes.
        me(tokens.issue(UUID.randomUUID()).token).status shouldBe 401
    }

    private fun me(token: String): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
            .andReturn()
            .response

    private fun registerAda(): UUID {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "email": "ada@example.com",
                      "password": "correct horse battery",
                      "displayName": "Ada",
                      "locale": "en",
                      "acceptedTermsVersion": "2026-09-01",
                      "over18": true
                    }
                    """.trimIndent()
            }.andReturn()
            .response.status shouldBe 201
        return jdbc.queryForObject("SELECT id FROM users WHERE email = 'ada@example.com'", UUID::class.java)!!
    }

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
