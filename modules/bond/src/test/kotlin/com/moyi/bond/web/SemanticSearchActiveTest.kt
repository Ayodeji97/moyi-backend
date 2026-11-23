package com.moyi.bond.web

import com.moyi.bond.api.SemanticSearchAvailability
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeSemanticSearchAvailability
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import javax.sql.DataSource
import org.springframework.test.web.servlet.get as mvcGet
import org.springframework.test.web.servlet.patch as mvcPatch
import org.springframework.test.web.servlet.post as mvcPost

/**
 * `semanticSearchActive` (ADR-0070): whether the bond's entries are searched by meaning now. The
 * bond's own setting **and** [SemanticSearchAvailability], `gratitude`'s answer, played here by
 * [FakeSemanticSearchAvailability]. Neither alone makes it true, and it is the same on every route
 * that answers a bond: the list as well as the one.
 *
 * The availability changes with no write to the bond (a deploy, an `ADMIN`'s switch), so the
 * `ETag` must change with it: otherwise a client holding the old tag is answered `304` and keeps
 * a "Remember when" that no longer works, or never shows one that now does.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class SemanticSearchActiveTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired availability: SemanticSearchAvailability,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val availability = availability as FakeSemanticSearchAvailability
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    private lateinit var ada: UUID

    @BeforeEach
    fun people() {
        ada = users.verified("Ada")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        availability.reset()
    }

    @Test
    fun `setting on and search available is active, on the bond and in the list`() {
        val bond = solo(ada)
        get(ada, bond)["semanticSearchActive"].asBoolean() shouldBe true
        list(ada).single()["semanticSearchActive"].asBoolean() shouldBe true
    }

    @Test
    fun `search unavailable is not active, though the setting is on`() {
        val bond = solo(ada)
        availability.available = false
        val body = get(ada, bond)
        body["semanticSearchEnabled"].asBoolean() shouldBe true
        body["semanticSearchActive"].asBoolean() shouldBe false
        list(ada).single()["semanticSearchActive"].asBoolean() shouldBe false
    }

    @Test
    fun `setting off is not active, though search is available`() {
        val bond = solo(ada)
        turnOff(ada, bond)
        get(ada, bond)["semanticSearchActive"].asBoolean() shouldBe false
        list(ada).single()["semanticSearchActive"].asBoolean() shouldBe false
    }

    /** Each bond its own setting: the availability is asked once for the list, and one bond's setting is not another's. */
    @Test
    fun `in the list, a bond with the setting off is not active beside one with it on`() {
        val on = solo(ada)
        val off = solo(ada)
        turnOff(ada, off)
        val active = list(ada).associate { it["id"].asString() to it["semanticSearchActive"].asBoolean() }
        active shouldBe mapOf(on to true, off to false)
    }

    @Test
    fun `the answer to the PATCH that turns it off says it is no longer active`() {
        val bond = solo(ada)
        val res = patch(ada, bond, """{"semanticSearchEnabled":false}""", etag(ada, bond))
        res.status shouldBe 200
        json.readTree(res.contentAsString)["semanticSearchActive"].asBoolean() shouldBe false
    }

    @Test
    fun `search becoming unavailable changes the ETag, so If-None-Match is not answered 304`() {
        val bond = solo(ada)
        val before = etag(ada, bond)
        conditional(ada, bond, before).status shouldBe 304

        availability.available = false

        val after = response(ada, bond)
        after.getHeader(HttpHeaders.ETAG) shouldNotBe before
        val revalidated = conditional(ada, bond, before)
        revalidated.status shouldBe 200
        json.readTree(revalidated.contentAsString)["semanticSearchActive"].asBoolean() shouldBe false
    }

    private fun solo(userId: UUID): String {
        val created =
            mockMvc
                .mvcPost("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(userId))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        return json.readTree(created.contentAsString)["id"].asString()
    }

    private fun turnOff(
        userId: UUID,
        bond: String,
    ) {
        patch(userId, bond, """{"semanticSearchEnabled":false}""", etag(userId, bond)).status shouldBe 200
    }

    private fun response(
        userId: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc.mvcGet("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun conditional(
        userId: UUID,
        bond: String,
        tag: String,
    ): MockHttpServletResponse =
        mockMvc
            .mvcGet("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_NONE_MATCH, tag)
            }.andReturn()
            .response

    private fun get(
        userId: UUID,
        bond: String,
    ): JsonNode = json.readTree(response(userId, bond).contentAsString)

    private fun list(userId: UUID): List<JsonNode> =
        json
            .readTree(
                mockMvc
                    .mvcGet("/api/v1/bonds") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
                    .andReturn()
                    .response.contentAsString,
            )["bonds"]
            .toList()

    private fun etag(
        userId: UUID,
        bond: String,
    ): String = response(userId, bond).getHeader(HttpHeaders.ETAG)!!

    private fun patch(
        userId: UUID,
        bond: String,
        body: String,
        ifMatch: String,
    ): MockHttpServletResponse =
        mockMvc
            .mvcPatch("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_MATCH, ifMatch)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun bearer(userId: UUID) = "Bearer ${tokens.issue(userId).token}"
}
