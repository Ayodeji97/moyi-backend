package com.moyi.bond.service

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.MemberRole
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Time
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `bond.notify`'s shared-setting push (issue #205, spec §3 B9–B11, §4, ADR-0069): a shared change,
 * or a proposal answered or withdrawn, told to **every member who has not left except the one who
 * acted**, keyed `shared:{eventId}:{userId}`, with the `change` the row reads. Nothing on a Bond
 * that has ended, by either order against a leave. Read from the ledger the push consumer reads,
 * through the real outbox and the real consumer.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass") // A test's collaborators and its routes, each named.
internal class SharedSettingPushesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val consumer: NotifyBondMembers,
    @Autowired private val meters: MeterRegistry,
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
        // Every delivery due before this test is dispatched and thrown away with the rows.
        dispatchAll()
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    // --- ruling 7: a shared setting ---

    @Test
    fun `a setting changed is told to the other member, as SETTING, under the event's key, and never to the actor`() {
        val bond = pairedBond()

        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        dispatchAll()

        shared() shouldBe listOf(Shared(bea, "shared:${eventId(BondSettingsChanged.TYPE)}:$bea", "SETTING"))
        sharedRow(bea)["status"] shouldBeIn OWED
        sharedRow(bea)["stale_after"] shouldBe Timestamp.from(occurredAt(BondSettingsChanged.TYPE).plus(Duration.ofHours(24)))
    }

    @Test
    fun `a PATCH of the same values asks nothing`() {
        val bond = pairedBond()

        patch(ada, bond, """{"name":"Us","type":"COUPLE"}""").status shouldBe 200
        dispatchAll()

        shared().shouldBeEmpty()
    }

    @Test
    fun `calling off an agreed deletion is a SETTING for the other member, and nothing for the one who called it off`() {
        val bond = pairedBond()
        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        post(bea, "/bonds/$bond/deletion-request").status shouldBe 202
        dispatchAll()
        clearPushes()

        delete(ada, "/bonds/$bond/deletion-request").status shouldBe 204
        dispatchAll()

        shared() shouldBe listOf(Shared(bea, "shared:${eventId(BondSettingsChanged.TYPE)}:$bea", "SETTING"))
    }

    @Test
    fun `turning search off with a request live is one SETTING for the other member`() {
        val bond = pairedBond()
        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 202
        // Defensive: the request cannot be live while search is on, so the fixture puts it there.
        jdbc.update("UPDATE bonds SET semantic_search_enabled = true WHERE id = ?", UUID.fromString(bond))
        dispatchAll()
        clearPushes()
        clearOutbox()

        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200
        dispatchAll()

        shared() shouldBe listOf(Shared(bea, "shared:${eventId(BondSettingsChanged.TYPE)}:$bea", "SETTING"))
    }

    // --- ruling 9: every proposal transition ---

    @Test
    fun `a proposal made asks no shared-setting push, only the opening one`() {
        val bond = pairedBond()

        propose(ada, bond).status shouldBe 200
        dispatchAll()

        shared().shouldBeEmpty()
        categories() shouldBe listOf("PROPOSAL_OPENED")
    }

    @Test
    fun `a zone change accepted is told to the proposer as PROPOSAL_ACCEPTED, and not to the member who accepted`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        dispatchAll()

        confirm(bea, bond, proposalId(bond)).status shouldBe 200
        dispatchAll()

        shared() shouldBe listOf(Shared(ada, "shared:${eventId(ProposalClosed.TYPE)}:$ada", "PROPOSAL_ACCEPTED"))
    }

    @Test
    fun `a zone change declined is told to the proposer as PROPOSAL_DECLINED`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        dispatchAll()

        delete(bea, "/bonds/$bond/timezone").status shouldBe 204
        dispatchAll()

        shared() shouldBe listOf(Shared(ada, "shared:${eventId(ProposalClosed.TYPE)}:$ada", "PROPOSAL_DECLINED"))
    }

    @Test
    fun `a zone change withdrawn after its opening push went is told to the other member as PROPOSAL_WITHDRAWN`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        dispatchAll()
        // Its opening push went (it is no longer owed), so Bea was told of the proposal.
        openingStatus(bond, bea) shouldBeNotIn OWED

        delete(ada, "/bonds/$bond/timezone").status shouldBe 204
        dispatchAll()

        shared() shouldBe listOf(Shared(bea, "shared:${eventId(ProposalClosed.TYPE)}:$bea", "PROPOSAL_WITHDRAWN"))
    }

    @Test
    fun `a search request agreed is told to the proposer, and declined likewise`() {
        val bond = pairedBond()
        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200
        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        dispatchAll()
        clearPushes()
        clearOutbox()

        delete(bea, "/bonds/$bond/semantic-search").status shouldBe 204
        dispatchAll()
        shared() shouldBe listOf(Shared(ada, "shared:${eventId(ProposalClosed.TYPE)}:$ada", "PROPOSAL_DECLINED"))

        post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
        dispatchAll()
        clearPushes()
        clearOutbox()
        post(bea, "/bonds/$bond/semantic-search").status shouldBe 202
        dispatchAll()
        shared() shouldBe listOf(Shared(ada, "shared:${eventId(ProposalClosed.TYPE)}:$ada", "PROPOSAL_ACCEPTED"))
    }

    @Test
    fun `the deletion agreement reaches the proposer while the bond counts down`() {
        // Ruling 11: PENDING_DELETION takes no writes and has not ended. Red under a takesWrites gate.
        val bond = pairedBond()
        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        dispatchAll()

        post(bea, "/bonds/$bond/deletion-request").status shouldBe 202
        dispatchAll()

        bondStatus(bond) shouldBe "PENDING_DELETION"
        shared() shouldBe listOf(Shared(ada, "shared:${eventId(ProposalClosed.TYPE)}:$ada", "PROPOSAL_ACCEPTED"))
    }

    @Test
    fun `a proposal lapsed tells nobody`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        jdbc.update("UPDATE bond_proposals SET proposed_at = now() - interval '9 days', expires_at = now() - interval '2 days'")
        dispatchAll()
        clearPushes()

        // A new proposal closes the lapsed one in the way (LAPSED): only its own opening push.
        propose(ada, bond, "Asia/Tokyo").status shouldBe 200
        dispatchAll()

        shared().shouldBeEmpty()
    }

    @Test
    fun `a proposal closed by the bond ending tells nobody`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        dispatchAll()
        clearPushes()

        post(ada, "/bonds/$bond/leave").status shouldBe 204
        dispatchAll()

        shared().shouldBeEmpty()
    }

    @Test
    fun `a lone member's change tells nobody, there being no one else`() {
        val bond = bondIdOf(createBond(ada))

        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        dispatchAll()

        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, BondSettingsChanged.TYPE) shouldBe 1
        shared().shouldBeEmpty()
    }

    @Test
    fun `a lone member's own deletion tells nobody`() {
        val bond = bondIdOf(createBond(ada))

        post(ada, "/bonds/$bond/deletion-request").status shouldBe 202
        dispatchAll()

        shared().shouldBeEmpty()
    }

    // --- ruling 10: the opening push called off ---

    @Test
    fun `a withdrawal while the other member's opening push is held calls it off and tells them nothing`() {
        val bond = pairedBond()
        inQuietHours(bea, bond)
        propose(ada, bond).status shouldBe 200
        dispatchAll()
        openingStatus(bond, bea) shouldBe "HELD"
        val calledOff = cancelledForProposalClosed()
        val neverTold = sharedCount("proposal_withdrawn", NotifyBondMembers.NEVER_TOLD)

        delete(ada, "/bonds/$bond/timezone").status shouldBe 204
        dispatchAll()

        openingStatus(bond, bea) shouldBe "CANCELLED"
        cancelledForProposalClosed() shouldBe calledOff + 1
        shared().shouldBeEmpty()
        sharedCount("proposal_withdrawn", NotifyBondMembers.NEVER_TOLD) shouldBe neverTold + 1
    }

    @Test
    fun `an acceptance calls off the accepter's own held opening push`() {
        val bond = pairedBond()
        inQuietHours(bea, bond)
        propose(ada, bond).status shouldBe 200
        dispatchAll()
        openingStatus(bond, bea) shouldBe "HELD"

        confirm(bea, bond, proposalId(bond)).status shouldBe 200
        dispatchAll()

        openingStatus(bond, bea) shouldBe "CANCELLED"
        shared() shouldBe listOf(Shared(ada, "shared:${eventId(ProposalClosed.TYPE)}:$ada", "PROPOSAL_ACCEPTED"))
    }

    // --- ruling 11: a Bond that has ended, in both orders against a leave ---

    @Test
    fun `a change delivered after its actor's leave committed asks nothing`() {
        val bond = pairedBond()
        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        val ended = sharedCount("setting", NotifyBondMembers.BOND_ENDED)

        post(ada, "/bonds/$bond/leave").status shouldBe 204
        dispatchAll()

        shared().shouldBeEmpty()
        sharedCount("setting", NotifyBondMembers.BOND_ENDED) shouldBe ended + 1
    }

    @Test
    fun `a change asked for before its actor's leave is called off by the leave`() {
        val bond = pairedBond()
        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        dispatchAll()
        sharedRow(bea)["status"] shouldBeIn OWED

        post(ada, "/bonds/$bond/leave").status shouldBe 204

        sharedRow(bea)["status"] shouldBe "CANCELLED"
    }

    @Test
    fun `a leave racing the delivery waits for its lock, then calls off the push it asked for`() {
        // Spec §7: decided by cancelBond in the leave's transaction and the consumer's row lock.
        val bond = pairedBond()
        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        val event = received(BondSettingsChanged.TYPE)
        val leaver = membershipOf(ada, bond)
        val cancelled = cancelledBondEnded()
        val asked = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val consumerPid = CompletableFuture<Int>()

        // A: the delivery has read the Bond (not ended), asked for Bea's push, and has not committed.
        val delivery =
            CompletableFuture.runAsync {
                transactions.executeWithoutResult {
                    consumer.handle(event)
                    consumerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                    asked.countDown()
                    check(commit.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the delivery was never let commit" }
                }
            }
        check(asked.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the delivery never asked" }
        val pid = consumerPid.get(WAIT_SECONDS, TimeUnit.SECONDS)

        // B: Ada leaves. It must queue on the delivery's lock; were there none, it would finish here.
        val leave = CompletableFuture.runAsync { endBond.leave(leaver, withdrawEntries = false) }
        awaitUntil("the leave neither waited on the delivery nor finished") { leave.isDone || waitsOnRowLockOf(pid) }
        val leaveWaited = !leave.isDone
        commit.countDown()
        delivery.get(WAIT_SECONDS, TimeUnit.SECONDS)
        leave.get(WAIT_SECONDS, TimeUnit.SECONDS)

        bondStatus(bond) shouldBe "ARCHIVED"
        sharedRow(bea)["status"] shouldBe "CANCELLED"
        cancelledBondEnded() shouldBe cancelled + 1
        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status IN ('REQUESTED', 'HELD')", Int::class.java) shouldBe 0
        leaveWaited shouldBe true
    }

    @Test
    fun `turning search off on a bond that has ended asks nothing`() {
        val bond = pairedBond()
        post(bea, "/bonds/$bond/leave").status shouldBe 204
        dispatchAll()
        clearPushes()

        patch(ada, bond, """{"semanticSearchEnabled":false}""").status shouldBe 200
        dispatchAll()

        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, BondSettingsChanged.TYPE) shouldBe 1
        shared().shouldBeEmpty()
    }

    // --- idempotence, toggles, metrics ---

    @Test
    fun `an event delivered twice asks once`() {
        val bond = pairedBond()
        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        val event = received(BondSettingsChanged.TYPE)
        val requested = sharedCount("setting", NotifyBondMembers.REQUESTED)
        val alreadyAsked = sharedCount("setting", NotifyBondMembers.ALREADY_ASKED)

        handle(event)
        handle(event)

        shared().size shouldBe 1
        sharedCount("setting", NotifyBondMembers.REQUESTED) shouldBe requested + 1
        sharedCount("setting", NotifyBondMembers.ALREADY_ASKED) shouldBe alreadyAsked + 1
    }

    @Test
    fun `an answer delivered twice asks once`() {
        val bond = pairedBond()
        propose(ada, bond).status shouldBe 200
        dispatchAll()
        delete(bea, "/bonds/$bond/timezone").status shouldBe 204
        val event = received(ProposalClosed.TYPE)

        handle(event)
        handle(event)
        dispatchAll()

        shared().size shouldBe 1
    }

    @Test
    fun `the other member's sharedSettings toggle off records their push suppressed`() {
        val bond = pairedBond()
        notifications(bea, bond, """{"categories":{"sharedSettings":false}}""")

        patch(ada, bond, """{"name":"Ours"}""").status shouldBe 200
        dispatchAll()

        sharedRow(bea)["wanted"] shouldBe false
        sharedRow(bea)["status"] shouldBe "SUPPRESSED"
    }

    // --- fixtures ---

    private data class Shared(
        val userId: UUID,
        val dedupeKey: String,
        val change: String,
    )

    private fun shared(): List<Shared> =
        jdbc.query(
            """
            SELECT user_id, dedupe_key, shared_change FROM notifications
             WHERE category = 'SHARED_SETTING_CHANGED' ORDER BY created_at, user_id
            """.trimIndent(),
        ) { row, _ -> Shared(row.getObject("user_id", UUID::class.java), row.getString("dedupe_key"), row.getString("shared_change")) }

    private fun categories(): List<String> =
        jdbc.queryForList("SELECT category FROM notifications ORDER BY created_at", String::class.java).filterNotNull()

    private fun sharedRow(userId: UUID): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM notifications WHERE category = 'SHARED_SETTING_CHANGED' AND user_id = ?", userId)

    private fun openingStatus(
        bond: String,
        userId: UUID,
    ): String =
        jdbc.queryForObject(
            "SELECT status FROM notifications WHERE dedupe_key = ?",
            String::class.java,
            "proposal:${proposalId(bond)}:$userId",
        )!!

    private infix fun Any?.shouldBeNotIn(statuses: List<String>) {
        (this in statuses) shouldBe false
    }

    private fun sharedCount(
        change: String,
        outcome: String,
    ): Double = meters.counter(NotifyBondMembers.SHARED_CHANGES, "change", change, "outcome", outcome).count()

    private fun cancelledBondEnded(): Double =
        meters.counter("notification.push.cancelled", "category", "SHARED_SETTING_CHANGED", "nudge", "none", "reason", "bond_ended").count()

    /** A backend waiting, not granted, for the transaction [pid] holds: a row lock it has taken. */
    private fun waitsOnRowLockOf(pid: Int): Boolean =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pg_locks waiting
              JOIN pg_locks holding ON holding.locktype = 'transactionid' AND holding.pid = ? AND holding.granted
                                   AND holding.transactionid = waiting.transactionid
             WHERE waiting.locktype = 'transactionid' AND NOT waiting.granted
            """.trimIndent(),
            Int::class.java,
            pid,
        )!! > 0

    /** Polls [condition] against a deadline that fails: what happened, never how long it took. */
    private fun awaitUntil(
        failure: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { failure }
            Thread.onSpinWait()
        }
    }

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

    private fun cancelledForProposalClosed(): Double =
        meters.counter("notification.push.cancelled", "category", "PROPOSAL_OPENED", "nudge", "none", "reason", "proposal_closed").count()

    /** Quiet hours around now in [userId]'s reminder zone, so a push to them is held. */
    private fun inQuietHours(
        userId: UUID,
        bond: String,
    ) {
        val now = clock.instant().atZone(ZoneId.of(QUIET_ZONE)).toLocalTime()
        jdbc.update(
            """
            UPDATE bond_members SET reminder_timezone = '$QUIET_ZONE', quiet_hours_start = ?, quiet_hours_end = ?
             WHERE bond_id = ? AND user_id = ?
            """.trimIndent(),
            Time.valueOf(now.minusHours(1).withNano(0)),
            Time.valueOf(now.plusHours(1).withNano(0)),
            UUID.fromString(bond),
            userId,
        )
    }

    private fun clearPushes() {
        jdbc.execute("TRUNCATE TABLE notifications")
    }

    private fun clearOutbox() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
    }

    private fun bondStatus(bond: String): String =
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?", String::class.java, UUID.fromString(bond))!!

    private fun proposalId(bond: String): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_proposals WHERE bond_id = ? ORDER BY proposed_at DESC LIMIT 1",
            UUID::class.java,
            UUID.fromString(bond),
        )!!

    private fun eventId(type: String): UUID = received(type).id

    private fun occurredAt(type: String) = received(type).occurredAt

    /** The latest event of [type] as the dispatcher hands it over, read back from the outbox. */
    private fun received(type: String): ReceivedEvent =
        jdbc.queryForObject(
            """
            SELECT id, aggregate_type, aggregate_id, payload::text AS payload, occurred_at
              FROM outbox_events WHERE event_type = ? ORDER BY occurred_at DESC LIMIT 1
            """.trimIndent(),
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
        )

    private fun handle(event: ReceivedEvent) {
        transactions.executeWithoutResult { consumer.handle(event) }
    }

    /** Runs the outbox until it has nothing due: the change, then the pushes it asked for. */
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
        dispatchAll()
        clearPushes()
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
    ): MockHttpServletResponse = mockMvc.post("/api/v1$path") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun delete(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse = mockMvc.delete("/api/v1$path") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

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
        const val WAIT_SECONDS = 10L
        val OWED = listOf("REQUESTED", "HELD")

        /** A region zone (a member's zone must be one) that has no daylight saving. */
        const val QUIET_ZONE = "Atlantic/Reykjavik"
    }
}
