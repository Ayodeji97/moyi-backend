package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.CloseDay.Outcome
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The closer against a live write, in both orders (spec §9, "Consent and
 * lifecycle races"). The closer takes no bond lock (ADR-0031 decision 18), so
 * the day's row lock is the only thing between it and a submission, an edit
 * or a delete: whichever gets the row first finishes, and the other decides
 * on what it left.
 *
 * **How an order is forced, without a sleep.** A third connection holds the
 * day's row. The party meant to go first is started and seen to be queued on
 * that row; then the second is started and seen queued too; then the row is
 * released. Postgres hands a row lock to its waiters in the order they
 * arrived, so they run in the order they were queued.
 *
 * The requests run with the clock at 11:00 on the day; the closer is told
 * the day's end. That is two clocks, deliberately: what is on trial is which
 * of them the row lock lets through first, not what time it is.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(CloseRaceTest.TimeConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class CloseRaceTest(
    @Autowired private val closeDay: CloseDay,
    @Autowired private val closer: DayCloser,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val pool = Executors.newFixedThreadPool(4)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(NOW.minusSeconds(172_800))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @AfterAll
    fun stop() {
        pool.shutdownNow()
    }

    // --- a submission ---

    @Test
    fun `a submission that reaches the day first is counted by the closer behind it`() {
        anOpenDayNobodyHasWrittenOn()

        val (submitted, closed) = inOrder({ submit(bea, """{"text":"just in time"}""") }, { closeDay.settle(day(), END) })

        submitted.status shouldBe 201
        closed shouldBe Outcome.CLOSED
        // Decided on the row as the submission left it. Decided on what it
        // read before the lock, this day would be EMPTY with an entry on it.
        status() shouldBe "SOLO"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE deleted_at IS NULL AND revealed_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `a submission behind the closer is refused, never filed on the day it closed`() {
        anOpenDayNobodyHasWrittenOn()

        val (closed, submitted) = inOrder({ closeDay.settle(day(), END) }, { submit(bea, """{"text":"a moment late"}""") })

        closed shouldBe Outcome.CLOSED
        submitted.status shouldBe 409
        submitted.contentAsString shouldContain "DAY_CLOSED"
        status() shouldBe "EMPTY"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE deleted_at IS NULL", Int::class.java) shouldBe 0
    }

    // --- an edit ---

    @Test
    fun `an edit that reaches the day first is what the closer reveals`() {
        val entry = idOf(submit(ada, """{"text":"first wording"}"""))

        val (edited, closed) = inOrder({ patch(ada, entry, """{"text":"second wording"}""") }, { closeDay.settle(day(), END) })

        edited.status shouldBe 200
        closed shouldBe Outcome.CLOSED
        jdbc.queryForObject("SELECT text FROM entries WHERE revealed_at IS NOT NULL", String::class.java) shouldBe "second wording"
    }

    @Test
    fun `an edit behind the closer is refused, and the revealed words stand`() {
        val entry = idOf(submit(ada, """{"text":"first wording"}"""))

        val (closed, edited) = inOrder({ closeDay.settle(day(), END) }, { patch(ada, entry, """{"text":"second wording"}""") })

        closed shouldBe Outcome.CLOSED
        edited.status shouldBe 409
        edited.contentAsString shouldContain "ENTRY_IMMUTABLE"
        jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe "first wording"
    }

    // --- a delete ---

    @Test
    fun `a delete that reaches the day first leaves the closer an empty day`() {
        val entry = idOf(submit(ada, """{"text":"withdrawn at the last"}"""))

        val (deleted, closed) = inOrder({ delete(ada, entry) }, { closeDay.settle(day(), END) })

        deleted.status shouldBe 204
        closed shouldBe Outcome.CLOSED
        status() shouldBe "EMPTY"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `a delete behind the closer leaves a tombstone on a day that stays SOLO`() {
        val entry = idOf(submit(ada, """{"text":"withdrawn a moment late"}"""))

        val (closed, deleted) = inOrder({ closeDay.settle(day(), END) }, { delete(ada, entry) })

        closed shouldBe Outcome.CLOSED
        deleted.status shouldBe 204
        status() shouldBe "SOLO"
        jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE text IS NULL AND revealed_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    // --- the closer against itself: ShedLock lost, or two instances mid-deploy ---

    @Test
    fun `two runs settling one day at once close it once`() {
        submit(ada, """{"text":"hers"}""").status shouldBe 201

        val (first, second) = inOrder({ closeDay.settle(day(), END) }, { closeDay.settle(day(), END.plusSeconds(1)) })

        listOf(first, second) shouldContainExactlyInAnyOrder listOf(Outcome.CLOSED, Outcome.ALREADY_CLOSED)
        first shouldBe Outcome.CLOSED
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'DayClosed'", Int::class.java) shouldBe 1
    }

    // --- the closer reads the bond without its lock: a bond write that is still committing ---

    /**
     * The closer takes no bond lock (ADR-0031 decision 18), so it reads when
     * a bond became two from whatever has committed. A pairing stamped at
     * 23:59:59 can still be committing at 00:00:01 — and the job fires on the
     * quarter-hour, which is when days end. Read then, the bond is still one
     * person, the day is closed as a suspended day nobody is asked about,
     * and when the pairing lands a moment later it is the couple's joining
     * day, closed, which nothing reopens.
     *
     * So the job leaves a day alone until it has been over for a minute:
     * longer than a commit takes, shorter than anybody would notice.
     */
    @Test
    fun `a pairing still committing when midnight passes does not find its joining day closed`() {
        val cara = users.verified("Cara")
        val dan = users.verified("Dan")
        val created = createBond(cara)
        val waiting = idOf(created)
        submitTo(waiting, cara, """{"text":"written while waiting"}""").status shouldBe 201
        jdbc.execute("DELETE FROM bond_days WHERE bond_id = '$bond'")

        val accepted =
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                val pid =
                    holder.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid() FROM bonds WHERE id = '$waiting' FOR UPDATE").use { result ->
                            check(result.next())
                            result.getInt(1)
                        }
                    }
                // Stamped now, at 11:00 on the day, and parked behind the bond's row.
                val accepting = pool.submit(Callable { accept(dan, codeOf(created)) })
                try {
                    awaitQueued(pid, 1)
                    // Five seconds past midnight: the pairing has not committed.
                    closer.closeElapsedDays(END.plusSeconds(5), budget = 100).closed shouldBe 0
                    jdbc.queryForObject("SELECT closed_at IS NULL FROM bond_days WHERE bond_id = '$waiting'", Boolean::class.java) shouldBe
                        true
                } finally {
                    holder.rollback()
                }
                accepting.get(15, TimeUnit.SECONDS)
            }

        accepted.status shouldBe 200
        closer.closeElapsedDays(END.plus(DayCloser.SETTLE_MARGIN), budget = 100).closed shouldBe 1
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = '$waiting'", String::class.java) shouldBe "SOLO"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE bond_id = '$waiting' AND revealed_at IS NOT NULL", Int::class.java) shouldBe
            1
    }

    /**
     * Runs [first] and then [second], each queued on the day's row before the
     * other is let go: see the class KDoc. Fails, rather than passing by
     * accident, if either finishes without ever having waited for the row.
     */
    private fun <A, B> inOrder(
        first: () -> A,
        second: () -> B,
    ): Pair<A, B> =
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            val pid =
                holder.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_backend_pid() FROM bond_days FOR UPDATE").use { result ->
                        check(result.next())
                        result.getInt(1)
                    }
                }
            val a = pool.submit(Callable { first() })
            val b =
                try {
                    awaitQueued(pid, 1)
                    pool.submit(Callable { second() }).also { awaitQueued(pid, 2) }
                } finally {
                    holder.rollback()
                }
            a.get(15, TimeUnit.SECONDS) to b.get(15, TimeUnit.SECONDS)
        }

    private fun awaitQueued(
        holderPid: Int,
        waiters: Int,
    ) {
        // Counted as sessions waiting on a lock, not as sessions blocked by
        // the holder: a second waiter on a row queues behind the FIRST
        // waiter's claim on it, so `pg_blocking_pids` names that waiter and
        // not the holder. Nothing else in this test's database holds a lock.
        await().atMost(Duration.ofSeconds(10)).until {
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> ?",
                Int::class.java,
                holderPid,
            )!! >= waiters
        }
    }

    /** An `OPEN` row with no entry on it: Ada wrote and withdrew, which is the only way a request leaves one. */
    private fun anOpenDayNobodyHasWrittenOn() {
        delete(ada, idOf(submit(ada, """{"text":"thought better of it"}"""))).status shouldBe 204
        status() shouldBe "OPEN"
    }

    private fun day(): BondDayId = BondDayId(jdbc.queryForObject("SELECT id FROM bond_days", UUID::class.java)!!)

    private fun status(): String? = jdbc.queryForObject("SELECT status FROM bond_days", String::class.java)

    private fun submit(
        caller: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun submitTo(
        bondId: String,
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

    private fun patch(
        user: UUID,
        id: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun delete(
        user: UUID,
        id: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$id") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
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

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val END: Instant = Instant.parse("2026-09-15T23:00:00Z")
    }
}
