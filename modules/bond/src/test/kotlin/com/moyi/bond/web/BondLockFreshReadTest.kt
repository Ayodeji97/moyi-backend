package com.moyi.bond.web

import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The audit ADR-0031 owed: **every `BondStore.lockBond` caller decides on what
 * is true once it holds the lock, not on what was true before it queued.**
 *
 * Slice C1 found one caller (`BondAccess.lockMembershipOf`) whose "re-read
 * under the lock" was answered from Hibernate's persistence context, because
 * the guard had loaded the bond earlier *in the same transaction*. The eleven
 * other call sites were left alone on the argument that they all write the
 * bond row afterwards, so `@Version` would catch a stale read. That argument
 * is wrong for most of them — creating or revoking an invite, proposing,
 * cancelling, a first deletion request and a member's settings never touch
 * the `bonds` row — and what actually keeps them correct is narrower: the
 * controller's guard read runs in its own finished transaction, so the
 * service's transaction starts with an empty persistence context and its
 * first statement is the lock.
 *
 * **Every test here has one shape.** A raw JDBC transaction takes the bond's
 * row lock with `lockRow`'s exact statement; the request under test is sent
 * through the real controller (guard included) and is *observed* queued behind
 * that transaction in `pg_blocking_pids`; the holder then commits what a real
 * concurrent writer would have committed; the request resumes, and must answer
 * for the new state. Through HTTP on purpose: the property being tested lives
 * in the seam between the controller's guard call and the service, and calling
 * the service directly would step over it.
 *
 * **Each was watched going red** with the bond loaded before the lock in the
 * service under test (`bonds.findByMember(…)` one line above `lockBond`), and
 * all the member-scoped ones again with `spring.jpa.open-in-view: true` in
 * this module's test configuration. The pull request records what each
 * failure looked like.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondLockFreshReadTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val pool = Executors.newCachedThreadPool()

    @AfterEach
    fun clear() {
        // The pool first, and awaited: a request still queued after a failed
        // assertion must not commit after the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        jdbc.execute("TRUNCATE TABLE bond_anchor_intervals, bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    // ---- UpdateBond.patch ---------------------------------------------------

    @Test
    fun `a settings patch queued behind a leave is refused as archived, whatever version it holds`() {
        val bond = pairedBond()
        val tagBeforeTheLeave = getBond(bond.ada, bond.id).getHeader(HttpHeaders.ETAG)!!

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { patch(bond.ada, bond.id, """{"name":"Us two"}""", tagBeforeTheLeave) },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.bea) },
            )

        // `409`, not `412` and not `200`: the bond the patch was authorised
        // against ended while it waited, and "this has ended" is the answer.
        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        bondColumn(bond.id, "name") shouldBe "Us"
        bondColumn(bond.id, "status") shouldBe "ARCHIVED"
    }

    // ---- EndBond.leave and EndBond.block (one `lockAndLoad`, two decisions) ----

    @Test
    fun `a leave queued behind the other member's leave finds the bond already ended`() {
        val bond = pairedBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { post(bond.ada, "/api/v1/bonds/${bond.id}/leave") },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.bea) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        // Ada did not leave: the bond ended under her, and her row says so.
        leftAt(bond.id, bond.ada) shouldBe null
        // 0 created, 1 accepted, 2 Bea's leave — and nothing from this one.
        bondColumn(bond.id, "version") shouldBe "2"
    }

    @Test
    fun `a block queued behind an accept blocks the member who joined while it waited`() {
        // The decision `block` makes on the member rows: one `blocks` row per
        // *other* member. Read from before the accept there is no other
        // member, the bond is archived with Bea inside it and nothing stops
        // the two accounts being paired again — FR-029 undone, silently.
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { post(bond.ada, "/api/v1/bonds/${bond.id}/block") },
                committedMeanwhile = { holder -> holder.partnerJoins(bond.id, bond.bea) },
            )

        response.status shouldBe 204
        jdbc.queryForObject(
            "SELECT count(*) FROM blocks WHERE blocker_user_id = ? AND blocked_user_id = ?",
            Int::class.java,
            bond.ada,
            bond.bea,
        ) shouldBe 1
        bondColumn(bond.id, "status") shouldBe "ARCHIVED"
        (leftAt(bond.id, bond.ada) != null) shouldBe true
    }

    // ---- AcceptInvite.accept (no guard; the invite is loaded before the lock) ----

    @Test
    fun `an accept queued behind the creator's leave does not join the bond that ended`() {
        // The bond is archived and the code deliberately left live, which no
        // real writer does (`EndBond` revokes it): that isolates the re-read
        // of the *bond* from the compare-and-set on the *invite*, which would
        // otherwise refuse this accept on its own and let a stale bond pass
        // unnoticed.
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { accept(bond.bea, bond.code) },
                committedMeanwhile = { holder ->
                    holder.run("UPDATE bond_members SET left_at = now() WHERE bond_id = ?", UUID.fromString(bond.id))
                    holder.run(
                        "UPDATE bonds SET status = 'ARCHIVED', archived_at = now(), version = version + 1 WHERE id = ?",
                        UUID.fromString(bond.id),
                    )
                },
            )

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"INVITE_NOT_USABLE\""
        count("bond_members") shouldBe 1
        count("bond_invites WHERE used_at IS NOT NULL") shouldBe 0
        bondColumn(bond.id, "status") shouldBe "ARCHIVED"
    }

    @Test
    fun `an accept queued behind a revoke does not spend the code that was revoked`() {
        // The one thing this service reads *before* the lock is the invite,
        // so its copy is as old as the wait. It is safe because spending the
        // code is a compare-and-set that asks "still live?" in the UPDATE
        // itself, not because anything re-reads it.
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { accept(bond.bea, bond.code) },
                committedMeanwhile = { holder ->
                    holder.run("UPDATE bond_invites SET revoked_at = now() WHERE bond_id = ?", UUID.fromString(bond.id)) shouldBe 1
                },
            )

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"INVITE_NOT_USABLE\""
        count("bond_members") shouldBe 1
        count("bond_invites WHERE used_at IS NOT NULL") shouldBe 0
        bondColumn(bond.id, "status") shouldBe "PENDING_MEMBER"
    }

    // ---- ChangeTimezone.propose, .confirm and .cancel ------------------------

    @Test
    fun `a zone proposal queued behind a leave is refused and records nothing`() {
        // The two-member path writes a `bond_proposals` row and never the
        // bond row, so `@Version` would have nothing to object to: a proposal
        // decided on the pre-wait copy lands on an ended bond with a `200`.
        val bond = pairedBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { proposeZone(bond.ada, bond.id, "Europe/London") },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.bea) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        count("bond_proposals") shouldBe 0
    }

    @Test
    fun `a zone proposal queued behind an accept asks the new member instead of moving the zone alone`() {
        // `status == PENDING_MEMBER` is the branch that applies a change with
        // nobody to ask. Bea joined while this waited, so there is somebody.
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { proposeZone(bond.ada, bond.id, "Europe/London") },
                committedMeanwhile = { holder -> holder.partnerJoins(bond.id, bond.bea) },
            )

        response.status shouldBe 200
        bondColumn(bond.id, "anchor_timezone") shouldBe "Africa/Lagos"
        count("bond_proposals WHERE confirmed_at IS NULL AND cancelled_at IS NULL") shouldBe 1
    }

    @Test
    fun `a zone confirmation queued behind a leave does not move the zone of the ended bond`() {
        val bond = pairedBond()
        proposeZone(bond.ada, bond.id, "Europe/London").status shouldBe 200
        val proposalId = jdbc.queryForObject("SELECT id FROM bond_proposals", UUID::class.java)!!

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { confirmZone(bond.bea, bond.id, proposalId) },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.ada) },
            )

        // `409`, the answer leave-first gives; not the `404` of a proposal
        // that happens to have been cancelled by the ending.
        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        bondColumn(bond.id, "anchor_timezone") shouldBe "Africa/Lagos"
        count("bond_proposals WHERE confirmed_at IS NOT NULL") shouldBe 0
    }

    @Test
    fun `a zone cancellation queued behind the confirmation finds nothing left to cancel`() {
        // This path never reads the bond. What it decides on is the proposal,
        // twice over: a query after the lock, then a compare-and-set.
        val bond = pairedBond()
        proposeZone(bond.ada, bond.id, "Europe/London").status shouldBe 200

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { delete(bond.ada, "/api/v1/bonds/${bond.id}/timezone") },
                committedMeanwhile = { holder ->
                    holder.run(
                        "UPDATE bond_proposals SET confirmed_at = now(), confirmed_by_member_id = " +
                            "(SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?)",
                        UUID.fromString(bond.id),
                        bond.bea,
                    ) shouldBe 1
                    holder.run(
                        "UPDATE bonds SET anchor_timezone = 'Europe/London', timezone_changed_at = now(), version = version + 1 WHERE id = ?",
                        UUID.fromString(bond.id),
                    )
                },
            )

        response.status shouldBe 404
        count("bond_proposals WHERE cancelled_at IS NOT NULL") shouldBe 0
        count("bond_proposals WHERE confirmed_at IS NOT NULL") shouldBe 1
        bondColumn(bond.id, "anchor_timezone") shouldBe "Europe/London"
    }

    // ---- MemberSettingsService.replace --------------------------------------

    @Test
    fun `a settings update queued behind the member's own leave is refused and does not undo the leave`() {
        // Ada leaves on one device while her other device saves a reminder.
        // This path writes her member row and nothing else — every column of
        // it, `left_at` included — so a copy from before the leave would put
        // `left_at` back to null on a bond that has ended, and say `200`.
        val bond = pairedBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = {
                    mockMvc
                        .put("/api/v1/bonds/${bond.id}/members/me/settings") {
                            header(HttpHeaders.AUTHORIZATION, bearer(bond.ada))
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"reminderTimeLocal":"07:30"}"""
                        }.andReturn()
                        .response
                },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.ada) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        (leftAt(bond.id, bond.ada) != null) shouldBe true
        jdbc.queryForObject(
            "SELECT reminder_time_local::text FROM bond_members WHERE user_id = ?",
            String::class.java,
            bond.ada,
        ) shouldBe "20:00:00"
    }

    // ---- RevokeInvite.revoke and CreateInvite.forBond -------------------------

    @Test
    fun `a revoke queued behind a leave is told the bond has ended, not that the invite is missing`() {
        // ADR-0028 §6a's own case: leave-first is `409`, revoke-first is
        // `204`, and the `404` of a conditional UPDATE that matched nothing
        // is an answer neither ordering produces.
        val bond = pendingBond()
        val inviteId = jdbc.queryForObject("SELECT id FROM bond_invites", UUID::class.java)!!

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { delete(bond.ada, "/api/v1/bonds/${bond.id}/invites/$inviteId") },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.ada) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `a new invite queued behind a leave is refused and no code is issued for the ended bond`() {
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { post(bond.ada, "/api/v1/bonds/${bond.id}/invites") },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.ada) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        count("bond_invites") shouldBe 1
        count("bond_invites WHERE used_at IS NULL AND revoked_at IS NULL") shouldBe 0
    }

    @Test
    fun `a new invite queued behind an accept is refused as full and no second code is issued`() {
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { post(bond.ada, "/api/v1/bonds/${bond.id}/invites") },
                committedMeanwhile = { holder -> holder.partnerJoins(bond.id, bond.bea) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_FULL\""
        count("bond_invites") shouldBe 1
    }

    // ---- RequestDeletion.request and .cancel ----------------------------------

    @Test
    fun `a deletion request queued behind a leave is refused and records nothing`() {
        val bond = pairedBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { post(bond.ada, "/api/v1/bonds/${bond.id}/deletion-request") },
                committedMeanwhile = { holder -> holder.memberLeaves(bond.id, bond.bea) },
            )

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        count("bond_proposals") shouldBe 0
    }

    @Test
    fun `a deletion request queued behind an accept waits for the new member instead of confirming itself`() {
        // `activeMembers.size == 1` is the branch that starts the cooling-off
        // with nobody to ask. Counted from before the accept, Ada would
        // schedule the destruction of a bond Bea had just joined, alone.
        val bond = pendingBond()

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { post(bond.ada, "/api/v1/bonds/${bond.id}/deletion-request") },
                committedMeanwhile = { holder -> holder.partnerJoins(bond.id, bond.bea) },
            )

        response.status shouldBe 202
        bondColumn(bond.id, "status") shouldBe "ACTIVE"
        bondColumn(bond.id, "deletion_requested_at") shouldBe null
        count("bond_proposals WHERE confirmed_at IS NULL AND cancelled_at IS NULL") shouldBe 1
    }

    @Test
    fun `a deletion cancel queued behind the member's own leave cannot call the deletion off`() {
        // A leave during the cooling-off writes `left_at` and leaves the bond
        // row — and its version — exactly as they were (`Bond.end`), so this
        // is the caller `@Version` could never have protected.
        val bond = pairedBond()
        post(bond.ada, "/api/v1/bonds/${bond.id}/deletion-request").status shouldBe 202
        post(bond.bea, "/api/v1/bonds/${bond.id}/deletion-request").status shouldBe 202
        bondColumn(bond.id, "status") shouldBe "PENDING_DELETION"

        val response =
            whileQueuedBehindTheBondLock(
                bond.id,
                request = { delete(bond.ada, "/api/v1/bonds/${bond.id}/deletion-request") },
                committedMeanwhile = { holder ->
                    holder.run(
                        "UPDATE bond_members SET left_at = now() WHERE bond_id = ? AND user_id = ?",
                        UUID.fromString(bond.id),
                        bond.ada,
                    ) shouldBe 1
                },
            )

        response.status shouldBe 404
        bondColumn(bond.id, "status") shouldBe "PENDING_DELETION"
        (bondColumn(bond.id, "deletion_requested_at") != null) shouldBe true
    }

    // ---- the harness --------------------------------------------------------

    /**
     * Sends [request] while another transaction holds [bondId]'s row lock,
     * proves it is queued behind that transaction, lets the holder run
     * [committedMeanwhile] and commit, and returns what the request then said.
     *
     * The request is only started once the lock is *held*, and "queued" is
     * read from `pg_blocking_pids`, never inferred from time passing. A
     * request that finishes without queuing fails here — it took no lock, and
     * nothing after this line would mean anything.
     */
    private fun whileQueuedBehindTheBondLock(
        bondId: String,
        request: () -> MockHttpServletResponse,
        committedMeanwhile: (Connection) -> Unit,
    ): MockHttpServletResponse =
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            try {
                // `BondRepository.lockRow`'s own statement.
                holder.prepareStatement("SELECT 1 FROM bonds WHERE id = ? FOR UPDATE").use {
                    it.setObject(1, UUID.fromString(bondId))
                    it.executeQuery().use { rows -> rows.next() shouldBe true }
                }
                val holderPid = backendPidOf(holder)
                val waiting = pool.submit<MockHttpServletResponse> { request() }
                awaitBlockedOrDone(holderPid, waiting)
                waiting.isDone shouldBe false

                committedMeanwhile(holder)
                holder.commit()
                waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } finally {
                // A no-op after the commit; otherwise it is what frees a
                // request still queued behind a failed assertion.
                holder.rollback()
                holder.autoCommit = true
            }
        }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun awaitBlockedOrDone(
        holderPid: Int,
        waiter: Future<*>,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            waiter.isDone ||
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Int::class.java,
                    holderPid,
                )!! > 0
        }
    }

    // ---- what the holder commits: the rows a real concurrent writer leaves ---

    /**
     * What `EndBond.leave` commits on an open bond: the member's `left_at`,
     * the bond archived **with its version moved** (Hibernate's `@Version`
     * does that on every write of the row), the live invite revoked and
     * whatever was waiting to be agreed cancelled.
     */
    private fun Connection.memberLeaves(
        bondId: String,
        userId: UUID,
    ) {
        run("UPDATE bond_members SET left_at = now() WHERE bond_id = ? AND user_id = ?", UUID.fromString(bondId), userId)
        run("UPDATE bonds SET status = 'ARCHIVED', archived_at = now(), version = version + 1 WHERE id = ?", UUID.fromString(bondId))
        run(
            "UPDATE bond_invites SET revoked_at = now() WHERE bond_id = ? AND used_at IS NULL AND revoked_at IS NULL",
            UUID.fromString(bondId),
        )
        run(
            "UPDATE bond_proposals SET cancelled_at = now() WHERE bond_id = ? AND confirmed_at IS NULL AND cancelled_at IS NULL",
            UUID.fromString(bondId),
        )
    }

    /**
     * What `AcceptInvite.accept` commits: the second member's row, the bond
     * `ACTIVE` with its version moved, and the code spent.
     */
    private fun Connection.partnerJoins(
        bondId: String,
        userId: UUID,
    ) {
        run(
            "INSERT INTO bond_members (id, bond_id, user_id, role, joined_at, reminder_timezone) " +
                "SELECT gen_random_uuid(), bond_id, ?, 'MEMBER', now(), reminder_timezone FROM bond_members WHERE bond_id = ?",
            userId,
            UUID.fromString(bondId),
        ) shouldBe 1
        run("UPDATE bonds SET status = 'ACTIVE', version = version + 1 WHERE id = ?", UUID.fromString(bondId))
        run(
            "UPDATE bond_invites SET used_at = now(), used_by_user_id = ? WHERE bond_id = ? AND used_at IS NULL AND revoked_at IS NULL",
            userId,
            UUID.fromString(bondId),
        )
    }

    private fun Connection.run(
        sql: String,
        vararg arguments: Any,
    ): Int =
        prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
            statement.executeUpdate()
        }

    // ---- fixtures and requests ----------------------------------------------

    private data class TestBond(
        val id: String,
        val ada: UUID,
        val bea: UUID,
        val code: String,
    )

    /** Ada's bond, still waiting for its second member; `bea` is verified and has not joined. */
    private fun pendingBond(): TestBond {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        return TestBond(
            id = Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1],
            ada = ada,
            bea = bea,
            code = Regex(""""code":"([A-Z0-9]{6})"""").find(created.contentAsString)!!.groupValues[1],
        )
    }

    /** Ada and Bea, both in it. */
    private fun pairedBond(): TestBond = pendingBond().also { accept(it.bea, it.code).status shouldBe 200 }

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun patch(
        userId: UUID,
        bondId: String,
        body: String,
        ifMatch: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_MATCH, ifMatch)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun post(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse = mockMvc.post(path) { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leftAt(
        bondId: String,
        userId: UUID,
    ): String? =
        jdbc.queryForObject(
            "SELECT left_at::text FROM bond_members WHERE bond_id = ? AND user_id = ?",
            String::class.java,
            UUID.fromString(bondId),
            userId,
        )

    private fun proposeZone(
        userId: UUID,
        bondId: String,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun confirmZone(
        userId: UUID,
        bondId: String,
        proposalId: UUID,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposalId"}"""
            }.andReturn()
            .response

    private fun delete(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse = mockMvc.delete(path) { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    /** Rows in a table, or in `table WHERE …` — test-only SQL, never caller input. */
    private fun count(from: String): Int = jdbc.queryForObject("SELECT count(*) FROM $from", Int::class.java)!!

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondColumn(
        bondId: String,
        column: String,
    ): String? = jdbc.queryForObject("SELECT $column::text FROM bonds WHERE id = ?", String::class.java, UUID.fromString(bondId))

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
