package com.moyi.bond.service

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.MemberRole
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.SharedChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.delete
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
 * What `bond` publishes for a shared change (spec §4, ADR-0069), read from the outbox it is
 * written to: `BondSettingsChanged` and `ProposalClosed`, once per change, each naming the
 * member who acted; nothing for a change of no value, a lapse, a lone member, or a bond ending.
 * And ending a bond calls off every push of it still owed, in the ending's own transaction.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
@Suppress("LongParameterList", "TooManyFunctions") // A test's collaborators and its routes, each named.
internal class SharedChangeEventsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val notifications: Notifications,
    @Autowired private val endBond: EndBond,
    @Autowired private val clock: Clock,
    @Autowired transactionManager: PlatformTransactionManager,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(transactionManager)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    // --- BondSettingsChanged ---

    @Test
    fun `a PATCH that changes a value publishes one BondSettingsChanged naming the actor`() {
        val bond = pairedBond()

        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200

        events(BondSettingsChanged.TYPE) shouldBe listOf(mapOf("bondId" to bond, "memberId" to memberId(ada, bond)))
        events(ProposalClosed.TYPE).shouldBeEmpty()
    }

    @Test
    fun `a PATCH with the same values publishes nothing`() {
        val bond = pairedBond()

        patch(ada, bond, """{"name":"Us","type":"COUPLE"}""").status shouldBe 200

        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    @Test
    fun `turning search off with a request live is one BondSettingsChanged and no ProposalClosed`() {
        val bond = pairedBond()
        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 202
        // Defensive: the request cannot be live while search is on, so the fixture puts it there.
        jdbc.update("UPDATE bonds SET semantic_search_enabled = true WHERE id = ?", UUID.fromString(bond))
        clearOutbox()

        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200

        events(BondSettingsChanged.TYPE) shouldBe listOf(mapOf("bondId" to bond, "memberId" to memberId(ada, bond)))
        events(ProposalClosed.TYPE).shouldBeEmpty()
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancelled_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `calling off an agreed deletion publishes one BondSettingsChanged naming the actor`() {
        val bond = pairedBond()
        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        post(bea, "/bonds/$bond/deletion-request").status shouldBe 202
        clearOutbox()

        delete(bea, "/bonds/$bond/deletion-request").status shouldBe 204

        events(BondSettingsChanged.TYPE) shouldBe listOf(mapOf("bondId" to bond, "memberId" to memberId(bea, bond)))
        events(ProposalClosed.TYPE).shouldBeEmpty()
    }

    // --- ProposalClosed ---

    @Test
    fun `a zone change confirmed publishes one ProposalClosed naming the confirmer`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        val proposal = proposalId(bond)
        clearOutbox()

        confirm(bea, bond, proposal).status shouldBe 200

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, proposal, bea))
        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    @Test
    fun `a zone change withdrawn, or declined, publishes one ProposalClosed naming whoever called it off`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        val withdrawn = proposalId(bond)
        delete(ada, "/bonds/$bond/timezone").status shouldBe 204

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, withdrawn, ada))

        clearOutbox()
        propose(ada, bond).status shouldBe 200
        val declined = latestProposalId(bond)
        delete(bea, "/bonds/$bond/timezone").status shouldBe 204

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, declined, bea))
    }

    @Test
    fun `a deletion agreed publishes one ProposalClosed naming the member who agreed`() {
        val bond = pairedBond()
        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        val proposal = proposalId(bond)
        // Asking twice is not a second change.
        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        clearOutbox()

        post(bea, "/bonds/$bond/deletion-request").status shouldBe 202
        // Asking again once it is counting down changes nothing either.
        post(bea, "/bonds/$bond/deletion-request").status shouldBe 202

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, proposal, bea))
        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    @Test
    fun `a deletion request withdrawn publishes one ProposalClosed and no BondSettingsChanged`() {
        val bond = pairedBond()
        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        val proposal = proposalId(bond)
        clearOutbox()

        delete(ada, "/bonds/$bond/deletion-request").status shouldBe 204

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, proposal, ada))
        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    @Test
    fun `a search request agreed, or called off, publishes one ProposalClosed naming the actor`() {
        val bond = pairedBond()
        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        val declined = proposalId(bond)
        clearOutbox()
        delete(bea, "/bonds/$bond/semantic-search").status shouldBe 204

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, declined, bea))

        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        val agreed = latestProposalId(bond)
        clearOutbox()
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 202
        // Already on: nothing to ask for, and nothing to tell.
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 202

        events(ProposalClosed.TYPE) shouldBe listOf(closed(bond, agreed, bea))
        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    // --- what publishes nothing ---

    @Test
    fun `a leave or a block that closes a live proposal publishes no ProposalClosed`() {
        val left = pairedBond()
        val blocked = pairedBond(invitee = users.verified("Cy"))
        propose(ada, left).status shouldBe 200
        propose(ada, blocked).status shouldBe 200
        clearOutbox()

        post(bea, "/bonds/$left/leave").status shouldBe 204
        post(ada, "/bonds/$blocked/block", """{"withdrawEntries":false}""").status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancel_cause = 'BOND_ENDED'", Int::class.java) shouldBe 2
        events(ProposalClosed.TYPE).shouldBeEmpty()
        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    @Test
    fun `a lapsed proposal closed by a new one publishes no ProposalClosed`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        jdbc.update("UPDATE bond_proposals SET proposed_at = now() - interval '9 days', expires_at = now() - interval '2 days'")
        clearOutbox()

        propose(ada, bond, "Asia/Tokyo").status shouldBe 200

        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancel_cause = 'LAPSED'", Int::class.java) shouldBe 1
        events(ProposalClosed.TYPE).shouldBeEmpty()
    }

    @Test
    fun `a lone member's own deletion publishes nothing`() {
        val bond = bondIdOf(createBond(ada))

        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202

        events(ProposalClosed.TYPE).shouldBeEmpty()
        events(BondSettingsChanged.TYPE).shouldBeEmpty()
    }

    // --- ending a bond calls off its owed pushes ---

    @Test
    fun `a leave calls off every push of the bond still owed, and no other bond's`() {
        val bond = pairedBond()
        val other = pairedBond(invitee = users.verified("Cy"))
        seedOwedPush(bea, bond, "seeded:left")
        seedOwedPush(ada, other, "seeded:other")

        post(ada, "/bonds/$bond/leave").status shouldBe 204

        pushStatus("seeded:left") shouldBe "CANCELLED"
        pushStatus("seeded:other") shouldBeIn OWED
    }

    @Test
    fun `a block calls off every push of the bond still owed`() {
        val bond = pairedBond()
        seedOwedPush(ada, bond, "seeded:blocked")

        post(bea, "/bonds/$bond/block", """{"withdrawEntries":false}""").status shouldBe 204

        pushStatus("seeded:blocked") shouldBe "CANCELLED"
    }

    @Test
    fun `a leave that rolls back calls off nothing`() {
        val bond = pairedBond()
        seedOwedPush(bea, bond, "seeded:kept")
        val membership = membershipOf(ada, bond)

        transactions.executeWithoutResult { status ->
            endBond.leave(membership, withdrawEntries = false)
            // Inside the leave's transaction it is called off; the rollback must take that with it.
            pushStatus("seeded:kept") shouldBe "CANCELLED"
            status.setRollbackOnly()
        }

        pushStatus("seeded:kept") shouldBeIn OWED
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?", String::class.java, UUID.fromString(bond)) shouldBe "ACTIVE"
    }

    // --- fixtures ---

    private fun closed(
        bond: String,
        proposal: UUID,
        actor: UUID,
    ): Map<String, String> = mapOf("bondId" to bond, "proposalId" to proposal.toString(), "memberId" to memberId(actor, bond))

    /** Each event of [type] in the outbox, as its references. */
    private fun events(type: String): List<Map<String, String>> =
        jdbc
            .queryForList("SELECT payload::text FROM outbox_events WHERE event_type = ? ORDER BY occurred_at", String::class.java, type)
            .map { payload ->
                Regex(""""(\w+)":\s*"([0-9a-f-]{36})"""").findAll(payload!!).associate { it.groupValues[1] to it.groupValues[2] }
            }

    private fun clearOutbox() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
    }

    /** A shared-setting push to [userId] in [bond], owed: it settles for two minutes before it may go. */
    private fun seedOwedPush(
        userId: UUID,
        bond: String,
        key: String,
    ) {
        transactions.execute {
            notifications.request(
                PushRequest(
                    userId = userId,
                    bondId = UUID.fromString(bond),
                    category = PushCategory.SHARED_SETTING_CHANGED,
                    dedupeKey = key,
                    staleAfter = clock.instant().plus(Duration.ofDays(1)),
                    recipient = PushRecipient(ZoneId.of("Europe/London"), null, NotificationPreferences.DEFAULTS),
                    bondDate = null,
                    nudge = null,
                    sharedChange = SharedChange.SETTING,
                ),
            )
        } shouldBe true
        pushStatus(key) shouldBeIn OWED
    }

    private fun pushStatus(key: String): String =
        jdbc.queryForObject("SELECT status FROM notifications WHERE dedupe_key = ?", String::class.java, key)!!

    private fun memberId(
        userId: UUID,
        bond: String,
    ): String =
        jdbc
            .queryForObject(
                "SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?",
                UUID::class.java,
                UUID.fromString(bond),
                userId,
            ).toString()

    private fun membershipOf(
        userId: UUID,
        bond: String,
    ): Membership =
        jdbc.queryForObject(
            "SELECT id, role FROM bond_members WHERE bond_id = ? AND user_id = ?",
            { row, _ ->
                Membership(
                    bondId = BondId(UUID.fromString(bond)),
                    memberId = MemberId(row.getObject("id", UUID::class.java)),
                    userId = UserId(userId),
                    role = MemberRole.valueOf(row.getString("role")),
                    left = false,
                )
            },
            UUID.fromString(bond),
            userId,
        )

    private fun proposalId(bond: String): UUID =
        jdbc.queryForObject("SELECT id FROM bond_proposals WHERE bond_id = ?", UUID::class.java, UUID.fromString(bond))!!

    private fun latestProposalId(bond: String): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_proposals WHERE bond_id = ? ORDER BY proposed_at DESC LIMIT 1",
            UUID::class.java,
            UUID.fromString(bond),
        )!!

    private fun pairedBond(invitee: UUID = bea): String {
        val created = createBond(ada)
        val bond = bondIdOf(created)
        mockMvc
            .post("/api/v1/invites/${codeOf(created)}/accept") { header(HttpHeaders.AUTHORIZATION, bearer(invitee)) }
            .andReturn()
            .response.status shouldBe 200
        clearOutbox()
        return bond
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun patch(
        userId: UUID,
        bond: String,
        body: String,
    ): MockHttpServletResponse {
        val etag =
            mockMvc
                .get("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
                .andReturn()
                .response
                .getHeader(HttpHeaders.ETAG)!!
        return mockMvc
            .patch("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_MATCH, etag)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response
    }

    private fun propose(
        userId: UUID,
        bond: String,
        zone: String = "Europe/London",
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bond/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun confirm(
        userId: UUID,
        bond: String,
        proposal: UUID,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposal"}"""
            }.andReturn()
            .response

    private fun post(
        userId: UUID,
        path: String,
        body: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1$path") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = it
                }
            }.andReturn()
            .response

    private fun delete(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse = mockMvc.delete("/api/v1$path") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        val OWED = listOf("REQUESTED", "HELD")
    }
}
