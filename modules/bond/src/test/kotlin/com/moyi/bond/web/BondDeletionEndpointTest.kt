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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import javax.sql.DataSource

/**
 * Closing the box (FR-028, §5.2 rows #16–#17, `states.md` §9).
 *
 * The rules worth reading the tests for: one route both asks and agrees, a
 * repeat by the same member changes nothing, the cooling-off is 30 days and
 * either member can call it off, and **an archived bond refuses the request
 * whichever way it ended** — which is ADR-0030's decision and is also checked
 * on the bytes in `DiscreetExitTest`.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondDeletionEndpointTest(
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
    fun `the first request is 202 and nothing is deleted`() {
        val (ada, bea, bondId) = pairedBond()

        val response = request(ada, bondId)

        response.status shouldBe 202
        response.contentAsString shouldContain "\"status\":\"ACTIVE\""
        response.contentAsString shouldContain "\"requestedByMemberId\""
        response.contentAsString shouldContain "\"deletionScheduledFor\":null"
        getBond(bea, bondId).contentAsString shouldContain "\"pendingDeletionRequest\""
        jdbc.queryForObject("SELECT count(*) FROM bonds", Int::class.java) shouldBe 1
    }

    @Test
    fun `repeating it as the same member is an idempotent 202 that confirms nothing`() {
        // A person tapping a button twice has not consented twice.
        val (ada, _, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202

        val again = request(ada, bondId)

        again.status shouldBe 202
        again.contentAsString shouldContain "\"status\":\"ACTIVE\""
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `the other member's request confirms it, and the cooling-off starts`() {
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202

        val response = request(bea, bondId)

        response.status shouldBe 202
        response.contentAsString shouldContain "\"status\":\"PENDING_DELETION\""
        response.contentAsString shouldContain "\"deletionScheduledFor\":\""
        response.contentAsString shouldContain "\"pendingDeletionRequest\":null"
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java) shouldBe 1
        // 30 days, and states.md §9 is explicit that this differs from account
        // deletion's 14 — so the arithmetic is worth asserting, not just the field.
        jdbc.queryForObject(
            "SELECT deletion_requested_at + interval '30 days' = " +
                "(SELECT deletion_requested_at + interval '30 days' FROM bonds) FROM bonds",
            Boolean::class.java,
        ) shouldBe true
    }

    @Test
    fun `a bond with one member confirms its own request`() {
        // Spec §11 decision 6: there is nobody to consent. The proposal is still
        // written and marked confirmed, so the record says who asked.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = request(ada, bondId)

        response.status shouldBe 202
        response.contentAsString shouldContain "\"status\":\"PENDING_DELETION\""
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `during the cooling-off the bond takes no other write and stays readable`() {
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202
        request(bea, bondId).status shouldBe 202

        val etag = getBond(ada, bondId).getHeader(HttpHeaders.ETAG)!!
        patch(ada, bondId, etag).let {
            it.status shouldBe 409
            it.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        }
        createInvite(ada, bondId).status shouldBe 409
        putSettings(ada, bondId).status shouldBe 409
        // And both of them can still read it, which is what makes the 30 days a
        // cooling-off rather than a disappearance.
        getBond(ada, bondId).status shouldBe 200
        getBond(bea, bondId).status shouldBe 200
    }

    @Test
    fun `either member cancels, and an active bond returns to active`() {
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202
        request(bea, bondId).status shouldBe 202

        cancel(bea, bondId).status shouldBe 204

        val after = getBond(ada, bondId)
        after.contentAsString shouldContain "\"status\":\"ACTIVE\""
        after.contentAsString shouldContain "\"deletionScheduledFor\":null"
        // And the bond takes writes again.
        patch(ada, bondId, after.getHeader(HttpHeaders.ETAG)!!).status shouldBe 200
    }

    @Test
    fun `cancelling an unconfirmed request removes it`() {
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202

        cancel(bea, bondId).status shouldBe 204

        getBond(ada, bondId).contentAsString shouldContain "\"pendingDeletionRequest\":null"
        // And a fresh ask is possible, because the slot is free.
        request(ada, bondId).status shouldBe 202
    }

    @Test
    fun `cancelling nothing is 404`() {
        val (ada, _, bondId) = pairedBond()

        cancel(ada, bondId).status shouldBe 404
    }

    @Test
    fun `an archived bond refuses the request`() {
        // ADR-0030: refused whichever way it ended, so a blocked member cannot
        // tell a block from a leave by trying it. DiscreetExitTest compares the
        // two answers byte for byte; this one is the status itself.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        leave(ada, bondId).status shouldBe 204

        val response = request(ada, bondId)

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `a non-member gets the same 404 on both routes`() {
        val (_, _, bondId) = pairedBond()
        val eve = users.verified("Eve")

        request(eve, bondId).status shouldBe 404
        cancel(eve, bondId).status shouldBe 404
    }

    @Test
    fun `cancelling a solo bond's deletion leaves it joinable`() {
        // The review of PR #41's first finding, end to end: restoring ACTIVE
        // would advertise a code that could never be used again.
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val code = codeOf(created)
        request(ada, bondId).status shouldBe 202

        cancel(ada, bondId).status shouldBe 204

        val after = getBond(ada, bondId)
        after.contentAsString shouldContain "\"status\":\"PENDING_MEMBER\""
        after.contentAsString shouldContain "\"invite\":null"

        // **The old code stays dead, and that is deliberate.** Scheduling the
        // deletion revoked it (a code into a room nobody could enter), and
        // cancelling does not resurrect a credential that was already shared
        // and withdrawn. The creator issues a fresh one, which is possible
        // again precisely because the status came back as PENDING_MEMBER rather
        // than ACTIVE — the bug this test was written for.
        val bea = users.verified("Bea")
        accept(bea, code).status shouldBe 404
        val reissued = createInvite(ada, bondId)
        reissued.status shouldBe 201
        accept(bea, codeOf(reissued)).status shouldBe 200
    }

    @Test
    fun `scheduling a deletion revokes the live invite`() {
        // The review's fourth finding: a bond counting down refuses every join,
        // so a code it kept advertising would be a code that cannot work.
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val code = codeOf(created)

        request(ada, bondId).status shouldBe 202

        getBond(ada, bondId).contentAsString shouldContain "\"invite\":null"
        accept(users.verified("Bea"), code).status shouldBe 404
    }

    @Test
    fun `blocking during the cooling-off ends the blocker's membership, and the bond cannot be revived with them in it`() {
        // The review's second finding, and the safety-critical one: `block` had
        // no effect during the cooling-off, so the other member could cancel and
        // the blocker was back in a live bond with the person they blocked.
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202
        request(bea, bondId).status shouldBe 202

        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java) shouldBe 1

        // Bea calls the deletion off — and the bond must not come back with Ada
        // in it.
        cancel(bea, bondId).status shouldBe 204
        val after = getBond(bea, bondId)
        after.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        // And the pair cannot be put back in touch (FR-029).
        accept(bea, codeOf(createBond(ada))).status shouldBe 404
    }

    @Test
    fun `a member who has left cannot call off a deletion the two of them agreed to`() {
        // `cancel` skips the `isOpen` check on purpose — PENDING_DELETION is not
        // open, so consulting it would make the cooling-off uncancellable. It
        // has to refuse a *left* member some other way, and nothing did: every
        // other write path refuses them only incidentally, by checking `isOpen`.
        // Without this, Ada agrees to the deletion, walks out, and then revokes
        // the agreement on her way — leaving Bea holding a bond she consented to
        // destroy and can never delete, because every re-request is 409 on an
        // archived bond.
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202
        request(bea, bondId).status shouldBe 202
        leave(ada, bondId).status shouldBe 204

        cancel(ada, bondId).status shouldBe 404

        // Still counting down, and still Bea's to call off — the escape hatch
        // belongs to whoever is actually in the bond.
        getBond(bea, bondId).contentAsString shouldContain "\"status\":\"PENDING_DELETION\""
        cancel(bea, bondId).status shouldBe 204
    }

    @Test
    fun `a deletion called off after the other member left leaves an archived bond that knows when it ended`() {
        // The one path that produced ARCHIVED without `archivedAt`. Every other
        // archived bond carries the timestamp, and Phase 5's deletion job and
        // the export both read it as "when did this end".
        val (ada, bea, bondId) = pairedBond()
        request(ada, bondId).status shouldBe 202
        request(bea, bondId).status shouldBe 202
        leave(ada, bondId).status shouldBe 204

        cancel(bea, bondId).status shouldBe 204

        val after = getBond(bea, bondId)
        after.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        after.contentAsString shouldNotContain "\"archivedAt\":null"
        jdbc.queryForObject("SELECT count(*) FROM bonds WHERE archived_at IS NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `leaving during the cooling-off is allowed, and looks exactly like blocking`() {
        // The two must stay indistinguishable wherever both are permitted (doc
        // 26 §2.1). Refusing `leave` for thirty days while permitting `block`
        // would have been an oracle on its own.
        val left = coolingOffBond { ada, bondId -> leave(ada, bondId) }
        val blocked = coolingOffBond { ada, bondId -> block(ada, bondId) }

        left.ending.status shouldBe 204
        blocked.ending.status shouldBe left.ending.status
        normalise(getBond(blocked.other, blocked.bondId).contentAsString) shouldBe
            normalise(getBond(left.other, left.bondId).contentAsString)
    }

    @Test
    fun `a bond in its cooling-off still counts toward the three-bond limit`() {
        // The review's third finding: the cooling-off used to free a slot, so a
        // user at the cap could start a deletion, create a fourth bond, and then
        // cancel the deletion to hold four.
        val ada = users.verified("Ada")
        val first = bondIdOf(createBond(ada))
        createBond(ada).status shouldBe 201
        createBond(ada).status shouldBe 201
        createBond(ada).status shouldBe 409

        request(ada, first).status shouldBe 202

        createBond(ada).status shouldBe 409
        cancel(ada, first).status shouldBe 204
        createBond(ada).status shouldBe 409
    }

    /** A bond in its cooling-off, ended by [ending]. Ada acts; Bea is the one who sees. */
    private fun coolingOffBond(ending: (UUID, String) -> MockHttpServletResponse): Ended {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        request(ada, bondId).status shouldBe 202
        request(bea, bondId).status shouldBe 202
        return Ended(bondId, bea, ending(ada, bondId))
    }

    private data class Ended(
        val bondId: String,
        val other: UUID,
        val ending: MockHttpServletResponse,
    )

    /** Ids and timestamps two different bonds cannot share; everything else must match. */
    private fun normalise(body: String): String {
        val ids = mutableMapOf<String, String>()
        return body
            .replace(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")) { match ->
                ids.getOrPut(match.value) { "uuid-${ids.size}" }
            }.replace(Regex("""\d{4}-\d{2}-\d{2}T[0-9:.]+Z"""), "timestamp")
    }

    private fun block(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/block") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    // ---- helpers ------------------------------------------------------------

    private fun pairedBond(): Triple<UUID, UUID, String> {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        return Triple(ada, bea, bondId)
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun request(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun cancel(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/bonds/$bondId/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun patch(
        userId: UUID,
        bondId: String,
        etag: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_MATCH, etag)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us two"}"""
            }.andReturn()
            .response

    private fun createInvite(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/invites") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun putSettings(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"reminderTimeLocal":"07:30"}"""
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

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{6})"""").find(response.contentAsString)!!.groupValues[1]
}
