package com.moyi.identity.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.ClientContext
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.service.AuthenticatedUserMissingException
import com.moyi.identity.service.LegalConsent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-103 and FR-011 (issue #128, ADR-0056): which Terms and Privacy Policy are
 * current, which ones a person accepted and when, and how they accept a new one.
 *
 * The versions are this context's configuration (`moyi.legal.*` in the test
 * `application.yml`): terms `terms-t3`, privacy `privacy-t2`, summary `summary-t1`.
 * Different from each other, and from anything a client sends, so a test can
 * tell which one was recorded.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
internal class LegalEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val legalConsent: LegalConsent,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
    }

    // ---- GET /meta/legal ----

    @Test
    fun `the current documents are public, and say their version and where to read them`() {
        val response = mockMvc.get("/api/v1/meta/legal").andReturn().response

        response.status shouldBe 200
        val body = response.body()
        body["terms"]["version"].asString() shouldBe "terms-t3"
        body["terms"]["url"].asString() shouldBe "https://moyi.test/terms"
        body["privacy"]["version"].asString() shouldBe "privacy-t2"
        body["privacy"]["url"].asString() shouldBe "https://moyi.test/privacy"
        body["dataProcessingSummary"]["version"].asString() shouldBe "summary-t1"
        body["dataProcessingSummary"]["url"].asString() shouldBe "https://moyi.test/data-processing"
        body.propertyNames().toList() shouldBe listOf("terms", "privacy", "dataProcessingSummary")
    }

    @Test
    fun `the answer may be kept by any cache, and is revalidated by its tag`() {
        val first = mockMvc.get("/api/v1/meta/legal").andReturn().response
        first.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("public, no-cache")
        first.getHeader(HttpHeaders.PRAGMA) shouldBe null
        val tag = first.getHeader(HttpHeaders.ETAG).shouldNotBeNull()

        val again =
            mockMvc
                .get("/api/v1/meta/legal") { header(HttpHeaders.IF_NONE_MATCH, tag) }
                .andReturn()
                .response
        again.status shouldBe 304
        again.contentAsString shouldBe ""
        again.getHeader(HttpHeaders.ETAG) shouldBe tag

        val stale =
            mockMvc
                .get("/api/v1/meta/legal") { header(HttpHeaders.IF_NONE_MATCH, "\"${"0".repeat(TAG_LENGTH)}\"") }
                .andReturn()
                .response
        stale.status shouldBe 200
    }

    @Test
    fun `a token is not needed, and one that is not a token is still refused as the API refuses it`() {
        mockMvc
            .get("/api/v1/meta/legal")
            .andReturn()
            .response.status shouldBe 200
        mockMvc
            .get("/api/v1/meta/legal") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(UUID.randomUUID()).token}") }
            .andReturn()
            .response.status shouldBe 401
        // Only GET is opened: nothing else on the path is public.
        mockMvc
            .post("/api/v1/meta/legal")
            .andReturn()
            .response.status shouldBe 401
    }

    // ---- registration records the server's versions ----

    @Test
    fun `registration records the configured versions, never the one the client sent`() {
        register(acceptedTermsVersion = "\"1999-01-01\"")

        jdbc.queryForList("SELECT document || '=' || version FROM consent_records ORDER BY document", String::class.java) shouldBe
            listOf("AGE_18=terms-t3", "PRIVACY=privacy-t2", "TERMS=terms-t3")
    }

    @Test
    fun `registration no longer needs the client to name a version`() {
        register(acceptedTermsVersion = null).status shouldBe 201
        jdbc.queryForObject("SELECT count(*) FROM consent_records", Int::class.java) shouldBe 3
    }

    // ---- GET /me ----

    @Test
    fun `me says which version of each document the person accepted, and when`() {
        val userId = registeredAda()
        val stored =
            jdbc.queryForObject("SELECT accepted_at::text FROM consent_records WHERE document = 'TERMS'", String::class.java)

        val legal = me(userId).body()["legalAccepted"]

        legal["terms"]["version"].asString() shouldBe "terms-t3"
        legal["privacy"]["version"].asString() shouldBe "privacy-t2"
        legal["terms"]["acceptedAt"].asString().isNotBlank() shouldBe true
        stored.shouldNotBeNull()
        legal.propertyNames().toList() shouldBe listOf("terms", "privacy")
    }

    @Test
    fun `a person with no consent recorded for a document is told null, not a version they never accepted`() {
        val userId = registeredAda()
        jdbc.update("DELETE FROM consent_records WHERE document = 'PRIVACY'")

        val legal = me(userId).body()["legalAccepted"]

        legal["terms"]["version"].asString() shouldBe "terms-t3"
        legal["privacy"].isNull shouldBe true
    }

    @Test
    fun `when an older version is the latest row, me reports the newest acceptance`() {
        val userId = registeredAda()
        // What a person registered before a version change looks like.
        jdbc.update(
            "UPDATE consent_records SET version = 'terms-t2', accepted_at = accepted_at - interval '30 days' WHERE document = 'TERMS'",
        )
        jdbc.update(
            "INSERT INTO consent_records (id, user_id, document, version, accepted_at) " +
                "VALUES (?, ?, 'TERMS', 'terms-t1', now() - interval '60 days')",
            UUID.randomUUID(),
            userId,
        )

        me(userId).body()["legalAccepted"]["terms"]["version"].asString() shouldBe "terms-t2"
    }

    @Test
    fun `when the current version was accepted, me reports it even if a later row names another`() {
        // Configuration rolled back to a version the person had already accepted:
        // the client compares, sees the current one, and does not ask again for ever.
        val userId = registeredAda()
        jdbc.update(
            "INSERT INTO consent_records (id, user_id, document, version, accepted_at) " +
                "VALUES (?, ?, 'TERMS', 'terms-t4', now() + interval '1 day')",
            UUID.randomUUID(),
            userId,
        )

        me(userId).body()["legalAccepted"]["terms"]["version"].asString() shouldBe "terms-t3"
    }

    // ---- POST /me/legal-consent ----

    @Test
    fun `re-consent records the current versions for a person whose acceptance lags`() {
        val userId = registeredAda()
        jdbc.update("UPDATE consent_records SET version = 'old' WHERE document IN ('TERMS', 'PRIVACY')")

        val response = consent(userId)

        response.status shouldBe 204
        response.contentAsString shouldBe ""
        jdbc.queryForList(
            "SELECT document || '=' || version FROM consent_records WHERE document <> 'AGE_18' ORDER BY document, version",
            String::class.java,
        ) shouldBe listOf("PRIVACY=old", "PRIVACY=privacy-t2", "TERMS=old", "TERMS=terms-t3")
        // The new rows carry the same corroborating evidence registration's do (ADR-0023).
        jdbc.queryForObject(
            "SELECT count(*) FROM consent_records " +
                "WHERE document <> 'AGE_18' AND version IN ('terms-t3', 'privacy-t2') AND ip_hash IS NOT NULL",
            Int::class.java,
        ) shouldBe 2
        val legal = me(userId).body()["legalAccepted"]
        legal["terms"]["version"].asString() shouldBe "terms-t3"
        legal["privacy"]["version"].asString() shouldBe "privacy-t2"
    }

    @Test
    fun `re-consent is idempotent, and keeps the moment the version was first accepted`() {
        val userId = registeredAda()
        val before = acceptedAts(userId)

        consent(userId).status shouldBe 204
        consent(userId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM consent_records", Int::class.java) shouldBe 3
        acceptedAts(userId) shouldBe before
    }

    @Test
    fun `re-consent ignores any body, so a client cannot choose the version recorded`() {
        val userId = registeredAda()
        jdbc.update("DELETE FROM consent_records WHERE document IN ('TERMS', 'PRIVACY')")

        mockMvc
            .post("/api/v1/me/legal-consent") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"terms":{"version":"chosen-by-client"},"privacy":{"version":"chosen-by-client"}}"""
            }.andReturn()
            .response.status shouldBe 204

        jdbc.queryForList("SELECT version FROM consent_records WHERE document <> 'AGE_18' ORDER BY version", String::class.java) shouldBe
            listOf("privacy-t2", "terms-t3")
    }

    @Test
    fun `re-consent without a token is 401 and records nothing`() {
        registeredAda()
        jdbc.update("DELETE FROM consent_records")

        mockMvc
            .post("/api/v1/me/legal-consent")
            .andReturn()
            .response.status shouldBe 401

        jdbc.queryForObject("SELECT count(*) FROM consent_records", Int::class.java) shouldBe 0
    }

    @Test
    fun `re-consent for a token whose user no longer exists is 401`() {
        consent(UUID.randomUUID()).status shouldBe 401
    }

    @Test
    fun `re-consent for a user deleted after the token was checked is the 401 me gives, not a foreign-key 500`() {
        // Over HTTP the token's verifier refuses a user who does not exist, so the
        // service's own check is reached only by a deletion between the two; asked here.
        shouldThrow<AuthenticatedUserMissingException> {
            legalConsent.acceptCurrent(UserId(UUID.randomUUID()), ClientContext(addressHash = "address-hash", userAgentHash = null))
        }
        jdbc.queryForObject("SELECT count(*) FROM consent_records", Int::class.java) shouldBe 0
    }

    private fun acceptedAts(userId: UUID): List<String?> =
        jdbc.queryForList(
            "SELECT document || '@' || accepted_at::text FROM consent_records WHERE user_id = ? ORDER BY document",
            String::class.java,
            userId,
        )

    private fun consent(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/me/legal-consent") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun me(userId: UUID): MockHttpServletResponse {
        val response =
            mockMvc
                .get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
                .andReturn()
                .response
        response.status shouldBe 200
        return response
    }

    private fun registeredAda(): UUID {
        register(acceptedTermsVersion = "\"2026-09-01\"").status shouldBe 201
        return jdbc.queryForObject("SELECT id FROM users WHERE email = 'ada@example.com'", UUID::class.java)!!
    }

    /** [acceptedTermsVersion] is raw JSON, or `null` to leave the field out. */
    private fun register(acceptedTermsVersion: String?): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"ada@example.com","password":"correct horse battery","displayName":"Ada","locale":"en",""" +
                    (if (acceptedTermsVersion == null) "" else """"acceptedTermsVersion":$acceptedTermsVersion,""") +
                    """"over18":true}"""
            }.andReturn()
            .response

    private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsByteArray)

    private companion object {
        const val TAG_LENGTH = 64

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
