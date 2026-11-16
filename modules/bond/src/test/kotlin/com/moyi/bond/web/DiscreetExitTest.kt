package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.QuietHoursWindow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * **Doc 26 §2.1, proved on the bytes.** From the other side, a block is
 * indistinguishable from a leave.
 *
 * Doc 26 calls the moment a block is discovered the point where retaliation
 * risk peaks, and T-09 turns that into a requirement: the person who was
 * blocked must not be able to tell that they were *identified as a threat*
 * rather than simply left. So there is no notification, no status of their own,
 * no field, no different wording — and this is the test that would notice if a
 * later slice added any of them.
 *
 * Two bonds are built identically, by people with the same display names, and
 * then one ends by a leave and the other by a block. Everything the remaining
 * member can see of each is compared: the `204` itself, the body of
 * `GET /bonds/{id}`, the entry in `GET /bonds`, and the **`ETag`** — which is
 * the row version, and would betray a block that wrote one more time than a
 * leave did. That last one is the subtle channel: nothing in the JSON differs,
 * but a counter the client is *told to keep* would.
 *
 * Ids and timestamps differ between two genuinely different bonds, so they are
 * normalised positionally. Nothing else is. `InviteOneAnswerTest` is the model,
 * and its rule holds here: normalise what the two cannot share, compare the
 * rest byte for byte.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class DiscreetExitTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val notifications: Notifications,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val clock: Clock,
    @Autowired transactionManager: PlatformTransactionManager,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(transactionManager)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `after a leave and after a block, the other member sees exactly the same thing`() {
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""") }

        withClue("the 204 itself") {
            blocked.ending.status shouldBe left.ending.status
            blocked.ending.contentAsString shouldBe left.ending.contentAsString
        }

        val leftDetail = getBond(left.other, left.bondId)
        val blockedDetail = getBond(blocked.other, blocked.bondId)

        withClue("GET /bonds/{id} as the other member") {
            blockedDetail.status shouldBe leftDetail.status
            blockedDetail.getHeader(HttpHeaders.ETAG)!!.substringBefore('-') shouldBe
                leftDetail.getHeader(HttpHeaders.ETAG)!!.substringBefore('-')
            normalise(blockedDetail.contentAsString) shouldBe normalise(leftDetail.contentAsString)
        }

        withClue("GET /bonds as the other member") {
            normalise(listBonds(blocked.other).contentAsString) shouldBe normalise(listBonds(left.other).contentAsString)
        }

        // And what is being compared is not two empty responses.
        leftDetail.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
    }

    @Test
    fun `the blocked member is refused nothing the member who was left is allowed`() {
        // The blocked party still holds a membership row, so the archive stays
        // theirs to read (states.md §9). A 403 or a 404 here would be the
        // disclosure T-09 forbids — and the most dangerous kind, because it
        // would arrive at exactly the person who must not be told.
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""") }

        val leftWrite = post(left.other, "/api/v1/bonds/${left.bondId}/invites")
        val blockedWrite = post(blocked.other, "/api/v1/bonds/${blocked.bondId}/invites")

        leftWrite.status shouldBe 409
        blockedWrite.status shouldBe leftWrite.status
        normalise(blockedWrite.contentAsString) shouldBe normalise(leftWrite.contentAsString)

        // And so is the way out: the blocked member may still end their side of
        // it, and is told exactly what the left-behind member is told.
        val leftAgain = post(left.other, "/api/v1/bonds/${left.bondId}/leave")
        val blockedAgain = post(blocked.other, "/api/v1/bonds/${blocked.bondId}/leave")
        blockedAgain.status shouldBe leftAgain.status
        normalise(blockedAgain.contentAsString) shouldBe normalise(leftAgain.contentAsString)
    }

    @Test
    fun `a block after the other member has already left changes nothing they can see`() {
        // **The case the two tests above do not cover, and the one the Codex
        // review bot found on PR #39.** They compare two bonds ended *by the
        // same member*. This one is the asymmetric order: Bea leaves, and Ada —
        // who is still in it — blocks her afterwards, which FR-029 exists to
        // allow.
        //
        // Bea keeps read access to the archive (`states.md` §9) and
        // `MemberResponse` shows every member's `leftAt`. So if the block
        // stamped Ada's, Bea's next GET would *change* — and since block is the
        // only mutation an archived bond accepts, the only thing that change
        // could mean is "she blocked me". An oracle aimed at exactly the person
        // T-09 says must not be told, and one that no comparison of two leave-
        // ended bonds would ever have caught.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        post(bea, "/api/v1/bonds/$bondId/leave").status shouldBe 204
        val before = getBond(bea, bondId)

        post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""").status shouldBe 204

        val after = getBond(bea, bondId)
        withClue("what Bea sees after being blocked") {
            after.status shouldBe before.status
            after.getHeader(HttpHeaders.ETAG) shouldBe before.getHeader(HttpHeaders.ETAG)
            // Not normalised: it is the same bond, so this is equality, not
            // equivalence. Nothing at all may change.
            after.contentAsString shouldBe before.contentAsString
        }
        // The block itself did happen — it is simply invisible to her.
        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        before.contentAsString shouldContain "\"status\":\"ARCHIVED\""
    }

    @Test
    fun `a deletion request is refused the same way on a bond that was left and one that was blocked`() {
        // **The decision this test exists for.** The Phase 2 spec refused a
        // deletion request only on a bond ended by a *block*, which is an
        // oracle: the blocked member would learn which happened by trying it
        // once. Daniel took the decision to refuse it on *any* archived bond, so
        // the two stay indistinguishable (doc 26 §2.1, ADR-0030) — at the cost
        // that a member who left cannot start a mutual deletion, which account
        // deletion (FR-008) and export (FR-009) still cover.
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""") }

        val afterLeave = post(left.other, "/api/v1/bonds/${left.bondId}/deletion-request")
        val afterBlock = post(blocked.other, "/api/v1/bonds/${blocked.bondId}/deletion-request")

        afterLeave.status shouldBe 409
        afterLeave.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        afterBlock.status shouldBe afterLeave.status
        normalise(afterBlock.contentAsString) shouldBe normalise(afterLeave.contentAsString)
    }

    @Test
    fun `a block that withdraws and a leave that does not are one answer, and one bond, to the other member`() {
        // FR-029a adds a second thing an ending may do, and so a second place
        // for the two to come apart: the withdrawal is recorded in this
        // module, in the ending's transaction. Whatever it writes, the other
        // member's view of the bond must not move by a byte or a version.
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave", """{"withdrawEntries":false}""") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""") }
        val leftSilently = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val leftWithdrawing = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave", """{"withdrawEntries":true}""") }
        val blockedKeeping = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":false}""") }

        // What is being compared really differs underneath.
        withdrawalsIn(left) shouldBe 0
        withdrawalsIn(blocked) shouldBe 1
        withdrawalsIn(leftSilently) shouldBe 0
        withdrawalsIn(leftWithdrawing) shouldBe 1
        withdrawalsIn(blockedKeeping) shouldBe 0

        val leftDetail = getBond(left.other, left.bondId)
        leftDetail.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        listOf(blocked, leftSilently, leftWithdrawing, blockedKeeping).forEach { other ->
            withClue("the 204 itself") {
                other.ending.status shouldBe left.ending.status
                other.ending.contentAsString shouldBe ""
                left.ending.contentAsString shouldBe ""
                headersOf(other.ending) shouldBe headersOf(left.ending)
            }
            val detail = getBond(other.other, other.bondId)
            withClue("GET /bonds/{id} as the other member") {
                detail.status shouldBe leftDetail.status
                detail.getHeader(HttpHeaders.ETAG)!!.substringBefore('-') shouldBe
                    leftDetail.getHeader(HttpHeaders.ETAG)!!.substringBefore('-')
                headersOf(detail).keys shouldBe headersOf(leftDetail).keys
                normalise(detail.contentAsString) shouldBe normalise(leftDetail.contentAsString)
            }
            withClue("GET /bonds as the other member") {
                normalise(listBonds(other.other).contentAsString) shouldBe normalise(listBonds(left.other).contentAsString)
            }
        }
    }

    @Test
    fun `a withdrawal made after the other member has already left changes nothing they can see of the bond`() {
        // The asymmetric order again, now with the withdrawal: Bea leaves, Ada
        // blocks and takes her entries back. Block is the only thing an ended
        // bond accepts, so anything of the bond that moved for Bea here could
        // mean only one thing.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        post(bea, "/api/v1/bonds/$bondId/leave").status shouldBe 204
        val before = getBond(bea, bondId)
        val listedBefore = listBonds(bea)

        post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""").status shouldBe 204

        val after = getBond(bea, bondId)
        withClue("what Bea sees of the bond after Ada withdrew") {
            after.status shouldBe before.status
            after.getHeader(HttpHeaders.ETAG) shouldBe before.getHeader(HttpHeaders.ETAG)
            after.contentAsString shouldBe before.contentAsString
            listBonds(bea).contentAsString shouldBe listedBefore.contentAsString
        }
        withdrawalsIn(bondId) shouldBe 1
    }

    @Test
    fun `a leave and a block each ask no shared-setting push, close the proposal alike and call off every owed push`() {
        val left = endedWithPushesOwed { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedWithPushesOwed { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""") }

        listOf(left, blocked).forEach { run ->
            // The fixture is what it says: Ben was owed all three, held, when the bond ended.
            run.owedBefore shouldBe
                mapOf("PROPOSAL_OPENED" to "HELD", "REVEAL" to "HELD", "SHARED_SETTING_CHANGED" to "HELD")

            withClue("no SHARED_SETTING_CHANGED row for anyone but the one asked before the ending") {
                run.sharedRowsAfter shouldBe run.sharedRowsBefore
            }
            withClue("the pending proposal, closed by the ending and by no member") {
                run.proposals shouldBe listOf(Triple("BOND_ENDED", null, true))
                run.closedProposals shouldBe listOf("BOND_ENDED" to null)
            }
            withClue("every owed row of the bond") {
                run.ledgerAfter.map { it["status"] } shouldBe List(run.ledgerAfter.size) { "CANCELLED" }
                run.cancelledBondEnded shouldBe 3.0
            }
        }
    }

    @Test
    fun `the remaining member's ledger and bond read the same after a leave and after a block`() {
        val left = endedWithPushesOwed { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedWithPushesOwed { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":true}""") }

        // Both are real: three rows to compare, and a bond that names how its proposal ended.
        left.ledgerAfter.size shouldBe 3
        left.detail.contentAsString shouldContain "\"outcome\":\"BOND_ENDED\""

        withClue("the ledger: every column but ids and times, and the metric it moved") {
            blocked.ledgerAfter shouldBe left.ledgerAfter
            blocked.cancelledBondEnded shouldBe left.cancelledBondEnded
        }
        withClue("GET /bonds/{id} as the remaining member") {
            blocked.detail.status shouldBe left.detail.status
            blocked.detail.getHeader(HttpHeaders.ETAG)!!.substringBefore('-') shouldBe
                left.detail.getHeader(HttpHeaders.ETAG)!!.substringBefore('-')
            normalise(blocked.detail.contentAsString) shouldBe normalise(left.detail.contentAsString)
        }
        withClue("GET /bonds as the remaining member") {
            normalise(blocked.list.contentAsString) shouldBe normalise(left.list.contentAsString)
        }
    }

    /**
     * What the remaining member and the ledger show once Ada's ending has been delivered, on a bond
     * where Ada has a zone proposal pending and Ben (Bea here) is owed three pushes, all `HELD` by
     * his quiet hours: the opening of Ada's proposal, a reveal, and the push for Ada's name change.
     */
    private data class Exit(
        val owedBefore: Map<String, String>,
        val sharedRowsBefore: List<Map<String, Any?>>,
        val sharedRowsAfter: List<Map<String, Any?>>,
        val proposals: List<Triple<String?, UUID?, Boolean>>,
        val closedProposals: List<Pair<String, String?>>,
        val ledgerAfter: List<Map<String, Any?>>,
        val cancelledBondEnded: Double,
        val detail: MockHttpServletResponse,
        val list: MockHttpServletResponse,
    )

    private fun endedWithPushesOwed(ending: (UUID, String) -> MockHttpServletResponse): Exit {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        dispatchAll()
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        inQuietHours(bea, bondId)

        // Ada's name change: Bea's SETTING push is asked for, and held.
        patchName(ada, bondId, "Ours").status shouldBe 200
        // Ada's pending zone proposal: Bea's opening push, held.
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"Europe/London"}"""
            }.andReturn()
            .response.status shouldBe 200
        // A reveal, held by the same quiet hours.
        transactions.executeWithoutResult { notifications.request(heldReveal(bea, bondId)) }
        dispatchAll()

        val owedBefore = statusByCategory()
        val sharedBefore = sharedRows()
        val cancelled = cancelledBondEndedCount()
        val response = ending(ada, bondId)
        response.status shouldBe 204
        dispatchAll()

        return Exit(
            owedBefore = owedBefore,
            sharedRowsBefore = sharedBefore,
            sharedRowsAfter = sharedRows(),
            proposals =
                jdbc.query(
                    "SELECT cancel_cause, cancelled_by_member_id, expires_at > now() FROM bond_proposals WHERE bond_id = ?",
                    { row, _ -> Triple(row.getString(1), row.getObject(2, UUID::class.java), row.getBoolean(3)) },
                    UUID.fromString(bondId),
                ),
            closedProposals = closedProposalsOf(getBond(bea, bondId).contentAsString),
            ledgerAfter = ledger(ada, bea),
            cancelledBondEnded = cancelledBondEndedCount() - cancelled,
            detail = getBond(bea, bondId),
            list = listBonds(bea),
        )
    }

    private fun closedProposalsOf(body: String): List<Pair<String, String?>> =
        Regex(""""outcome":"(\w+)","closedByMemberId":(null|"[^"]+")""")
            .findAll(body)
            .map { it.groupValues[1] to it.groupValues[2].takeIf { id -> id != "null" } }
            .toList()

    private fun statusByCategory(): Map<String, String> =
        jdbc.queryForList("SELECT category, status FROM notifications").associate { it["category"] as String to it["status"] as String }

    private fun sharedRows(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT id, user_id, status FROM notifications WHERE category = 'SHARED_SETTING_CHANGED' ORDER BY id")
            .map { it - "status" }

    /** Every column of the bond's ledger but its ids and its times, the users named by who they are. */
    private fun ledger(
        ada: UUID,
        bea: UUID,
    ): List<Map<String, Any?>> {
        val ids = mutableMapOf<String, String>()
        val uuid = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        return jdbc
            .queryForList("SELECT * FROM notifications ORDER BY category, user_id")
            .map { row ->
                (row - setOf("id", "bond_id", "created_at", "sent_at", "not_before", "stale_after"))
                    .mapValues { (column, value) ->
                        when (column) {
                            "user_id" -> if (value == ada) "ada" else if (value == bea) "bea" else "other"
                            "dedupe_key" -> uuid.replace(value as String) { ids.getOrPut(it.value) { "id-${ids.size}" } }
                            else -> value
                        }
                    }
            }.sortedBy { "${it["category"]}/${it["user_id"]}" }
    }

    private fun cancelledBondEndedCount(): Double =
        PushCategory.entries.sumOf {
            meters
                .counter("notification.push.cancelled", "category", it.name, "nudge", "none", "reason", "bond_ended")
                .count()
        }

    private fun heldReveal(
        userId: UUID,
        bondId: String,
    ): PushRequest {
        val now = clock.instant().atZone(ZoneId.of(QUIET_ZONE)).toLocalTime()
        return PushRequest(
            userId = userId,
            bondId = UUID.fromString(bondId),
            category = PushCategory.REVEAL,
            dedupeKey = "reveal:discreet-exit:$userId",
            staleAfter = clock.instant().plus(Duration.ofHours(STALE_HOURS)),
            recipient =
                PushRecipient(
                    zone = ZoneId.of(QUIET_ZONE),
                    quietHours = QuietHoursWindow(now.minusHours(1).withNano(0), now.plusHours(1).withNano(0)),
                    preferences = NotificationPreferences(),
                ),
            bondDate = null,
            nudge = null,
            sharedChange = null,
        )
    }

    /** Quiet hours around now in [userId]'s reminder zone, so a push to them is held. */
    private fun inQuietHours(
        userId: UUID,
        bondId: String,
    ) {
        val now = clock.instant().atZone(ZoneId.of(QUIET_ZONE)).toLocalTime()
        jdbc.update(
            "UPDATE bond_members SET reminder_timezone = ?, quiet_hours_start = ?, quiet_hours_end = ? WHERE bond_id = ? AND user_id = ?",
            QUIET_ZONE,
            java.sql.Time.valueOf(now.minusHours(1).withNano(0)),
            java.sql.Time.valueOf(now.plusHours(1).withNano(0)),
            UUID.fromString(bondId),
            userId,
        )
    }

    private fun patchName(
        userId: UUID,
        bondId: String,
        name: String,
    ): MockHttpServletResponse {
        val etag = getBond(userId, bondId).getHeader(HttpHeaders.ETAG)!!
        return mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_MATCH, etag)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"$name"}"""
            }.andReturn()
            .response
    }

    /** Runs the outbox until it has nothing due: the change, then the pushes it asked for. */
    private fun dispatchAll() {
        repeat(DISPATCH_ROUNDS) { dispatcher.dispatchDue(clock.instant().plusSeconds(1), BUDGET) }
    }

    /**
     * A bond built the same way every time: Ada creates it, Bea joins, and then
     * [ending] finishes it. The display names are fixed so that two of these
     * differ only in their ids and timestamps.
     */
    private fun endedBond(ending: (UUID, String) -> MockHttpServletResponse): Ended {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        val response = ending(ada, bondId)
        response.status shouldBe 204
        return Ended(bondId = bondId, other = bea, ending = response)
    }

    private data class Ended(
        val bondId: String,
        val other: UUID,
        val ending: MockHttpServletResponse,
    )

    /**
     * Replaces what two different bonds cannot share — uuids and timestamps —
     * with positional placeholders, so the *n*th id in one body is compared with
     * the *n*th in the other. Anything else that differs is a real difference,
     * which is this file's whole subject.
     */
    private fun normalise(body: String): String {
        val ids = mutableMapOf<String, String>()
        return body
            .replace(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")) { match ->
                ids.getOrPut(match.value) { "uuid-${ids.size}" }
            }.replace(Regex("""\d{4}-\d{2}-\d{2}T[0-9:.]+Z"""), "timestamp")
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

    private fun post(
        userId: UUID,
        path: String,
        body: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
            }.andReturn()
            .response

    private fun withdrawalsIn(ended: Ended): Int = withdrawalsIn(ended.bondId)

    private fun withdrawalsIn(bondId: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?",
            Int::class.java,
            UUID.fromString(bondId),
        )!!

    /** Every response header, by name. The `ETag` is compared apart: beside the version it digests ids two bonds cannot share. */
    private fun headersOf(response: MockHttpServletResponse): Map<String, List<String>> =
        response.headerNames
            .filter { it != HttpHeaders.ETAG }
            .associateWith { response.getHeaders(it) }
            .toSortedMap()

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun listBonds(userId: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        const val BUDGET = 100
        const val DISPATCH_ROUNDS = 3
        const val STALE_HOURS = 24L

        /** A region zone (a member's zone must be one) that has no daylight saving. */
        const val QUIET_ZONE = "Atlantic/Reykjavik"
    }
}
