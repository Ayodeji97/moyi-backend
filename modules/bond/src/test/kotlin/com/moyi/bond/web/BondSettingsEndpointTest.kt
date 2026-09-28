package com.moyi.bond.web

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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * `PATCH /bonds/{bondId}` — every status the design's §5.2 row #10 lists.
 *
 * The subject is doc 06 §1's one sentence: *"`ETag` on mutable resources;
 * `If-Match` required for updates to Bond settings."* Which makes the
 * interesting tests here not the happy path but the four ways a condition can be
 * wrong, and the order in which the refusals are decided.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondSettingsEndpointTest(
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
    fun `a patch with the current ETag is 200, and returns the new one`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""

        val response = patch(ada, bondId, """{"name":"Us two"}""", "\"0\"")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"name\":\"Us two\""
        // The NEW version, not the one that was sent: a client told to keep a
        // stale value would be refused on its next write (the lesson from the
        // review of PR #38, where accept documented an ETag it never sent).
        response.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
        getBond(ada, bondId).getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
    }

    @Test
    fun `no If-Match is 428 PRECONDITION_REQUIRED and changes nothing`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = patch(ada, bondId, """{"name":"Us two"}""", ifMatch = null)

        response.status shouldBe 428
        response.contentAsString shouldContain "\"code\":\"PRECONDITION_REQUIRED\""
        getBond(ada, bondId).contentAsString shouldContain "\"name\":\"Us\""
    }

    @Test
    fun `a stale If-Match is 412 and changes nothing`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        patch(ada, bondId, """{"name":"First"}""", "\"0\"").status shouldBe 200

        val response = patch(ada, bondId, """{"name":"Second"}""", "\"0\"")

        response.status shouldBe 412
        response.contentAsString shouldContain "\"code\":\"PRECONDITION_FAILED\""
        getBond(ada, bondId).contentAsString shouldContain "\"name\":\"First\""
    }

    @Test
    fun `a star If-Match is 428, and a weak one is 412`() {
        // ADR-0029's two deviations from RFC 9110, asserted so that they are
        // decisions rather than accidents.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, """{"name":"Us two"}""", "*").status shouldBe 428
        patch(ada, bondId, """{"name":"Us two"}""", "W/\"0\"").status shouldBe 412
        getBond(ada, bondId).contentAsString shouldContain "\"name\":\"Us\""
    }

    @Test
    fun `a list of ETags matches if one of them is current`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, """{"name":"Us two"}""", "\"7\", \"0\"").status shouldBe 200
    }

    @Test
    fun `the guard runs before the precondition, so a non-member never learns the bond is real`() {
        // The order matters: a 428 or a 412 to a stranger would confirm that
        // the bond exists, which is precisely T-02's oracle — and it would go
        // to the one caller who must not have it.
        val ada = users.verified("Ada")
        val eve = users.verified("Eve")
        val bondId = bondIdOf(createBond(ada))

        patch(eve, bondId, """{"name":"Mine"}""", ifMatch = null).status shouldBe 404
        patch(eve, bondId, """{"name":"Mine"}""", "\"0\"").status shouldBe 404
        patch(eve, bondId, """{"name":"Mine"}""", "\"99\"").status shouldBe 404

        // A malformed body is a 422 for a non-member too, and that is not an
        // oracle: Spring validates the body before the handler runs, so the
        // answer depends only on what the caller sent — a member sending the
        // same body gets the same 422, and nothing about the bond is disclosed.
        // Worth asserting rather than assuming; the first draft of this test
        // expected 404 and the reasoning above is why it does not.
        patch(eve, bondId, """{"name":""}""", "\"0\"").status shouldBe 422
        patch(ada, bondId, """{"name":""}""", "\"0\"").status shouldBe 422
    }

    @Test
    fun `patching an archived bond is 409 BOND_ARCHIVED`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        leave(ada, bondId).status shouldBe 204

        val response = patch(ada, bondId, """{"name":"Us two"}""", "\"1\"")

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `the type is patchable, and the seats do not move`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = patch(ada, bondId, """{"type":"FRIENDS"}""", "\"0\"")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"type\":\"FRIENDS\""
        response.contentAsString shouldContain "\"maxMembers\":2"
    }

    @Test
    fun `a named null clears the reveal time and an absent field is left alone`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        patch(ada, bondId, """{"revealTimeLocal":"21:00","strictMode":true}""", "\"0\"").let {
            it.status shouldBe 200
            it.contentAsString shouldContain "\"revealTimeLocal\":\"21:00\""
        }

        val cleared = patch(ada, bondId, """{"revealTimeLocal":null}""", "\"1\"")

        cleared.status shouldBe 200
        cleared.contentAsString shouldContain "\"revealTimeLocal\":null"
        // strictMode was not named, so it is untouched — the whole reason
        // `Change` exists.
        cleared.contentAsString shouldContain "\"strictMode\":true"
    }

    @Test
    fun `the anchor timezone is refused here, not ignored`() {
        // FR-027 makes it two-party and once per 30 days, which is slice B5's
        // `PATCH /bonds/{id}/timezone`. Silently dropping the field would tell
        // a client the change succeeded.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = patch(ada, bondId, """{"anchorTimezone":"Europe/London"}""", "\"0\"")

        response.status shouldBe 422
        response.contentAsString shouldContain "anchorTimezone"
        getBond(ada, bondId).contentAsString shouldContain "\"anchorTimezone\":\"Africa/Lagos\""
    }

    @Test
    fun `an empty patch is 422 rather than a version bump for nothing`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, "{}", "\"0\"").status shouldBe 422
        getBond(ada, bondId).getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
    }

    @Test
    fun `a name that is too long, blank, or the wrong type is 422 naming the field`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, """{"name":"${"x".repeat(61)}"}""", "\"0\"").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "name"
            // Doc 18 §5: a failure response does not echo the input.
            it.contentAsString shouldNotContain "xxxxx"
        }
        patch(ada, bondId, """{"name":"  "}""", "\"0\"").status shouldBe 422
        patch(ada, bondId, """{"type":"THROUPLE"}""", "\"0\"").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"field\":\"type\""
        }
        patch(ada, bondId, """{"revealTimeLocal":"9am"}""", "\"0\"").status shouldBe 422
        // And nothing was written by any of them.
        getBond(ada, bondId).getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
    }

    // ---- helpers ------------------------------------------------------------

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun patch(
        userId: UUID,
        bondId: String,
        body: String,
        ifMatch: String?,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]
}
