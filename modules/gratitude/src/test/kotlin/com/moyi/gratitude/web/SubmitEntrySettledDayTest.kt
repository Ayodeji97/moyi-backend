package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * BR-3a's recheck under the day lock (spec §6.1.3): "Recheck closed/revealed
 * state under the day lock; if a close raced with offline assignment,
 * redirect once to the submission-time day."
 *
 * **There is no close job yet (C3), so the close is a raw JDBC transaction.**
 * It takes `bond_days`' own `FOR UPDATE` on the day — the lock a close would
 * hold — waits until the submission is observed queued behind it through
 * `pg_blocking_pids`, then stamps the day closed and commits. That is the
 * race itself, frozen at the one moment that matters: the submission has
 * already resolved its day against a row that was open when it looked
 * (`DayAssignment.resolve`'s own check), and gets the lock only after the
 * close has landed. No sleeps; `SubmitEntryBondLockTest`'s technique, one
 * lock further down the order.
 *
 * **What goes red without the recheck.** Remove it from `SubmitEntry.write`
 * and every raced submission below still queues, still gets the lock — and
 * then files its entry on the day that was just closed. Each test fails on an
 * assertion about where the entry landed, or on the status, never on a
 * timeout.
 *
 * The clock: Africa/Lagos is UTC+1 all year, so the bond's 14th is
 * `[09-13T23:00Z, 09-14T23:00Z)` and its 15th the 24 hours after. [NOW] is
 * 11:00 on the 15th; [YESTERDAY_EVENING] is 21:00 on the 14th, 14 hours
 * before [NOW] — inside the 36h offline window, after the bond was created.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class SubmitEntrySettledDayTest(
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
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(BOND_CREATED)
        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        // The 14th exists, and is still open, because Bea wrote on it.
        clock.set(YESTERDAY_NOON)
        submit(bea, """{"text":"bea, on the 14th"}""").status shouldBe 201
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        // The pool first, and awaited: a submission still queued after a
        // failed assertion must not commit after the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `an offline draft whose day closes while it waits for the day lock lands on today instead`() {
        withDayLockHeld(YESTERDAY) { holderPid, closeAndCommit ->
            val submission = async { submit(ada, """{"text":"written on the flight","intendedAt":"$YESTERDAY_EVENING"}""") }
            awaitBlockedOrDone(holderPid, submission)
            // It resolved to the 14th (open when it looked) and is queued on
            // that day's row. Done already means it never took the day lock.
            submission.isDone shouldBe false

            closeAndCommit("SOLO")

            val response = submission.get(10, TimeUnit.SECONDS)
            response.status shouldBe 201
            // Redirected: filed on the submission-time day, and stamped with
            // the submission instant — the rejected claim is never stored (F1).
            response.contentAsString shouldContain "\"date\":\"$TODAY\""
            intendedAtOf(response) shouldBe NOW
            entriesOn(YESTERDAY) shouldBe 1 // Bea's, and only Bea's
            entryCountOf(YESTERDAY) shouldBe 1
            statusOf(YESTERDAY) shouldBe "SOLO"
            entriesOn(TODAY) shouldBe 1
            entryCountOf(TODAY) shouldBe 1
        }
    }

    @Test
    fun `a submission for today whose day closes while it waits is refused, not filed on a closed day`() {
        // No intendedAt: the entry is ON the submission-time day already, so
        // there is nowhere to redirect it. Spec §6.2: `409 DAY_CLOSED` "if it
        // ever arrives another way".
        submit(bea, """{"text":"bea, on the 15th"}""").status shouldBe 201

        withDayLockHeld(TODAY) { holderPid, closeAndCommit ->
            val submission = async { submit(ada, """{"text":"a moment too late"}""") }
            awaitBlockedOrDone(holderPid, submission)
            submission.isDone shouldBe false

            closeAndCommit("SOLO")

            val response = submission.get(10, TimeUnit.SECONDS)
            response.status shouldBe 409
            response.contentAsString shouldContain "\"code\":\"DAY_CLOSED\""
            entriesOn(TODAY) shouldBe 1
            entryCountOf(TODAY) shouldBe 1
        }
    }

    @Test
    fun `the redirect happens once - a settled submission-time day refuses the entry`() {
        // Today is already settled when the race begins; yesterday closes
        // during it. The redirect lands on a settled day and stops there.
        submit(bea, """{"text":"bea, on the 15th"}""").status shouldBe 201
        jdbc.update("UPDATE bond_days SET status = 'FROZEN', closed_at = now() WHERE bond_id = ?::uuid AND date = ?::date", bondId, TODAY)

        withDayLockHeld(YESTERDAY) { holderPid, closeAndCommit ->
            val submission = async { submit(ada, """{"text":"nowhere to go","intendedAt":"$YESTERDAY_EVENING"}""") }
            awaitBlockedOrDone(holderPid, submission)
            submission.isDone shouldBe false

            closeAndCommit("SOLO")

            val response = submission.get(10, TimeUnit.SECONDS)
            response.status shouldBe 409
            response.contentAsString shouldContain "\"code\":\"DAY_CLOSED\""
            jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2 // Bea's two
            jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 2
        }
    }

    @Test
    fun `an elapsed suspended day stamped closed is settled, though its status never changed`() {
        // Spec §6.1.2: settled is `closedAt != null`, "including FROZEN and
        // elapsed SUSPENDED" — the close stamps a SUSPENDED day and leaves its
        // status alone (§6.4), so a check on the status would file the draft
        // on a closed day. No race needed: the day is settled before the
        // submission looks.
        jdbc.update(
            "UPDATE bond_days SET status = 'SUSPENDED', closed_at = now() WHERE bond_id = ?::uuid AND date = ?::date",
            bondId,
            YESTERDAY,
        )

        val response = submit(ada, """{"text":"written on the flight","intendedAt":"$YESTERDAY_EVENING"}""")

        response.status shouldBe 201
        response.contentAsString shouldContain "\"date\":\"$TODAY\""
        intendedAtOf(response) shouldBe NOW
        entriesOn(YESTERDAY) shouldBe 1
    }

    // ---- the close, standing in for C3 ----------------------------------

    private fun <T> async(call: () -> T): Future<T> = pool.submit<T> { call() }

    /**
     * Opens a transaction on its own connection, takes the day row's
     * `FOR UPDATE` — `BondDayRepository.lockRow`'s own lock — and hands
     * [block] the holder's backend pid and a `closeAndCommit(status)` that
     * stamps the day closed under that lock and commits. Rolled back if
     * [block] never closed it, which is what frees a submission still queued
     * behind a failed assertion.
     */
    private fun withDayLockHeld(
        date: String,
        block: (holderPid: Int, closeAndCommit: (String) -> Unit) -> Unit,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockDayRow(connection, date)
                block(backendPidOf(connection)) { status ->
                    closeDay(connection, date, status)
                    connection.commit()
                }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun lockDayRow(
        connection: Connection,
        date: String,
    ) {
        connection.prepareStatement("SELECT 1 FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date FOR UPDATE").use {
            it.setString(1, bondId)
            it.setString(2, date)
            it.executeQuery().use { rows -> rows.next() shouldBe true }
        }
    }

    private fun closeDay(
        connection: Connection,
        date: String,
        status: String,
    ) {
        connection.prepareStatement("UPDATE bond_days SET status = ?, closed_at = now() WHERE bond_id = ?::uuid AND date = ?::date").use {
            it.setString(1, status)
            it.setString(2, bondId)
            it.setString(3, date)
            it.executeUpdate() shouldBe 1
        }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** Until [waiter] is either finished or genuinely queued behind [holderPid] — `SubmitEntryBondLockTest`'s own wait. */
    private fun awaitBlockedOrDone(
        holderPid: Int,
        waiter: Future<*>,
    ) {
        await().atMost(Duration.ofSeconds(10)).until {
            waiter.isDone ||
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Int::class.java,
                    holderPid,
                )!! > 0
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun entriesOn(date: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entries e JOIN bond_days d ON d.id = e.bond_day_id WHERE d.bond_id = ?::uuid AND d.date = ?::date",
            Int::class.java,
            bondId,
            date,
        )!!

    private fun entryCountOf(date: String): Int =
        jdbc.queryForObject("SELECT entry_count FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date", Int::class.java, bondId, date)!!

    private fun statusOf(date: String): String =
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date", String::class.java, bondId, date)!!

    private fun intendedAtOf(response: MockHttpServletResponse): Instant =
        Instant.parse(Regex(""""intendedAt":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1])

    private fun submit(
        caller: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        /** 11:00 on the 15th in Africa/Lagos — nowhere near a day boundary. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** 21:00 on the 14th in Africa/Lagos; 14 hours before [NOW]. */
        val YESTERDAY_EVENING: Instant = Instant.parse("2026-09-14T20:00:00Z")

        /** 13:00 on the 14th in Africa/Lagos. */
        val YESTERDAY_NOON: Instant = Instant.parse("2026-09-14T12:00:00Z")

        /** 11:00 on the 13th — before every instant a test claims. */
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")

        const val YESTERDAY = "2026-09-14"
        const val TODAY = "2026-09-15"
    }
}
