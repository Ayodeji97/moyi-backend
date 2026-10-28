package com.moyi.bond.service

import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * `bond.notify` (spec §4 N3, ADR-0046): a partner joining and a proposal
 * opening, each told to **the other member only**, once, and nothing on a
 * bond that has ended. Read from the ledger the push consumer reads
 * (`notifications`), through the real outbox.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class BondPushesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val consumer: NotifyBondMembers,
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
        // Every delivery due before this test is dispatched first and thrown away with the rows:
        // another class's events must not land in this one's ledger.
        dispatchAll()
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @Test
    fun `a partner joining is told to the member who was there, and never to the joiner`() {
        val bond = pairedBond()

        dispatchAll()

        pushes() shouldBe listOf(Push(ada, "PARTNER_JOINED", "joined:$bond:$ada"))
        // Ids only, on the event as on the push: the joiner's member id says whom not to tell.
        eventReferences("MemberJoined") shouldBe setOf("bondId", "memberId")
    }

    @Test
    fun `the join delivered twice asks for one push`() {
        pairedBond()
        val event = received("MemberJoined")

        handle(event)
        handle(event)

        pushes().size shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 1
    }

    @Test
    fun `a bond that ended before the join was told tells nobody`() {
        val bond = pairedBond()
        leave(bea, bond).status shouldBe 204

        dispatchAll()

        pushes().shouldBeEmpty()
    }

    @Test
    fun `a zone change waiting on the other member is told to them only, once, and never again`() {
        val bond = pairedBond()
        dispatchAll()
        clearPushes()

        propose(ada, bond, "Europe/London").status shouldBe 200
        dispatchAll()
        val proposal = proposalId(bond)

        pushes() shouldBe listOf(Push(bea, "PROPOSAL_OPENED", "proposal:$proposal:$bea"))
        pushRow(bea)["stale_after"] shouldBe Timestamp.from(proposedAt(proposal).plus(Duration.ofHours(24)))
        eventReferences("ProposalOpened") shouldBe setOf("bondId", "proposalId", "proposerId")

        // Delivered again, and the outbox run again: no second push, no reminder.
        handle(received("ProposalOpened"))
        dispatchAll()
        pushes().size shouldBe 1
    }

    @Test
    fun `a deletion request waiting on the other member is told to them only`() {
        val bond = pairedBond()
        dispatchAll()
        clearPushes()

        requestDeletion(bea, bond).status shouldBe 202
        dispatchAll()

        pushes() shouldBe listOf(Push(ada, "PROPOSAL_OPENED", "proposal:${proposalId(bond)}:$ada"))
    }

    @Test
    fun `a creator alone asks nobody, so nothing is told`() {
        // A lone member's zone change applies at once and their deletion confirms itself:
        // neither waits on anybody, and neither publishes ProposalOpened.
        val bond = bondIdOf(createBond(ada))

        propose(ada, bond, "Europe/London").status shouldBe 200
        requestDeletion(ada, bond).status shouldBe 202
        dispatchAll()

        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'ProposalOpened'", Int::class.java) shouldBe 0
        pushes().shouldBeEmpty()
    }

    @Test
    fun `a proposal called off before it was told is told to nobody`() {
        val bond = pairedBond()
        dispatchAll()
        clearPushes()

        propose(ada, bond, "Europe/London").status shouldBe 200
        cancel(ada, bond).status shouldBe 204
        dispatchAll()

        pushes().shouldBeEmpty()
    }

    @Test
    fun `a member's own toggle is carried with their push, and the other's is not read`() {
        val bond = pairedBond()
        dispatchAll()
        clearPushes()
        // Ada turns proposals off; Bea proposes, so the push is Ada's and is recorded unwanted.
        notifications(ada, bond, """{"categories":{"proposal":false}}""")

        propose(bea, bond, "Europe/London").status shouldBe 200
        dispatchAll()

        pushRow(ada)["wanted"] shouldBe false
        pushRow(ada)["status"] shouldBe "SUPPRESSED"
    }

    private data class Push(
        val userId: UUID,
        val category: String,
        val dedupeKey: String,
    )

    private fun pushes(): List<Push> =
        jdbc.query("SELECT user_id, category, dedupe_key FROM notifications ORDER BY created_at, user_id") { row, _ ->
            Push(row.getObject("user_id", UUID::class.java), row.getString("category"), row.getString("dedupe_key"))
        }

    private fun pushRow(userId: UUID): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ?", userId)

    private fun clearPushes() {
        jdbc.execute("TRUNCATE TABLE notifications")
    }

    private fun proposalId(bond: String): UUID =
        jdbc.queryForObject("SELECT id FROM bond_proposals WHERE bond_id = ?", UUID::class.java, UUID.fromString(bond))!!

    private fun proposedAt(proposal: UUID) =
        jdbc.queryForObject("SELECT proposed_at FROM bond_proposals WHERE id = ?", Timestamp::class.java, proposal)!!.toInstant()

    private fun eventReferences(type: String): Set<String> =
        jdbc
            .queryForList("SELECT jsonb_object_keys(payload) FROM outbox_events WHERE event_type = ?", String::class.java, type)
            .filterNotNull()
            .toSet()

    /** The event as the dispatcher hands it over, read back from the outbox. */
    private fun received(type: String): ReceivedEvent =
        jdbc.queryForObject(
            "SELECT id, aggregate_type, aggregate_id, payload::text AS payload, occurred_at FROM outbox_events WHERE event_type = ?",
            { row, _ ->
                ReceivedEvent(
                    id = row.getObject("id", UUID::class.java),
                    aggregateType = row.getString("aggregate_type"),
                    aggregateId = row.getObject("aggregate_id", UUID::class.java),
                    eventType = type,
                    references =
                        Regex(""""(\w+)":\s*"([0-9a-f-]{36})"""")
                            .findAll(row.getString("payload"))
                            .associate { it.groupValues[1] to UUID.fromString(it.groupValues[2]) },
                    occurredAt = row.getTimestamp("occurred_at").toInstant(),
                )
            },
            type,
        )!!

    private fun handle(event: ReceivedEvent) {
        transactions.executeWithoutResult { consumer.handle(event) }
    }

    /** Runs the outbox until it has nothing due: the join or the proposal, then the pushes it asked for. */
    private fun dispatchAll() {
        repeat(DISPATCH_ROUNDS) { dispatcher.dispatchDue(clock.instant().plusSeconds(1), BUDGET) }
    }

    private fun pairedBond(): String {
        val created = createBond(ada)
        val bond = bondIdOf(created)
        mockMvc
            .post("/api/v1/invites/${codeOf(created)}/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
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

    private fun propose(
        userId: UUID,
        bond: String,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bond/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun cancel(
        userId: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc.delete("/api/v1/bonds/$bond/timezone") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun requestDeletion(
        userId: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bond/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leave(
        userId: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/leave") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
            }.andReturn()
            .response

    private fun notifications(
        userId: UUID,
        bond: String,
        body: String,
    ) {
        mockMvc
            .put("/api/v1/bonds/$bond/members/me/notifications") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        const val BUDGET = 100
        const val DISPATCH_ROUNDS = 3
    }
}
