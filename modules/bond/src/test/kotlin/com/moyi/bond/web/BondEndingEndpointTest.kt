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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /bonds/{bondId}/leave` and `POST /bonds/{bondId}/block` (FR-026,
 * FR-029): every status the design's §5.2 rows #8–#9 list, and the rule §6.3
 * states about what an archived bond still answers.
 *
 * That the two are *indistinguishable from the other side* is `DiscreetExitTest`'s
 * subject. This file is about each one's own behaviour.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondEndingEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `leaving is 204, archives the bond and kills the live code`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val code = codeOf(created)

        leave(ada, bondId).status shouldBe 204

        val after = getBond(ada, bondId)
        after.status shouldBe 200
        after.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        after.contentAsString shouldContain "\"archivedAt\":\""
        after.contentAsString shouldContain "\"invite\":null"
        resolve(users.verified("Joiner"), code).status shouldBe 404
    }

    @Test
    fun `leaving twice is 409 BOND_ARCHIVED`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        leave(ada, bondId).status shouldBe 204

        val again = leave(ada, bondId)

        again.status shouldBe 409
        again.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `the member who left still reads the archive, and so does the other`() {
        // states.md §9, and the design's §11 decision 9: both keep the record.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        leave(ada, bondId).status shouldBe 204

        getBond(ada, bondId).status shouldBe 200
        getBond(bea, bondId).status shouldBe 200
        listBonds(ada).contentAsString shouldContain bondId
        listBonds(bea).contentAsString shouldContain bondId
    }

    @Test
    fun `an archived bond takes no writes`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val inviteId = inviteIdOf(created)
        leave(ada, bondId).status shouldBe 204

        val invited = createInvite(ada, bondId)
        invited.status shouldBe 409
        invited.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""

        val revoked = revokeInvite(ada, bondId, inviteId)
        revoked.status shouldBe 409
        revoked.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `leaving frees the slot the three-bond limit counts`() {
        val ada = users.verified("Ada")
        val first = bondIdOf(createBond(ada))
        createBond(ada).status shouldBe 201
        createBond(ada).status shouldBe 201
        createBond(ada).status shouldBe 409

        leave(ada, first).status shouldBe 204

        createBond(ada).status shouldBe 201
    }

    @Test
    fun `blocking is 204 and writes one row per other member`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT blocked_user_id FROM blocks", UUID::class.java) shouldBe bea
        getBond(ada, bondId).contentAsString shouldContain "\"status\":\"ARCHIVED\""
    }

    @Test
    fun `blocking an already archived bond is allowed, and blocking twice writes one row`() {
        // The one place leave and block differ in what they accept (FR-029):
        // the other person left first, and blocking them afterwards is the
        // whole point.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        leave(bea, bondId).status shouldBe 204

        block(ada, bondId).status shouldBe 204
        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        // And the blocker's own membership is deliberately *not* stamped: the
        // person who left keeps read access, and a `leftAt` appearing on the
        // other member would tell them they were blocked, since block is the
        // only mutation this bond still accepts (doc 26 §2.1). `left_at` is only
        // ever set by ending a bond that is still open.
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `blocking a bond nobody else ever joined behaves exactly as leaving`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 0
        getBond(ada, bondId).contentAsString shouldContain "\"status\":\"ARCHIVED\""
    }

    @Test
    fun `a blocked pair cannot pair again, and the refusal names nothing`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        accept(bea, codeOf(created)).status shouldBe 200
        block(ada, bondIdOf(created)).status shouldBe 204

        val response = accept(bea, codeOf(createBond(ada)))

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"INVITE_NOT_USABLE\""
    }

    @Test
    fun `no response on the ending paths says block`() {
        // Doc 26 §2.1: the word never reaches the wire, in either direction.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        val blocked = block(ada, bondId)

        blocked.contentAsString shouldBe ""
        val seen = getBond(bea, bondId).contentAsString + listBonds(bea).contentAsString
        seen.lowercase() shouldNotContain "block"
    }

    @Test
    fun `ending a bond cancels what was waiting to be agreed`() {
        // ADR-0028 promised this and could not deliver it until B5 built the
        // table. Without it, a confirmation arriving after a leave would try to
        // move the anchor zone of a bond that has ended.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        proposeTimezone(ada, bondId).status shouldBe 200
        getBond(bea, bondId).contentAsString shouldContain "\"proposedTimezone\""

        leave(ada, bondId).status shouldBe 204

        getBond(bea, bondId).contentAsString shouldContain "\"pendingTimezoneChange\":null"
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancelled_at IS NULL", Int::class.java) shouldBe 0
        // And confirming afterwards is the archived refusal, not a zone change.
        confirmTimezone(bea, bondId).status shouldBe 409
        jdbc.queryForObject("SELECT anchor_timezone FROM bonds", String::class.java) shouldBe "Africa/Lagos"
    }

    private fun proposeTimezone(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"Europe/London"}"""
            }.andReturn()
            .response

    private fun confirmTimezone(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    // ---- helpers ------------------------------------------------------------

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun listBonds(userId: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun block(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/block") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun createInvite(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/invites") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun revokeInvite(
        userId: UUID,
        bondId: String,
        inviteId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/bonds/$bondId/invites/$inviteId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun resolve(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/invites/$code") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun inviteIdOf(response: MockHttpServletResponse): String =
        Regex(""""invite":\{"id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
