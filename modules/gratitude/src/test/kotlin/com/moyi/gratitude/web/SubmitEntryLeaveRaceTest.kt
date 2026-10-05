package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * A submission against a **real** `POST /bonds/{id}/leave` (final
 * whole-branch review, B3).
 *
 * [SubmitEntryBondLockTest] proves the submit path takes the bond's row lock,
 * with raw JDBC standing in for the other writer. This is the other half:
 * that the lifecycle write it is meant to serialise against — `EndBond.leave`,
 * through its endpoint — really takes that same lock, so the two cannot
 * interleave. There are exactly two legal outcomes, and each is staged here
 * and then left to chance once more:
 *
 * - **the entry first:** `201`, then the leave's `204`. The entry was
 *   committed on an open bond, and stays in the archive it becomes.
 * - **the leave first:** `204`, then `409 BOND_ARCHIVED`. Nothing is stored.
 *
 * What must never happen is the third: an entry committed *after* the bond
 * ended.
 *
 * **How a real request is held mid-transaction.** Neither endpoint can be
 * paused from outside, but each one, after taking the bond lock, goes on to
 * touch a row the other never locks: the leave updates the leaver's
 * `bond_members` row, the submission locks its `bond_days` row. The test
 * holds that row on a connection of its own, so the request stops *while
 * holding the bond lock*, and the other request is then started and
 * **observed queued behind it** — through `pg_blocking_pids`, never a sleep.
 * Nothing is signalled or released until that queue has been seen.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class SubmitEntryLeaveRaceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW)
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        bondId = Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
    }

    @AfterEach
    fun clear() {
        // The pool first, and awaited: a request still queued after a failed
        // assertion must not commit after the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a leave that holds the bond lock first ends the bond, and the submission queued behind it is refused`() {
        val adasMemberRow =
            "SELECT pg_backend_pid() FROM bond_members WHERE bond_id = '$bondId'::uuid AND user_id = '$ada'::uuid FOR UPDATE"
        withRowHeld(adasMemberRow) { holderPid, release ->
            // Ada leaves: bond lock taken, then stopped at her own member row.
            val leaving = pool.submit<MockHttpServletResponse> { leave(ada) }
            val leavePid = awaitQueuedBehind(holderPid, leaving)
            // Bea submits, and has to wait for the leave — not for the test's row.
            val submission = pool.submit<MockHttpServletResponse> { submit(bea) }
            awaitQueuedBehind(leavePid, submission)

            release()

            leaving.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 204
            val refused = submission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            refused.status shouldBe 409
            refused.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        }

        bondStatus() shouldBe "ARCHIVED"
        count("entries") shouldBe 0
        count("bond_days") shouldBe 0
    }

    @Test
    fun `a submission that holds the bond lock first commits its entry, and the leave queued behind it then ends the bond`() {
        // Ada's entry opens today's row, so there is a bond-day to hold.
        submit(ada).status shouldBe 201

        withRowHeld("SELECT pg_backend_pid() FROM bond_days WHERE bond_id = '$bondId'::uuid FOR UPDATE") { holderPid, release ->
            // Bea submits: bond lock taken, then stopped at the day's row.
            val submission = pool.submit<MockHttpServletResponse> { submit(bea) }
            val submitPid = awaitQueuedBehind(holderPid, submission)
            // Ada leaves, and has to wait for the submission.
            val leaving = pool.submit<MockHttpServletResponse> { leave(ada) }
            awaitQueuedBehind(submitPid, leaving)
            // Still open while the entry is in flight: the leave has not happened.
            bondStatus() shouldBe "ACTIVE"

            release()

            submission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 201
            leaving.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 204
        }

        bondStatus() shouldBe "ARCHIVED"
        count("entries") shouldBe 2
        jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 2
    }

    /**
     * The same two requests with nothing held: released together, and
     * whichever takes the bond lock first wins. Either legal outcome is
     * accepted; what is asserted is that the outcome is one of them and that
     * the rows agree with the statuses — an entry exists exactly when its
     * submission was told `201`.
     */
    @RepeatedTest(RACES)
    fun `left to chance, a leave and a submission end in one of the two legal outcomes`() {
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)

        fun <T> racing(call: () -> T): Future<T> =
            pool.submit<T> {
                ready.countDown()
                check(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the race was never started" }
                call()
            }
        val leaving = racing { leave(ada) }
        val submission = racing { submit(bea) }
        ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
        go.countDown()

        leaving.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 204
        val submitted = submission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        submitted.status shouldBeIn listOf(201, 409)
        if (submitted.status == 409) submitted.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        bondStatus() shouldBe "ARCHIVED"
        count("entries") shouldBe if (submitted.status == 201) 1 else 0
        count("bond_days") shouldBe if (submitted.status == 201) 1 else 0
    }

    // ---- the held row -----------------------------------------------------

    /**
     * Runs [lockingQuery] — a `SELECT pg_backend_pid() … FOR UPDATE` — on a
     * connection of its own and hands [block] that backend's pid and a
     * `release`. Rolled back in any case, which is also what frees a request
     * still queued behind a failed assertion.
     */
    private fun withRowHeld(
        lockingQuery: String,
        block: (holderPid: Int, release: () -> Unit) -> Unit,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(lockRow(connection, lockingQuery)) { connection.rollback() }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun lockRow(
        connection: Connection,
        lockingQuery: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(lockingQuery).use { rows ->
                rows.next() shouldBe true
                rows.getInt(1)
            }
        }

    /**
     * Waits until [waiter] is genuinely queued behind [blockerPid] and answers
     * the waiter's own backend pid. Fails if the waiter finishes instead: it
     * was supposed to be stopped.
     */
    private fun awaitQueuedBehind(
        blockerPid: Int,
        waiter: Future<*>,
    ): Int {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { waiter.isDone || queuedBehind(blockerPid).isNotEmpty() }
        waiter.isDone shouldBe false
        return queuedBehind(blockerPid).single()
    }

    private fun queuedBehind(blockerPid: Int): List<Int> =
        jdbc
            .queryForList("SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, blockerPid)
            .filterNotNull()

    // ---- helpers ----------------------------------------------------------

    private fun bondStatus(): String? = jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bondId)

    private fun count(table: String): Int? = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun submit(caller: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response

    private fun leave(caller: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
            .andReturn()
            .response

    private companion object {
        /** Midday-plus-one in Africa/Lagos, nowhere near a day boundary. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val TIMEOUT_SECONDS = 10L
        const val RACES = 10
    }
}
