package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `after a leave and after a block, the other member sees exactly the same thing`() {
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }

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
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }

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

        post(ada, "/api/v1/bonds/$bondId/block").status shouldBe 204

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
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }

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
        val blockedSilently = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }
        val leftWithdrawing = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave", """{"withdrawEntries":true}""") }
        val blockedKeeping = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block", """{"withdrawEntries":false}""") }

        // What is being compared really differs underneath.
        withdrawalsIn(left) shouldBe 0
        withdrawalsIn(blocked) shouldBe 1
        withdrawalsIn(leftSilently) shouldBe 0
        withdrawalsIn(blockedSilently) shouldBe 1
        withdrawalsIn(leftWithdrawing) shouldBe 1
        withdrawalsIn(blockedKeeping) shouldBe 0

        val leftDetail = getBond(left.other, left.bondId)
        leftDetail.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        listOf(blocked, leftSilently, blockedSilently, leftWithdrawing, blockedKeeping).forEach { other ->
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
}
