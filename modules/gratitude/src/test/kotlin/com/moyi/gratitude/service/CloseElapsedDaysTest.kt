package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.CloseCandidates
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.function.ThrowingSupplier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The sweep, through the port `scheduling` will call ([DayCloser]): which
 * days it finds, that it settles each on its own, and that it can be run
 * again at any time.
 *
 * Three bonds in three zones share one instant, 10:00Z on 2026-09-15, and
 * each has one entry on its own "today":
 *
 * | Zone | Its date at 10:00Z | That day ends |
 * |---|---|---|
 * | `Pacific/Auckland` (+12) | the 15th | 12:00Z on the 15th |
 * | `Asia/Kathmandu` (+5:45) | the 15th | 18:15Z on the 15th |
 * | `Africa/Lagos` (+1) | the 15th | 23:00Z on the 15th |
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(CloseElapsedDaysTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class CloseElapsedDaysTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    /**
     * Before as well as after. The close job works on every bond in the
     * database, and this database is shared with test classes that leave
     * bonds behind: their unwritten days would be written first and use up
     * the job's allowance for a run before it reached the bond under test.
     */
    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `each bond's day closes when its own calendar says and its grace has run out, whatever the others are doing`() {
        val auckland = bondWithOneEntry("Pacific/Auckland")
        val kathmandu = bondWithOneEntry("Asia/Kathmandu")
        val lagos = bondWithOneEntry("Africa/Lagos")

        // Auckland's day ended at 12:00Z. It is settled twelve hours later (BR-3a's
        // late-entry grace) and a minute more (`DayCloser.SETTLE_MARGIN`): not at its end,
        // and not a minute after it, which is when it was settled before the grace.
        closer.closeElapsedDays(Instant.parse("2026-09-15T12:01:00Z"), BUDGET).closed shouldBe 0
        closer.closeElapsedDays(Instant.parse("2026-09-16T00:00:59Z"), BUDGET).closed shouldBe 0
        statuses() shouldBe mapOf(auckland to "PARTIAL", kathmandu to "PARTIAL", lagos to "PARTIAL")

        val afterAuckland = closer.closeElapsedDays(Instant.parse("2026-09-16T00:01:00Z"), BUDGET)
        afterAuckland.closed shouldBe 1
        afterAuckland.bondsChanged shouldBe setOf(UUID.fromString(auckland))
        statuses() shouldBe mapOf(auckland to "SOLO", kathmandu to "PARTIAL", lagos to "PARTIAL")

        // 18:15Z: Kathmandu's quarter-hour offset is why the job runs every fifteen minutes.
        closer.closeElapsedDays(Instant.parse("2026-09-16T06:15:59Z"), BUDGET).closed shouldBe 0
        closer.closeElapsedDays(Instant.parse("2026-09-16T06:16:00Z"), BUDGET).closed shouldBe 1
        statuses() shouldBe mapOf(auckland to "SOLO", kathmandu to "SOLO", lagos to "PARTIAL")

        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1
        statuses() shouldBe mapOf(auckland to "SOLO", kathmandu to "SOLO", lagos to "SOLO")
    }

    /**
     * Issue #132, the reason for the grace: written offline before the day ended and sent the
     * next morning, an entry counts for the day it was written, and the day is decided once,
     * after that — not at midnight, where the lone entry would have closed `SOLO`, been
     * unlocked to the partner and broken the streak, and the late entry been filed on today.
     */
    @Test
    fun `a day left open through its grace takes a late entry, and is decided once, after it`() {
        val (bond, ada, bea) = pairedBond("Africa/Lagos")
        submit(ada, bond, "hers").status shouldBe 201

        // 07:00 on the 16th in Lagos: the 15th ended eight hours ago. The job leaves it open.
        clock.set(Instant.parse("2026-09-16T06:00:00Z"))
        val inGrace = closer.closeElapsedDays(clock.instant(), BUDGET)
        inGrace.closed shouldBe 0
        inGrace.created shouldBe 0
        statuses() shouldBe mapOf(bond to "PARTIAL")
        events("DayClosed") shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0

        // Bea wrote at 23:00 on the 15th, offline, and her phone sends it now.
        submitIntended(bea, bond, "his, from the flight", Instant.parse("2026-09-15T22:00:00Z")).let { response ->
            response.status shouldBe 201
            response.contentAsString shouldContain "\"date\":\"2026-09-15\""
        }
        jdbc.queryForObject("SELECT status FROM bond_days WHERE date = '2026-09-15'", String::class.java) shouldBe "REVEALED"

        // The grace runs out at 11:00Z on the 16th; the job settles the day a minute later, once.
        closer.closeElapsedDays(Instant.parse("2026-09-16T11:00:59Z"), BUDGET).closed shouldBe 0
        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1
        closer.closeElapsedDays(LAGOS_END.plusSeconds(900), BUDGET).closed shouldBe 0
        jdbc.queryForObject("SELECT status FROM bond_days WHERE date = '2026-09-15'", String::class.java) shouldBe "REVEALED"
        jdbc.queryForObject("SELECT evaluated_as FROM bond_days WHERE date = '2026-09-15'", String::class.java) shouldBe "COMPLETE"
        events("DayClosed") shouldBe 1
    }

    @Test
    fun `a day nobody opened is not written closed inside its grace, so a late entry can still open it`() {
        val (bond, _, bea) = pairedBond("Africa/Lagos")

        clock.set(Instant.parse("2026-09-16T06:00:00Z"))
        closer.closeElapsedDays(clock.instant(), BUDGET).created shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 0

        submitIntended(bea, bond, "his, late", Instant.parse("2026-09-15T22:00:00Z")).contentAsString shouldContain
            "\"date\":\"2026-09-15\""
        statuses() shouldBe mapOf(bond to "PARTIAL")

        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1
        statuses() shouldBe mapOf(bond to "SOLO")
    }

    @Test
    fun `a second run finds nothing the first one finished`() {
        repeat(3) { bondWithOneEntry("Africa/Lagos") }

        val first = closer.closeElapsedDays(LAGOS_END, BUDGET)
        val second = closer.closeElapsedDays(LAGOS_END.plusSeconds(900), BUDGET)

        first.closed shouldBe 3
        first.backlog shouldBe false
        second shouldBe
            second.copy(created = 0, closed = 0, revealed = 0, evaluated = 0, failed = 0, bondsChanged = emptySet(), backlog = false)
        events("DayClosed") shouldBe 3
    }

    @Test
    fun `the budget is honoured, the result says days are waiting, and the next run takes them`() {
        repeat(3) { bondWithOneEntry("Africa/Lagos") }

        val first = closer.closeElapsedDays(LAGOS_END, budget = 2)
        first.closed shouldBe 2
        first.backlog shouldBe true

        val second = closer.closeElapsedDays(LAGOS_END, budget = 2)
        second.closed shouldBe 1
        second.backlog shouldBe false
    }

    @Test
    fun `one day that cannot be closed is counted and left, and the others close`(output: CapturedOutput) {
        val broken = bondWithOneEntry("Africa/Lagos", text = "privacy-canary-in-the-broken-day")
        val fine = listOf(bondWithOneEntry("Africa/Lagos"), bondWithOneEntry("Africa/Lagos"))
        // Closing a one-entry day reveals the entry; this forbids that for one bond only.
        jdbc.execute("ALTER TABLE entries ADD CONSTRAINT c3_sweep_probe CHECK (revealed_at IS NULL OR bond_id <> '$broken')")
        try {
            val result = closer.closeElapsedDays(LAGOS_END, BUDGET)

            result.closed shouldBe 2
            result.failed shouldBe 1
            result.bondsChanged shouldBe fine.map(UUID::fromString).toSet()
            statuses()[broken] shouldBe "PARTIAL"
            output.all shouldContain "close: day"
            output.all shouldNotContain "privacy-canary"
        } finally {
            jdbc.execute("ALTER TABLE entries DROP CONSTRAINT c3_sweep_probe")
        }
        // Still a candidate: nothing recorded that it was tried.
        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1
    }

    @Test
    fun `a bond that has ended does not strand the day it ended on`() {
        val (bond, ada) = bondWithOneEntryAndItsAuthor("Africa/Lagos")
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1

        statuses()[bond] shouldBe "SOLO"
    }

    @Test
    fun `a bond counting down to deletion does not strand the day the countdown began on`() {
        val (bond, ada, bea) = pairedBond("Africa/Lagos")
        submit(ada, bond, "hers").status shouldBe 201
        for (member in listOf(ada, bea)) {
            mockMvc
                .post("/api/v1/bonds/$bond/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(member)) }
                .andReturn()
                .response.status shouldBe 202
        }

        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1

        statuses()[bond] shouldBe "SOLO"
    }

    /**
     * ADR-0035 question 9, ruled 2026-10-21: an ended bond reveals nothing new. This test said
     * the opposite until the ruling, which reversed what was built: Ada left before 20:00.
     */
    @Test
    fun `an ended bond's day that was waiting on its reveal time is withheld when the time comes`() {
        val (bond, ada, bea) = pairedBond("Africa/Lagos")
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00' WHERE id = ?::uuid", bond)
        submit(ada, bond, "hers").status shouldBe 201
        submit(bea, bond, "his").status shouldBe 201
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        closer.closeElapsedDays(Instant.parse("2026-09-15T18:59:00Z"), BUDGET).revealed shouldBe 0
        closer.closeElapsedDays(Instant.parse("2026-09-15T19:00:00Z"), BUDGET).revealed shouldBe 0
        statuses()[bond] shouldBe "PENDING_REVEAL"

        closer.closeElapsedDays(LAGOS_END, BUDGET).closed shouldBe 1
        statuses()[bond] shouldBe "PENDING_REVEAL"
        events("DayRevealed") shouldBe 0
    }

    @Test
    fun `a bond whose calendar cannot be read stops neither the days written for others nor their closing`(output: CapturedOutput) {
        val broken = bondWithOneEntry("Africa/Lagos", text = "privacy-canary-on-the-broken-bond")
        val fine = bondWithOneEntry("Africa/Lagos")
        jdbc.update("DELETE FROM bond_anchor_intervals WHERE bond_id = ?::uuid", broken)

        val result = closer.closeElapsedDays(LAGOS_END, BUDGET)

        // The broken bond fails twice over: its missing days, and its one day with a row.
        result.failed shouldBe 2
        result.closed shouldBe 1
        statuses() shouldBe mapOf(broken to "PARTIAL", fine to "SOLO")
        output.all shouldContain "close: the missing days of bond $broken could not be written"
        output.all shouldNotContain "privacy-canary"
    }

    @Test
    fun `a day waiting on its reveal time is found before its end, and revealed when the time comes`() {
        val (bond, ada, bea) = pairedBond("Africa/Lagos")
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00' WHERE id = ?::uuid", bond)
        submit(ada, bond, "hers").status shouldBe 201
        submit(bea, bond, "his").status shouldBe 201
        statuses()[bond] shouldBe "PENDING_REVEAL"

        val early = closer.closeElapsedDays(Instant.parse("2026-09-15T18:59:00Z"), BUDGET)
        early.revealed shouldBe 0
        early.closed shouldBe 0

        val due = closer.closeElapsedDays(Instant.parse("2026-09-15T19:00:00Z"), BUDGET)
        due.revealed shouldBe 1
        due.closed shouldBe 0
        due.bondsChanged shouldBe setOf(UUID.fromString(bond))
        statuses()[bond] shouldBe "REVEALED"
        events("DayRevealed") shouldBe 1
    }

    @Test
    fun `a day looked at and left is not looked at for ever within one run`() {
        // Five days pending a reveal that is hours away: all five are
        // candidates on every page, none changes, and the run must end.
        val bonds =
            List(5) {
                val (bond, ada, bea) = pairedBond("Africa/Lagos")
                jdbc.update("UPDATE bonds SET reveal_time_local = '22:00' WHERE id = ?::uuid", bond)
                submit(ada, bond, "hers").status shouldBe 201
                submit(bea, bond, "his").status shouldBe 201
                bond
            }

        val result = closer.closeElapsedDays(Instant.parse("2026-09-15T12:00:00Z"), BUDGET)

        result shouldBe result.copy(closed = 0, revealed = 0, failed = 0, backlog = false)
        bonds.forEach { statuses()[it] shouldBe "PENDING_REVEAL" }
    }

    @Test
    fun `days that are looked at and left do not use up the budget of the days behind them`() {
        // Los Angeles, so that the five reveal times (22:00 there, 05:00Z on the 16th) are
        // still ahead when Auckland's day settles, twelve hours after it ends.
        repeat(5) {
            val (bond, ada, bea) = pairedBond("America/Los_Angeles")
            jdbc.update("UPDATE bonds SET reveal_time_local = '22:00' WHERE id = ?::uuid", bond)
            submit(ada, bond, "hers").status shouldBe 201
            submit(bea, bond, "his").status shouldBe 201
        }
        val ended = bondWithOneEntry("Pacific/Auckland")

        // Auckland's day is over at 12:00Z and settles at 00:01Z; the five days are pending until 05:00Z.
        val result =
            assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                ThrowingSupplier { closer.closeElapsedDays(Instant.parse("2026-09-16T00:01:00Z"), budget = 1) },
            )

        result.closed shouldBe 1
        result.backlog shouldBe false
        statuses()[ended] shouldBe "SOLO"
    }

    /**
     * What this guards: the job's statement can be answered from `bond_days_unclosed_idx`, the
     * partial index that holds only the days still to be settled, so the scan reads those and
     * not every day there has ever been. A statement that stopped implying `closed_at IS NULL`
     * could not use it.
     *
     * Deterministic, not a cost race. Which index the planner prefers depends on the statistics,
     * and on the shared test database those are whatever the other tests left there: an index
     * that walks `bond_id` in order (`bond_days_bond_date_key`, and later `bond_days_feed_idx`,
     * read forwards under an Incremental Sort) lets the `LIMIT` stop early, and each has won in
     * CI. Dropping each competitor by name broke again when the next one won, so every other
     * index on the table is dropped, whatever it is called and whenever it was added, inside a
     * transaction that is rolled back; and sequential scans are priced out. What is left is the
     * partial index, or a Seq Scan if the statement cannot use it.
     *
     * The transaction also holds the data that tipped the planner in CI (days that have ended and
     * are not yet settled, analysed), so that a regression of this guard fails here every time
     * rather than on whichever run the statistics happen to favour another index.
     */
    @Test
    fun `the scan for days that may have ended can use its index`() {
        val plan = candidatePlan()

        plan shouldContain UNCLOSED_INDEX
        plan shouldNotContain "Seq Scan"
    }

    /** `EXPLAIN` of the job's own statement, with every index on `bond_days` but [UNCLOSED_INDEX] gone, rolled back. */
    private fun candidatePlan(): String =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    // Never queue the other tests behind these locks for long.
                    statement.execute("SET LOCAL lock_timeout = '5s'")
                    statement.execute(ENDED_UNSETTLED_DAYS)
                    statement.execute("ANALYZE bond_days")
                    competingIndexes(connection).forEach(statement::execute)
                    statement.execute("SET LOCAL enable_seqscan = off")
                }
                explainCandidates(connection)
            } finally {
                connection.rollback()
            }
        }

    /**
     * A statement that removes each index on `bond_days` other than [UNCLOSED_INDEX]. An index
     * that backs a constraint (the primary key, a unique constraint) cannot be dropped on its
     * own, so its constraint is, with whatever foreign keys lean on it.
     */
    private fun competingIndexes(connection: java.sql.Connection): List<String> =
        connection
            .prepareStatement(
                """
                SELECT i.relname, c.conname
                FROM pg_index x
                JOIN pg_class i ON i.oid = x.indexrelid
                LEFT JOIN pg_constraint c ON c.conindid = x.indexrelid AND c.conrelid = x.indrelid
                WHERE x.indrelid = 'bond_days'::regclass AND i.relname <> ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, UNCLOSED_INDEX)
                statement.executeQuery().use { rows ->
                    generateSequence {
                        if (!rows.next()) {
                            null
                        } else {
                            rows.getString(2)?.let { "ALTER TABLE bond_days DROP CONSTRAINT \"$it\" CASCADE" }
                                ?: "DROP INDEX \"${rows.getString(1)}\""
                        }
                    }.toList()
                }
            }

    /** The statement the job runs, not a copy of it. */
    private fun explainCandidates(connection: java.sql.Connection): String =
        connection
            .prepareStatement("EXPLAIN " + CloseCandidates.SQL)
            .use { statement ->
                statement.setTimestamp(1, java.sql.Timestamp.from(LAGOS_END))
                statement.setObject(2, UUID(0, 0))
                statement.setObject(3, java.time.LocalDate.of(1, 1, 1))
                statement.setInt(4, 200)
                statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }.joinToString("\n")

    private fun statuses(): Map<String, String> =
        jdbc.query("SELECT bond_id, status FROM bond_days") { row, _ -> row.getString(1) to row.getString(2) }.toMap()

    private fun events(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun bondWithOneEntry(
        zone: String,
        text: String = "hers",
    ): String = bondWithOneEntryAndItsAuthor(zone, text).first

    private fun bondWithOneEntryAndItsAuthor(
        zone: String,
        text: String = "hers",
    ): Pair<String, UUID> {
        val (bond, ada, _) = pairedBond(zone)
        submit(ada, bond, text).status shouldBe 201
        return bond to ada
    }

    /**
     * Two fresh people, paired an hour before [NOW] — the same local day in
     * all three zones. Today is the bond's first day as two, so there is no
     * earlier day for the job to write: these tests are about the days that
     * have a row (`MissingDaysTest` has the ones that do not).
     */
    private fun pairedBond(zone: String): Triple<String, UUID, UUID> {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        clock.set(NOW.minusSeconds(3_600))
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$zone"}"""
                }.andReturn()
                .response
        mockMvc
            .post("/api/v1/invites/${codeOf(created)}/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
        clock.set(NOW)
        return Triple(idOf(created), ada, bea)
    }

    private fun submit(
        caller: UUID,
        bondId: String,
        text: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$text"}"""
            }.andReturn()
            .response

    private fun submitIntended(
        caller: UUID,
        bondId: String,
        text: String,
        intendedAt: Instant,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$text","intendedAt":"$intendedAt"}"""
            }.andReturn()
            .response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

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

        /**
         * Twelve hours and a minute past Lagos's midnight: the first instant the job settles the
         * 15th (BR-3a's late-entry grace, then `DayCloser.SETTLE_MARGIN`).
         */
        val LAGOS_END: Instant = Instant.parse("2026-09-16T11:01:00Z")
        const val BUDGET = 1_000

        const val UNCLOSED_INDEX = "bond_days_unclosed_idx"

        /** Five thousand bonds, each with a day that ended long before [LAGOS_END] and is not yet settled. */
        const val ENDED_UNSETTLED_DAYS =
            "INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, created_at) " +
                "SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-01-01', 'OPEN', 'UTC', " +
                "TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-02 00:00:00+00', " +
                "TIMESTAMPTZ '2026-01-01 00:00:00+00' FROM generate_series(1, 5000)"
    }
}
