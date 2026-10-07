package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.EndBond
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
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
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class BondEndingEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val guard: BondAccessGuard,
    @Autowired private val endBond: EndBond,
    @Autowired private val transactions: TransactionTemplate,
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

    // ---- FR-029a: taking one's own entries back ------------------------------
    //
    // Every assertion below is on the rows of the bond the test made, by its
    // id: the module's tests share one database, and `outbox_events` is never
    // truncated.

    @Test
    fun `blocking with no body withdraws - one marker for the caller, one event, both stamped alike`() {
        val pair = pairedBond()

        block(pair.ada, pair.id).status shouldBe 204

        withdrawnMembers(pair.id) shouldBe listOf(memberIdOf(pair.id, pair.ada))
        val events = withdrawalEvents(pair.id)
        events shouldHaveSize 1
        events.single()["aggregate_type"] shouldBe "Bond"
        // The payload is exactly the two ids, and nothing that says which
        // of the two endings this was.
        payloadOf(events.single()) shouldBe mapOf("bondId" to pair.id, "memberId" to memberIdOf(pair.id, pair.ada).toString())
        // One instant for both, cut to microseconds before either is stored:
        // this context's clock ticks finer than Postgres keeps, and Postgres
        // rounds, so an uncut marker would land a microsecond after its event.
        val markedAt =
            jdbc.queryForObject(
                "SELECT withdrawn_at FROM bond_entry_withdrawals WHERE bond_id = ?",
                Timestamp::class.java,
                UUID.fromString(pair.id),
            )!!
        (events.single()["occurred_at"] as Timestamp).toInstant() shouldBe markedAt.toInstant()
    }

    @Test
    fun `the absent flag means withdraw on a block and keep on a leave, however it is absent`() {
        // No body and no Content-Type (scripts/moyi, the Bruno collection),
        // a JSON Content-Type and no body (scripts/smoke.sh), `{}`, an
        // explicit null, and a property this API does not know.
        val absences: List<Pair<String?, Boolean>> =
            listOf(
                null to false,
                null to true,
                "" to true,
                "{}" to true,
                """{"withdrawEntries":null}""" to true,
                """{"somethingElse":1}""" to true,
            )

        absences.forEach { (body, json) ->
            withClue("body=$body json=$json") {
                val blocked = pairedBond()
                send(blocked.ada, blocked.id, "block", body, json).status shouldBe 204
                withdrawnMembers(blocked.id) shouldHaveSize 1
                withdrawalEvents(blocked.id) shouldHaveSize 1

                val left = pairedBond()
                send(left.ada, left.id, "leave", body, json).status shouldBe 204
                withdrawnMembers(left.id).shouldBeEmpty()
                withdrawalEvents(left.id).shouldBeEmpty()
            }
            clear()
        }
    }

    @Test
    fun `blocking with the flag off ends the bond and withdraws nothing`() {
        val pair = pairedBond()

        block(pair.ada, pair.id, """{"withdrawEntries":false}""").status shouldBe 204

        getBond(pair.ada, pair.id).contentAsString shouldContain "\"status\":\"ARCHIVED\""
        jdbc.queryForObject("SELECT count(*) FROM blocks WHERE bond_id = ?", Int::class.java, UUID.fromString(pair.id)) shouldBe 1
        withdrawnMembers(pair.id).shouldBeEmpty()
        withdrawalEvents(pair.id).shouldBeEmpty()
    }

    @Test
    fun `leaving with the flag on withdraws, exactly as a block does`() {
        val pair = pairedBond()

        leave(pair.ada, pair.id, """{"withdrawEntries":true}""").status shouldBe 204

        withdrawnMembers(pair.id) shouldBe listOf(memberIdOf(pair.id, pair.ada))
        val events = withdrawalEvents(pair.id)
        events shouldHaveSize 1
        payloadOf(events.single()) shouldBe mapOf("bondId" to pair.id, "memberId" to memberIdOf(pair.id, pair.ada).toString())
    }

    @Test
    fun `a second block with the flag on writes nothing more`() {
        val pair = pairedBond()
        block(pair.ada, pair.id, """{"withdrawEntries":true}""").status shouldBe 204
        val first = jdbc.queryForMap("SELECT * FROM bond_entry_withdrawals WHERE bond_id = ?", UUID.fromString(pair.id))

        block(pair.ada, pair.id, """{"withdrawEntries":true}""").status shouldBe 204
        block(pair.ada, pair.id).status shouldBe 204

        jdbc.queryForMap("SELECT * FROM bond_entry_withdrawals WHERE bond_id = ?", UUID.fromString(pair.id)) shouldBe first
        withdrawalEvents(pair.id) shouldHaveSize 1
    }

    @Test
    fun `a block that declined to withdraw can be repeated to withdraw after all`() {
        val pair = pairedBond()
        block(pair.ada, pair.id, """{"withdrawEntries":false}""").status shouldBe 204
        withdrawnMembers(pair.id).shouldBeEmpty()

        block(pair.ada, pair.id, """{"withdrawEntries":true}""").status shouldBe 204

        withdrawnMembers(pair.id) shouldBe listOf(memberIdOf(pair.id, pair.ada))
        withdrawalEvents(pair.id) shouldHaveSize 1
    }

    @Test
    fun `a member who left, and one whose partner left first, each withdraw by blocking afterwards`() {
        val pair = pairedBond()
        leave(pair.ada, pair.id).status shouldBe 204
        withdrawnMembers(pair.id).shouldBeEmpty()

        // Ada left earlier and kept her words; Bea never left at all.
        block(pair.ada, pair.id).status shouldBe 204
        block(pair.bea, pair.id).status shouldBe 204

        withdrawnMembers(pair.id).toSet() shouldBe setOf(memberIdOf(pair.id, pair.ada), memberIdOf(pair.id, pair.bea))
        withdrawalEvents(pair.id).map { payloadOf(it)["memberId"] }.toSet() shouldBe
            setOf(memberIdOf(pair.id, pair.ada).toString(), memberIdOf(pair.id, pair.bea).toString())
    }

    @Test
    fun `leaving an ended bond with the flag on is still 409 and withdraws nothing`() {
        val pair = pairedBond()
        leave(pair.bea, pair.id).status shouldBe 204

        val refused = leave(pair.ada, pair.id, """{"withdrawEntries":true}""")

        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        withdrawnMembers(pair.id).shouldBeEmpty()
        withdrawalEvents(pair.id).shouldBeEmpty()
    }

    @Test
    fun `a bond with only its creator is withdrawn from like any other`() {
        // Leave and block treat a bond nobody joined as they treat any open
        // bond: it ends. Its creator may have written in it while waiting
        // (doc 04 §8.3a), so there can be words to take back.
        val ada = users.verified("Ada")
        val blocked = bondIdOf(createBond(ada))
        val left = bondIdOf(createBond(ada))
        val kept = bondIdOf(createBond(ada))

        block(ada, blocked).status shouldBe 204
        leave(ada, left, """{"withdrawEntries":true}""").status shouldBe 204
        leave(ada, kept).status shouldBe 204

        withdrawnMembers(blocked) shouldBe listOf(memberIdOf(blocked, ada))
        withdrawalEvents(blocked) shouldHaveSize 1
        withdrawnMembers(left) shouldBe listOf(memberIdOf(left, ada))
        withdrawalEvents(left) shouldHaveSize 1
        withdrawnMembers(kept).shouldBeEmpty()
        withdrawalEvents(kept).shouldBeEmpty()
    }

    @Test
    fun `a withdrawal moves neither the bond's version nor its ETag, for either member`() {
        // A bond's ETag is its row version (ADR-0028 decision 5). A marker
        // that touched the row would hand the other member a counter.
        val pair = pairedBond()
        leave(pair.bea, pair.id).status shouldBe 204
        val version = versionOf(pair.id)
        val before = listOf(pair.ada, pair.bea).map { getBond(it, pair.id) }

        block(pair.ada, pair.id).status shouldBe 204

        withdrawnMembers(pair.id) shouldHaveSize 1
        versionOf(pair.id) shouldBe version
        listOf(pair.ada, pair.bea).map { getBond(it, pair.id) }.zip(before).forEach { (after, earlier) ->
            after.getHeader(HttpHeaders.ETAG) shouldBe earlier.getHeader(HttpHeaders.ETAG)
            after.contentAsString shouldBe earlier.contentAsString
        }

        // And on a bond that was still open, a block that withdraws moves the
        // version exactly as far as one that does not.
        val withdrawn = pairedBond(names = "Cy" to "Di")
        val notWithdrawn = pairedBond(names = "Ed" to "Flo")
        block(withdrawn.ada, withdrawn.id, """{"withdrawEntries":true}""").status shouldBe 204
        block(notWithdrawn.ada, notWithdrawn.id, """{"withdrawEntries":false}""").status shouldBe 204
        versionOf(withdrawn.id) shouldBe versionOf(notWithdrawn.id)
    }

    @Test
    fun `a body that cannot be read is the standing 400, and nothing is ended`() {
        // Not JSON, not an object, and every value that is not a JSON true,
        // false or null. The last group is refused on purpose: left to its
        // defaults the JSON library reads "true", 1 and 2 as true and "" as
        // absent, and what this flag asks for cannot be undone.
        val unreadable =
            listOf(
                "not json",
                """{"withdrawEntries": """,
                "[]",
                "true",
                """{"withdrawEntries":"perhaps"}""",
                """{"withdrawEntries":"true"}""",
                """{"withdrawEntries":"false"}""",
                """{"withdrawEntries":""}""",
                """{"withdrawEntries":1}""",
                """{"withdrawEntries":0}""",
                """{"withdrawEntries":1.0}""",
                """{"withdrawEntries":[true]}""",
                """{"withdrawEntries":{}}""",
            )

        listOf("block", "leave").forEach { ending ->
            unreadable.forEach { body ->
                withClue("$ending $body") {
                    val pair = pairedBond()
                    val response = send(pair.ada, pair.id, ending, body, json = true)

                    response.status shouldBe 400
                    response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
                    // The standing sentence, and nothing of what was sent.
                    response.contentAsString shouldContain "\"detail\":\"The request body could not be read.\""
                    getBond(pair.ada, pair.id).contentAsString shouldContain "\"status\":\"ACTIVE\""
                    withdrawnMembers(pair.id).shouldBeEmpty()
                    withdrawalEvents(pair.id).shouldBeEmpty()
                }
                clear()
            }
        }
    }

    @Test
    fun `a stranger learns nothing from the body they send`() {
        // The guard is the handler's first statement, so whatever a readable
        // body says, a non-member gets the one 404 and nothing is written.
        val pair = pairedBond()
        val eve = users.verified("Eve")

        listOf("block", "leave").forEach { ending ->
            val response = send(eve, pair.id, ending, """{"withdrawEntries":true}""", json = true)
            response.status shouldBe 404
            response.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        }
        getBond(pair.ada, pair.id).contentAsString shouldContain "\"status\":\"ACTIVE\""
        withdrawnMembers(pair.id).shouldBeEmpty()
        withdrawalEvents(pair.id).shouldBeEmpty()
    }

    @Test
    fun `an ending that rolls back leaves no marker and no event`() {
        // The marker and the event are the ending's own transaction, not
        // statements beside it: undo the ending and both are gone with it.
        val pair = pairedBond()
        val membership = guard.membershipOf(UserId(pair.ada), BondId(UUID.fromString(pair.id)))

        transactions.execute { status ->
            endBond.block(membership, withdrawEntries = true)
            // Seen inside the transaction, so what vanishes below was really written.
            withdrawnMembers(pair.id) shouldHaveSize 1
            withdrawalEvents(pair.id) shouldHaveSize 1
            status.setRollbackOnly()
        }

        getBond(pair.ada, pair.id).contentAsString shouldContain "\"status\":\"ACTIVE\""
        withdrawnMembers(pair.id).shouldBeEmpty()
        withdrawalEvents(pair.id).shouldBeEmpty()
    }

    private data class PairedBond(
        val id: String,
        val ada: UUID,
        val bea: UUID,
    )

    private fun pairedBond(names: Pair<String, String> = "Ada" to "Bea"): PairedBond {
        val ada = users.verified(names.first)
        val bea = users.verified(names.second)
        val created = createBond(ada)
        accept(bea, codeOf(created)).status shouldBe 200
        return PairedBond(bondIdOf(created), ada, bea)
    }

    private fun withdrawnMembers(bondId: String): List<UUID> =
        jdbc
            .queryForList(
                "SELECT member_id FROM bond_entry_withdrawals WHERE bond_id = ? ORDER BY withdrawn_at, member_id",
                UUID::class.java,
                UUID.fromString(bondId),
            ).filterNotNull()

    private fun withdrawalEvents(bondId: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT aggregate_type, event_type, payload::text AS payload, occurred_at FROM outbox_events " +
                "WHERE aggregate_id = ? AND event_type = 'EntriesWithdrawn' ORDER BY id",
            UUID.fromString(bondId),
        )

    private fun payloadOf(event: Map<String, Any?>): Map<String, String> =
        Regex(""""([A-Za-z]+)"\s*:\s*"([^"]+)"""").findAll(event["payload"] as String).associate { it.groupValues[1] to it.groupValues[2] }

    private fun memberIdOf(
        bondId: String,
        userId: UUID,
    ): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?",
            UUID::class.java,
            UUID.fromString(bondId),
            userId,
        )!!

    private fun versionOf(bondId: String): Long =
        jdbc.queryForObject("SELECT version FROM bonds WHERE id = ?", Long::class.java, UUID.fromString(bondId))!!

    /** An ending sent as a client would send it: [body] null is no body at all, [json] says whether a Content-Type goes with it. */
    private fun send(
        userId: UUID,
        bondId: String,
        ending: String,
        body: String?,
        json: Boolean,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/$ending") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                if (json) contentType = MediaType.APPLICATION_JSON
                if (body != null) content = body
            }.andReturn()
            .response

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
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"proposalId":"${jdbc
                        .queryForList(
                            "SELECT id FROM bond_proposals WHERE bond_id = ? AND kind = 'TIMEZONE_CHANGE' ORDER BY proposed_at DESC",
                            UUID.fromString(bondId),
                        ).firstOrNull()
                        ?.get("id") ?: UUID.randomUUID()}"}"""
            }.andReturn()
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
        body: String? = null,
    ): MockHttpServletResponse = send(userId, bondId, "leave", body, json = body != null)

    private fun block(
        userId: UUID,
        bondId: String,
        body: String? = null,
    ): MockHttpServletResponse = send(userId, bondId, "block", body, json = body != null)

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
