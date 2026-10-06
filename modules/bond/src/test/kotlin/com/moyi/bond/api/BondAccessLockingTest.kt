package com.moyi.bond.api

import com.moyi.bond.domain.AnchorInterval
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.NotFoundException
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    @Autowired private val dataSource: DataSource,
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
    fun `lockMembershipOf refuses a non-member exactly as membershipOf does`() {
        // Ruling on lock order (fix round 1): the guard runs before the lock
        // is taken, the same order ChangeTimezone, EndBond, RequestDeletion
        // and UpdateBond all use. A stranger must get the one 404 both
        // methods give — and never take `FOR UPDATE` on a bond that is not
        // theirs on the way to it.
        val bond = bondForTwo()
        val stranger = users.verified("Eve")

        shouldThrow<NotFoundException> { access.membershipOf(stranger, bond.id) }
        // The `shouldThrow` wraps `inTransaction`, not the other way round:
        // `guard.membershipOf` is itself `@Transactional`, so it marks the
        // surrounding transaction rollback-only the instant it throws.
        // Swallowing the exception *inside* the transaction and letting the
        // callback return normally would make `TransactionTemplate` try to
        // commit a transaction already marked rollback-only, which raises
        // `UnexpectedRollbackException` instead of the `NotFoundException`
        // this test is actually about — found by running it, not by reading.
        shouldThrow<NotFoundException> {
            inTransaction { access.lockMembershipOf(stranger, bond.id) }
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
                        // The pid is only published once the lock is actually
                        // held — completing it beforehand let the waiter start
                        // (and sometimes win the lock) before `lockMembershipOf`
                        // had run at all, which is exactly the race that made
                        // this test flake (fix round 1, Minor #2).
                        access.lockMembershipOf(bond.ada, bond.id)
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
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
    fun `lockMembershipOf sees what committed while it waited for the lock, not what the guard read`() {
        // The guard runs before the lock and loads the bond into this
        // transaction; Hibernate would answer the "re-read under the lock"
        // from that copy. Drop `refreshReads = true` from lockMembershipOf and
        // the waiter reports the bond still open although it ended before the
        // waiter held the lock. The holder is raw JDBC so it can pause while
        // holding the lock, exactly where a real `EndBond` would be.
        val bond = bondForTwo()
        val pool = Executors.newFixedThreadPool(1)
        try {
            withBondRowLocked(bond.id) { holder, pid ->
                val waiter = pool.submit<BondMembership> { transactions.execute { access.lockMembershipOf(bond.ada, bond.id) } }
                await().atMost(Duration.ofSeconds(10)).until { waiter.isDone || blockedBy(pid) }
                waiter.isDone shouldBe false

                holder.prepareStatement("UPDATE bonds SET status = 'ARCHIVED', archived_at = ? WHERE id = ?").use {
                    it.setTimestamp(1, Timestamp.from(ENDED))
                    it.setObject(2, bond.id)
                    it.executeUpdate()
                }
                holder.commit()

                val seen = waiter.get(10, TimeUnit.SECONDS)
                seen.isOpen shouldBe false
                seen.endedAt shouldBe ENDED
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** A transaction on its own connection holding `lockRow`'s exact statement on [bondId]; rolled back unless [block] commits. */
    private fun withBondRowLocked(
        bondId: UUID,
        block: (holder: Connection, pid: Int) -> Unit,
    ) {
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            try {
                holder.prepareStatement("SELECT 1 FROM bonds WHERE id = ? FOR UPDATE").use {
                    it.setObject(1, bondId)
                    it.executeQuery().close()
                }
                block(holder, backendPidOf(holder))
            } finally {
                holder.rollback()
                holder.autoCommit = true
            }
        }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun blockedBy(pid: Int): Boolean =
        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, pid)!! > 0

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
    fun `a creator who leaves before anyone accepts still has no activeSince`() {
        // Fix round 1, Important #1: `Bond.end` archives a one-member bond
        // exactly as readily as a two-member one. A status check
        // (`PENDING_MEMBER`) would have missed this — the bond is `ARCHIVED`
        // here, not `PENDING_MEMBER` — and reported the creator's own
        // `joinedAt` as `activeSince`, which is the harm this field exists to
        // prevent.
        val bond = bondPendingMember()
        leave(bond.ada, bond.id)

        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).activeSince.shouldBeNull()
        }
    }

    @Test
    fun `a creator who requests deletion before anyone accepts still has no activeSince`() {
        // Same harm as above, through `RequestDeletion` instead of `EndBond`:
        // the bond is `PENDING_DELETION` with one member row, not
        // `PENDING_MEMBER`.
        val bond = bondPendingMember()
        requestDeletion(bond.ada, bond.id)

        inTransaction {
            access.lockMembershipOf(bond.ada, bond.id).activeSince.shouldBeNull()
        }
    }

    @Test
    fun `activeSince is unchanged once one of the two members later leaves`() {
        val created = Instant.parse("2026-09-01T00:00:00Z")
        val joined = Instant.parse("2026-09-04T09:00:00Z")
        val bond = bondForTwo(createdAt = created, joinedAt = joined)

        leave(bond.ada, bond.id)

        inTransaction {
            // The left member's row survives (`states.md` §9), so the
            // second-earliest `joinedAt` is still the same instant.
            access.lockMembershipOf(bond.ada, bond.id).activeSince shouldBe joined
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

            // Fix round 1, Minor #4: port-level assertions against known
            // values (Europe/London is on BST in September; Pacific/Auckland
            // is still on NZST, DST not starting until late September), not
            // only against a second computation through the same mechanism —
            // so a swapped `startsAt`/`endsAt`, or a `dayBoundsAtFn` that
            // silently returned the wrong projection, would fail this.
            val beforeHandoff = membership.anchorTimeline.dayBoundsAt(probes[0])
            beforeHandoff.date shouldBe LocalDate.of(2026, 9, 5)
            beforeHandoff.startsAt shouldBe Instant.parse("2026-09-04T23:00:00Z")
            beforeHandoff.endsAt shouldBe Instant.parse("2026-09-05T23:00:00Z")
            beforeHandoff.isDegenerate shouldBe false

            val afterHandoff = membership.anchorTimeline.dayBoundsAt(probes[2])
            afterHandoff.date shouldBe LocalDate.of(2026, 9, 13)
            afterHandoff.startsAt shouldBe Instant.parse("2026-09-12T12:00:00Z")
            afterHandoff.endsAt shouldBe Instant.parse("2026-09-13T12:00:00Z")
            afterHandoff.isDegenerate shouldBe false

            // Every label from the bond's creation (2026-09-01, London) through
            // the label in force at the last probe (2026-09-13, Auckland) —
            // the handoff cost no skipped label, so the run has no gap.
            membership.anchorTimeline.usedLabelsUpTo(probes[2]) shouldBe
                (1..13).map { LocalDate.of(2026, 9, it) }.toSet()

            // Where the timeline starts: the bond's creation, not the handoff
            // and not a midnight.
            membership.anchorTimeline.beginsAt shouldBe Instant.parse("2026-09-01T00:00:00Z")
            membership.anchorTimeline.beginsAt shouldBe direct.intervals.first().effectiveFrom
        }
    }

    // ---- fixtures -----------------------------------------------------------

    // --- the closer's view: a bond's calendar for a caller that is nobody's user (C3) ---

    @Test
    fun `the closing view carries when the bond became two, when it ended, and its timeline`() {
        val created = Instant.parse("2026-09-01T00:00:00Z")
        val joined = Instant.parse("2026-09-04T09:00:00Z")
        val bond = bondForTwo(createdAt = created, joinedAt = joined)

        val live = access.closingViewOf(bond.id).shouldNotBeNull()
        live.bondId shouldBe bond.id
        live.activeSince shouldBe joined
        live.endedAt.shouldBeNull()
        live.revealTimeLocal.shouldBeNull()
        live.strictModeBefore(joined) shouldBe false
        live.anchorTimeline.beginsAt shouldBe created
        // Lagos is UTC+1 all year: the 5th runs from 23:00Z on the 4th.
        live.anchorTimeline.dayBoundsAt(Instant.parse("2026-09-05T08:00:00Z")).startsAt shouldBe Instant.parse("2026-09-04T23:00:00Z")

        jdbc.update("UPDATE bonds SET reveal_time_local = '21:00', strict_mode = true WHERE id = ?", bond.id)
        leave(bond.ada, bond.id)

        val ended = access.closingViewOf(bond.id).shouldNotBeNull()
        ended.endedAt shouldBe archivedAtOf(bond.id)
        ended.activeSince shouldBe joined
        ended.revealTimeLocal shouldBe LocalTime.of(21, 0)
        // Set by hand above, with no change instant recorded: read as "always was".
        ended.strictModeBefore(joined) shouldBe true
    }

    @Test
    fun `the closing view of a bond still waiting for its partner has no activeSince, and an unknown bond has no view`() {
        val pending = bondPendingMember()

        access
            .closingViewOf(pending.id)
            .shouldNotBeNull()
            .activeSince
            .shouldBeNull()
        access.closingViewOf(UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `bondsToSweep pages every bond that has ever been two, in id order, and none that never was`() {
        val paired = List(3) { bondForTwo().id }.sorted()
        val endedButOncePaired = bondForTwo().also { leave(it.ada, it.id) }.id
        bondPendingMember()
        val expected = (paired + endedButOncePaired).sorted()

        access.bondsToSweep(after = null, limit = 10) shouldBe expected
        val firstPage = access.bondsToSweep(after = null, limit = 2)
        firstPage shouldBe expected.take(2)
        access.bondsToSweep(after = firstPage.last(), limit = 10) shouldBe expected.drop(2)
        access.bondsToSweep(after = expected.last(), limit = 10) shouldBe emptyList()
    }

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

    /** The solo-member path: `RequestDeletion.request` schedules deletion at once (`202`), no second member to ask. */
    private fun requestDeletion(
        userId: UUID,
        bondId: UUID,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bondId/deletion-request") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
            }.andReturn()
            .response.status shouldBe 202
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

        val ENDED: Instant = Instant.parse("2026-09-02T12:00:00Z")
    }
}
