package com.moyi.bond.api

import com.moyi.bond.domain.AnchorInterval
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `BondAccess.lockMembershipOf` (task 3 of the C1 rework): the port's
 * row-locking read, and the three facts it adds to [BondMembership] —
 * `activeSince`, `endedAt` and `anchorTimeline`.
 *
 * Runs through the real HTTP endpoints for every fixture (`leave`,
 * `propose`/`confirm`, `accept`), the same shape `DeferredTimezoneChangeTest`
 * uses, because what each test is really pinning is the *stored* state the
 * port then has to project correctly — not a hand-built aggregate that could
 * silently drift from what the write paths actually persist. [clock] is a
 * [MutableClock] for the same reason it is there: several assertions are
 * about which instant a day boundary or a `joined_at` falls on.
 */
@SpringBootTest(
    classes = [BondTestApplication::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@AutoConfigureMockMvc
internal class BondAccessLockingTest(
    @Autowired private val access: BondAccess,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    @TestBean(name = "clock")
    private lateinit var clock: MutableClock

    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_anchor_intervals, bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `lockMembershipOf refuses to run without a transaction`() {
        val bond = bondForTwo()

        // MANDATORY propagation: a caller that forgot the transaction gets a
        // loud failure rather than a lock that is released before the write
        // it was meant to protect. Spec §2.1.
        shouldThrow<IllegalTransactionStateException> {
            access.lockMembershipOf(bond.ada, bond.id)
        }
    }

    @Test
    fun `lockMembershipOf actually blocks a concurrent lock-taker on the same bond`() {
        // A test that would pass with the lock removed proves nothing: if
        // `lockBond` were deleted, `waiter` would never appear in
        // `pg_blocking_pids` and the `await` below would time out rather than
        // see a (false) "still blocked" snapshot.
        val bond = bondForTwo()
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holderPid = CompletableFuture<Int>()
            val holder =
                pool.submit {
                    transactions.execute {
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                        access.lockMembershipOf(bond.ada, bond.id)
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            val pid = holderPid.get(10, TimeUnit.SECONDS)

            val waiter =
                pool.submit {
                    transactions.execute { access.lockMembershipOf(bond.ada, bond.id) }
                }

            await().atMost(Duration.ofSeconds(10)).until {
                waiter.isDone ||
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                        Int::class.java,
                        pid,
                    )!! > 0
            }
            waiter.isDone shouldBe false

            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            waiter.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `activeSince is the second member's join, not the bond's creation`() {
        val created = Instant.parse("2026-09-01T00:00:00Z")
        val joined = Instant.parse("2026-09-04T09:00:00Z")
        val bond = bondForTwo(createdAt = created, joinedAt = joined)

        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).activeSince shouldBe joined
        }
    }

    @Test
    fun `a bond still awaiting its partner has no activeSince`() {
        val bond = bondPendingMember()

        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).activeSince.shouldBeNull()
        }
    }

    @Test
    fun `the membership carries the effective timeline, not the requested zone`() {
        val bond = bondForTwo(anchor = "Africa/Lagos", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        proposeAndConfirm(bond, to = "Pacific/Kiritimati", at = Instant.parse("2026-09-15T11:00:00Z"))

        inTransaction {
            val membership = access.lockMembershipOf(bond.ada, bond.id)
            // What was agreed...
            membership.anchorTimezone shouldBe "Pacific/Kiritimati"
            // ...is not yet what decides the date, for the rest of this
            // logical day (BR-6) — and is, from the next one.
            membership.anchorTimeline.zoneIdAt(Instant.parse("2026-09-15T12:00:00Z")) shouldBe "Africa/Lagos"
            membership.anchorTimeline.zoneIdAt(Instant.parse("2026-09-16T00:00:00Z")) shouldBe "Pacific/Kiritimati"
        }
    }

    @Test
    fun `hasLeft survives on the port`() {
        // R2. Spec §2.1's field list omits it; SubmitEntry checks it, and the
        // second review of PR #41 is why. Deleting it reopens that defect.
        val bond = bondForTwo()
        leave(bond.ada, bond.id)

        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).hasLeft shouldBe true
        }
    }

    @Test
    fun `endedAt is null while the bond is live, and the archived_at it was given once it ends`() {
        val bond = bondForTwo()
        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).endedAt.shouldBeNull()
        }

        leave(bond.ada, bond.id)

        val archivedAt = archivedAtOf(bond.id)
        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).endedAt shouldBe archivedAt
        }
    }

    @Test
    fun `anchorTimeline agrees with AnchorIntervalStore timelineOf`() {
        val bond = bondForTwo(anchor = "Europe/London", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        proposeAndConfirm(bond, to = "Pacific/Auckland", at = Instant.parse("2026-09-10T11:00:00Z"))
        val probes =
            listOf(
                Instant.parse("2026-09-05T08:00:00Z"),
                Instant.parse("2026-09-10T20:00:00Z"),
                Instant.parse("2026-09-12T20:00:00Z"),
            )

        inTransaction {
            val membership = access.lockMembershipOf(bond.ada, bond.id)
            // Built straight from the table `AnchorIntervalStore.timelineOf`
            // itself reads — `DeferredTimezoneChangeTest`'s own precedent —
            // rather than injecting that store, so this stays an independent
            // check of what the port reports against what was persisted.
            val direct = directTimelineOf(bond.id)
            probes.forEach { at ->
                membership.anchorTimeline.zoneIdAt(at) shouldBe direct.zoneAt(at).id
                membership.anchorTimeline.dateAt(at) shouldBe direct.dateAt(at)
            }
        }
    }

    // ---- fixtures -----------------------------------------------------------

    /** The same reconstruction `AnchorIntervalStore.timelineOf` does, read directly off the table. */
    private fun directTimelineOf(bondId: UUID): AnchorTimeline =
        AnchorTimeline(
            jdbc.query(
                "SELECT zone, first_label, effective_from, effective_to FROM bond_anchor_intervals " +
                    "WHERE bond_id = ? ORDER BY effective_from",
                { rs, _ ->
                    AnchorInterval(
                        zone = ZoneId.of(rs.getString("zone")),
                        effectiveFrom = rs.getTimestamp("effective_from").toInstant(),
                        effectiveTo = rs.getTimestamp("effective_to")?.toInstant(),
                        firstLabel = rs.getDate("first_label").toLocalDate(),
                    )
                },
                bondId,
            ),
        )

    private data class TestBond(
        val id: UUID,
        val ada: UUID,
        val bea: UUID,
    )

    /** Ada creates, Bea joins — at a controlled instant each, so `activeSince` can be told apart from `createdAt`. */
    private fun bondForTwo(
        anchor: String = "Africa/Lagos",
        createdAt: Instant = Instant.parse("2026-09-01T00:00:00Z"),
        joinedAt: Instant = createdAt,
    ): TestBond {
        clock.set(createdAt)
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada, anchor)
        val bondId = UUID.fromString(bondIdOf(created))
        val code = codeOf(created)
        if (joinedAt.isAfter(clock.instant())) clock.set(joinedAt)
        accept(bea, code).status shouldBe 200
        return TestBond(bondId, ada, bea)
    }

    /** Ada alone, waiting for an invite to be accepted. */
    private fun bondPendingMember(): TestBond {
        clock.set(Instant.parse("2026-09-01T00:00:00Z"))
        val ada = users.verified("Ada")
        val created = createBond(ada, "Africa/Lagos")
        return TestBond(UUID.fromString(bondIdOf(created)), ada, UUID.randomUUID())
    }

    /** Proposes and confirms a zone change, moving the clock to [at] first — never backward. */
    private fun proposeAndConfirm(
        bond: TestBond,
        to: String,
        at: Instant,
    ) {
        if (at.isAfter(clock.instant())) clock.set(at)
        propose(bond.ada, bond.id, to).status shouldBe 200
        confirm(bond.bea, bond.id).status shouldBe 200
    }

    private fun leave(
        userId: UUID,
        bondId: UUID,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
            }.andReturn()
            .response.status shouldBe 204
    }

    private fun archivedAtOf(bondId: UUID): Instant =
        jdbc.queryForObject(
            "SELECT archived_at FROM bonds WHERE id = ?",
            { rs, _ -> rs.getTimestamp("archived_at").toInstant() },
            bondId,
        )!!

    private fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }!!

    // ---- HTTP helpers — `DeferredTimezoneChangeTest`'s own shape -----------

    private fun createBond(
        userId: UUID,
        anchor: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$anchor"}"""
            }.andReturn()
            .response

    private fun propose(
        userId: UUID,
        bondId: UUID,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun confirm(
        userId: UUID,
        bondId: UUID,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"proposalId":"${jdbc
                        .queryForList(
                            "SELECT id FROM bond_proposals WHERE bond_id = ? AND kind = 'TIMEZONE_CHANGE' ORDER BY proposed_at DESC",
                            bondId,
                        ).firstOrNull()
                        ?.get("id") ?: UUID.randomUUID()}"}"""
            }.andReturn()
            .response

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

    private companion object {
        /** [TestBean]'s default naming convention: a static method named after the field it overrides. */
        @JvmStatic
        fun clock(): MutableClock = MutableClock(start = Instant.parse("2020-01-01T00:00:00Z"))
    }
}
