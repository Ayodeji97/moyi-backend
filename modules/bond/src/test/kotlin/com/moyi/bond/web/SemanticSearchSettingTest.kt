package com.moyi.bond.web

import com.moyi.bond.api.BondAccess
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
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
import org.springframework.test.web.servlet.delete as mvcDelete
import org.springframework.test.web.servlet.get as mvcGet
import org.springframework.test.web.servlet.patch as mvcPatch
import org.springframework.test.web.servlet.post as mvcPost

/**
 * The bond's semantic-search setting (FR-204, ADR-0054, spec §3.3): on by default,
 * turned off by either member alone, turned on only with both.
 *
 * Off is a `PATCH` because one person withdrawing consent to the embedding provider
 * needs nobody's agreement; on is a proposal because it sends what *both* of them
 * wrote to a third party. Decision 9 (provisional) lets a member of an ended bond
 * still turn it off, and nothing else.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class SemanticSearchSettingTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val access: BondAccess,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var cy: UUID
    private lateinit var dee: UUID
    private lateinit var eve: UUID
    private lateinit var fay: UUID
    private lateinit var gus: UUID

    @BeforeEach
    fun people() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        cy = users.verified("Cy")
        dee = users.verified("Dee")
        eve = users.verified("Eve")
        fay = users.verified("Fay")
        gus = users.verified("Gus")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a new bond has semantic search on`() {
        val bond = pair(ada, bea)
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe true
    }

    @Test
    fun `either member turns it off alone, and the outbox records it`() {
        val bond = pair(ada, bea)
        patch(bea, bond, """{"semanticSearchEnabled":false}""", ifMatch = etag(bea, bond)).status shouldBe 200
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
        outboxTypes(bond) shouldContain "SemanticSearchDisabled"
    }

    @Test
    fun `the event names the bond and nothing else`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        val payload =
            jdbc.queryForList(
                "SELECT jsonb_object_keys(payload) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SemanticSearchDisabled'",
                String::class.java,
                UUID.fromString(bond),
            )
        payload shouldBe listOf("bondId")
    }

    @Test
    fun `turning it off when it is already off publishes nothing more`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        turnOff(bea, bond)
        outboxTypes(bond).count { it == "SemanticSearchDisabled" } shouldBe 1
    }

    @Test
    fun `PATCH cannot turn it on`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        val res = patch(ada, bond, """{"semanticSearchEnabled":true}""", ifMatch = etag(ada, bond))
        res.status shouldBe 422
        violations(res) shouldBe listOf("semanticSearchEnabled" to "ASSERT_FALSE")
    }

    @Test
    fun `turning on needs the other member`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
        get(bea, bond)["pendingSemanticSearchRequest"]["requestedByMemberId"].isNull shouldBe false
        outboxTypes(bond) shouldContain "ProposalOpened"
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 202
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe true
        get(ada, bond)["pendingSemanticSearchRequest"].isNull shouldBe true
    }

    @Test
    fun `the proposer asking again changes nothing, and either member cancels`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        post(ada, "/bonds/$bond/semantic-search")
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
        delete(bea, "/bonds/$bond/semantic-search").status shouldBe 204
        get(ada, bond)["pendingSemanticSearchRequest"].isNull shouldBe true
    }

    @Test
    fun `cancelling nothing is 404`() {
        val bond = pair(ada, bea)
        delete(ada, "/bonds/$bond/semantic-search").status shouldBe 404
    }

    @Test
    fun `asking for it while it is on changes nothing`() {
        val bond = pair(ada, bea)
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        get(bea, bond)["pendingSemanticSearchRequest"].isNull shouldBe true
    }

    @Test
    fun `turning it off cancels a request to turn it on that is somehow still open`() {
        // Unreachable through the API today (asking while on is a no-op, agreeing
        // confirms the row), so the state is built in SQL: the setting flipped back
        // on under a live request. Turning off must not leave it to be agreed to.
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        jdbc.update("UPDATE bonds SET semantic_search_enabled = true WHERE id = ?", UUID.fromString(bond))
        turnOff(bea, bond)
        get(ada, bond)["pendingSemanticSearchRequest"].isNull shouldBe true
    }

    @Test
    fun `turning it off again while a request is pending leaves the request for the other member to answer`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        turnOff(bea, bond)
        get(bea, bond)["pendingSemanticSearchRequest"].isNull shouldBe false
    }

    @Test
    fun `turning off leaves a pending deletion request intact`() { // cancelLive is scoped to SEMANTIC_SEARCH_ON
        val bond = pair(ada, bea)
        post(bea, "/bonds/$bond/deletion-request").status shouldBe 202
        turnOff(ada, bond)
        get(ada, bond)["pendingDeletionRequest"]["requestedByMemberId"].isNull shouldBe false
    }

    @Test
    fun `a member of an ended bond can still turn it off, and nothing else`() { // decision 9, provisional
        val bond = pair(ada, bea)
        end(bond)
        patch(ada, bond, """{"semanticSearchEnabled":false}""", ifMatch = etag(ada, bond)).status shouldBe 200
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
        outboxTypes(bond) shouldContain "SemanticSearchDisabled"
        patch(ada, bond, """{"semanticSearchEnabled":false,"strictMode":true}""", ifMatch = etag(ada, bond)).status shouldBe 409
        patch(ada, bond, """{"strictMode":true}""", ifMatch = etag(ada, bond)).status shouldBe 409
    }

    @Test
    fun `an ended bond cannot be asked to turn it back on`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        end(bond)
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 409
    }

    @Test
    fun `bondsWithSemanticSearch lists solo and ended bonds with the setting on, and no bond with it off`() {
        val paired = pair(ada, bea)
        val alone = solo(cy)
        val ended = pair(dee, eve)
        end(ended)
        val off = pair(fay, gus)
        turnOff(fay, off)
        access.bondsWithSemanticSearch(null, 100).map { it.toString() } shouldContainAll listOf(paired, alone, ended)
        access.bondsWithSemanticSearch(null, 100).map { it.toString() } shouldNotContain off
    }

    @Test
    fun `bondsWithSemanticSearch pages by id`() {
        val all = listOf(pair(ada, bea), solo(cy), solo(dee)).map(UUID::fromString).sorted()
        val first = access.bondsWithSemanticSearch(null, 2)
        first shouldBe all.take(2)
        access.bondsWithSemanticSearch(first.last(), 2) shouldBe all.drop(2)
    }

    @Test
    fun `a solo bond turns it back on alone`() {
        val bond = solo(ada)
        turnOff(ada, bond)
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe true
    }

    @Test
    fun `the ETag changes when the setting changes`() {
        val bond = pair(ada, bea)
        val before = etag(ada, bond)
        turnOff(ada, bond)
        etag(ada, bond) shouldNotBe before
    }

    @Test
    fun `a pending request changes the other member's ETag, though the bond row did not change`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        val before = etag(bea, bond)
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        etag(bea, bond) shouldNotBe before
    }

    @Test
    fun `the membership carries the setting`() {
        val bond = pair(ada, bea)
        turnOff(ada, bond)
        access.membershipOf(ada, UUID.fromString(bond)).semanticSearchEnabled shouldBe false
        access.closingViewOf(UUID.fromString(bond))!!.semanticSearchEnabled shouldBe false
    }

    // ---- helpers ------------------------------------------------------------

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

    private fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val bond = solo(creator)
        val code = get(creator, bond)["invite"]["code"].asString()
        mockMvc
            .mvcPost("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
        return bond
    }

    /** One member leaves, which archives the bond for both. */
    private fun end(bond: String) {
        val leaver =
            jdbc
                .queryForList("SELECT user_id FROM bond_members WHERE bond_id = ?", UUID::class.java, UUID.fromString(bond))
                .first()!!
        post(leaver, "/bonds/$bond/leave").status shouldBe 204
    }

    private fun turnOff(
        userId: UUID,
        bond: String,
    ) {
        patch(userId, bond, """{"semanticSearchEnabled":false}""", ifMatch = etag(userId, bond)).status shouldBe 200
    }

    private fun response(
        userId: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc.mvcGet("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun get(
        userId: UUID,
        bond: String,
    ): JsonNode = json.readTree(response(userId, bond).contentAsString)

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

    private fun post(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse = mockMvc.mvcPost("/api/v1$path") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun delete(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc.mvcDelete("/api/v1$path") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    /** The outbox is never truncated between tests, so this reads only this bond's rows. */
    private fun outboxTypes(bond: String): List<String> =
        jdbc
            .queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ? OR payload->>'bondId' = ?",
                String::class.java,
                UUID.fromString(bond),
                bond,
            ).filterNotNull()

    private fun violations(res: MockHttpServletResponse): List<Pair<String, String>> =
        json.readTree(res.contentAsString)["errors"].let { errors ->
            (0 until errors.size()).map { errors[it]["field"].asString() to errors[it]["code"].asString() }
        }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"
}
