package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /bonds`, `GET /bonds` and `GET /bonds/{bondId}` through the real
 * filter chain against a real Postgres — the same beans `app` wires, not a
 * slice with the interesting parts stubbed.
 *
 * Users exist only as the identity port reports them ([FakeUserDirectory]),
 * which is exactly what the production code can see of them, and tokens are
 * minted by the same issuer that signs them in production.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondsEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a verified user creates a bond and gets it back with its invite`() {
        val ada = users.verified("Ada")

        val response = create(ada, body(name = "Us"))

        response.status shouldBe 201
        response.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""

        val json = response.contentAsString
        json shouldContain "\"name\":\"Us\""
        json shouldContain "\"type\":\"COUPLE\""
        json shouldContain "\"status\":\"PENDING_MEMBER\""
        json shouldContain "\"anchorTimezone\":\"Africa/Lagos\""
        json shouldContain "\"maxMembers\":2"
        json shouldContain "\"strictMode\":false"
        json shouldContain "\"displayName\":\"Ada\""
        json shouldContain "\"role\":\"OWNER\""
        json shouldContain "\"me\":{\"memberId\":\""

        val code = codeOf(json)
        code.length shouldBe InviteCode.LENGTH
        code.all { it in InviteCode.ALPHABET } shouldBe true
        json shouldContain "\"link\":\"https://moyi.test/i/$code\""

        // states.md §8: never show the other member's settings. And a user id
        // is identity's to hand out, not this module's to echo.
        json shouldNotContain "userId"
        json shouldNotContain "reminderTimezone"
        json shouldNotContain "quietHours"
        // The version travels as the ETag, not as a field a client might
        // decide to compare itself.
        json shouldNotContain "version"

        jdbc.queryForObject("SELECT reminder_timezone FROM bond_members", String::class.java) shouldBe "Africa/Lagos"
    }

    @Test
    fun `the reminder zone is the client's when it sends one`() {
        val ada = users.verified("Ada")

        create(ada, body(reminderTimezone = "\"Europe/London\"")).status shouldBe 201

        jdbc.queryForObject("SELECT reminder_timezone FROM bond_members", String::class.java) shouldBe "Europe/London"
    }

    @Test
    fun `a reveal time is echoed as HH mm, and anything else is 422`() {
        val ada = users.verified("Ada")

        val accepted = create(ada, body(revealTimeLocal = "\"21:00\""))
        accepted.status shouldBe 201
        accepted.contentAsString shouldContain "\"revealTimeLocal\":\"21:00\""

        create(ada, body(revealTimeLocal = "\"9pm\"")).status shouldBe 422
        create(ada, body(revealTimeLocal = "\"24:00\"")).status shouldBe 422
    }

    @Test
    fun `an unverified user is 403 EMAIL_NOT_VERIFIED, and nothing is written`() {
        // FR-002: an unverified account may sign in and may not create a bond.
        val bob = users.unverified("Bob")

        val response = create(bob, body())

        response.status shouldBe 403
        response.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
        jdbc.queryForObject("SELECT count(*) FROM bonds", Int::class.java) shouldBe 0
    }

    @Test
    fun `a token for a user identity has never heard of is refused the same way`() {
        // The token is well-formed and signed by us; the subject names nobody.
        // Not "verified", so the same 403 — and no bond.
        val response = create(UUID.randomUUID(), body())

        response.status shouldBe 403
        response.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
        jdbc.queryForObject("SELECT count(*) FROM bonds", Int::class.java) shouldBe 0
    }

    @Test
    fun `the fourth open bond is 409 BOND_LIMIT_REACHED, and an archived one does not count`() {
        val ada = users.verified("Ada")
        repeat(3) { index -> create(ada, body(name = "Bond $index")).status shouldBe 201 }

        val fourth = create(ada, body(name = "One too many"))
        fourth.status shouldBe 409
        fourth.contentAsString shouldContain "\"code\":\"BOND_LIMIT_REACHED\""

        // FR-025 counts what a person is still in, not what they have ever had.
        jdbc.update("UPDATE bonds SET status = 'ARCHIVED', archived_at = now() WHERE name = 'Bond 0'")
        create(ada, body(name = "Room again")).status shouldBe 201
    }

    @Test
    fun `a fixed-offset, unknown or blank zone is 422 naming the field, without echoing it`() {
        val ada = users.verified("Ada")

        for (zone in listOf("UTC", "Etc/GMT+3", "Mars/Olympus", "")) {
            val response = create(ada, body(anchorTimezone = "\"$zone\""))

            response.status shouldBe 422
            response.contentAsString shouldContain "\"field\":\"anchorTimezone\""
            // Doc 18 §5: the failure response never echoes the input.
            if (zone.isNotEmpty()) response.contentAsString shouldNotContain zone
        }
    }

    @Test
    fun `an unknown type is 422 on type, a long name 422 on name, and unparseable JSON is 400`() {
        val ada = users.verified("Ada")

        val type = create(ada, body(type = "\"THROUPLE\""))
        type.status shouldBe 422
        type.contentAsString shouldContain "\"field\":\"type\""
        type.contentAsString shouldNotContain "THROUPLE"

        val name = create(ada, body(name = "x".repeat(61)))
        name.status shouldBe 422
        name.contentAsString shouldContain "\"field\":\"name\""

        val garbage =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}")
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"name\": "
                }.andReturn()
                .response
        garbage.status shouldBe 400
        garbage.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
    }

    @Test
    fun `no token is 401 in the problem shape`() {
        val response = mockMvc.get("/api/v1/bonds").andReturn().response

        response.status shouldBe 401
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
    }

    // ---- helpers ------------------------------------------------------------

    private fun create(
        userId: UUID,
        json: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = json
            }.andReturn()
            .response

    private fun body(
        name: String = "Us",
        type: String = "\"COUPLE\"",
        anchorTimezone: String = "\"Africa/Lagos\"",
        revealTimeLocal: String = "null",
        reminderTimezone: String = "null",
    ): String =
        """{"name":"$name","type":$type,"anchorTimezone":$anchorTimezone,""" +
            """"revealTimeLocal":$revealTimeLocal,"reminderTimezone":$reminderTimezone}"""

    private fun codeOf(json: String): String = Regex(""""code":"([^"]+)"""").find(json)!!.groupValues[1]
}
