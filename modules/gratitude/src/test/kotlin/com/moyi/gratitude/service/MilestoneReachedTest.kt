package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `MilestoneReached` (FR-075, spec §8; ADR-0041 decision 8): written by the close job's
 * evaluation, in the transaction that records the day, once for each threshold of complete
 * days a day takes the bond across, carrying the bond's id and the day's and nothing else.
 *
 * Days are written by two people's requests and the job is run with an instant, as in
 * `StreakEvaluationTest` (whose clock configuration this shares, so the two share a context).
 * `Africa/Lagos`: day *n* is 2026-09-01 plus *n*, ends at 23:00Z, and the job settles it a
 * minute later. The bond is paired on day 0.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEvaluationTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class MilestoneReachedTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val streaks: EvaluateStreaks,
    @Autowired private val closeDay: CloseDay,
    @Autowired private val recalculate: RecalculateStreak,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(0))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
    }

    /** Before as well as after ([setUp] calls it first): the job works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    /** Spec §8: ids only. H-M5: no member is named, so a consumer can reach the two only through the bond. */
    @Test
    fun `the seventh complete day writes one MilestoneReached, naming the bond and that day and nobody`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        milestones() shouldBe emptyList()

        bothWriteOn(7)
        closeThrough(7)

        milestones() shouldBe listOf(Announced(dayId(7), "BondDay", dayId(7), bond, "bondDayId,bondId"))
        // The same transaction as the day's record: the day is evaluated, and as COMPLETE.
        jdbc.queryForObject(
            "SELECT evaluated_as FROM bond_days WHERE id = ?",
            String::class.java,
            dayId(7),
        ) shouldBe "COMPLETE"
    }

    /**
     * One run of the job over a month: each threshold on the day that crossed it. Day 15 only
     * Ada wrote, and the freeze banked on day 14 covers it: the run goes on and the day is not
     * a complete day (Q-M1). So the run reaches 30 on day 30 and the count on day 31.
     */
    @Test
    fun `a backlog crosses each threshold on its own day, and a day a freeze covered counts toward none`() {
        (1..14).forEach { bothWriteOn(it) }
        writeOn(15, ada)
        (16..31).forEach { bothWriteOn(it) }

        closeThrough(31)

        jdbc.queryForObject("SELECT status FROM bond_days WHERE id = ?", String::class.java, dayId(15)) shouldBe "FROZEN"
        milestones().map { it.dayId } shouldBe listOf(dayId(7), dayId(31))
    }

    /** Day 4 only Ada wrote, with no freeze banked: the run breaks. The count does not: 3 before, 4 after. */
    @Test
    fun `a day only one wrote on reaches nothing, and the break after it takes nothing back`() {
        (1..3).forEach { bothWriteOn(it) }
        writeOn(4, ada)
        (5..8).forEach { bothWriteOn(it) }

        closeThrough(8)

        jdbc.queryForObject("SELECT current_streak FROM streak_states WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 4
        milestones().map { it.dayId } shouldBe listOf(dayId(8))
    }

    /** ADR-0034 decisions 9 and 10: a day is evaluated once, and a recalculation announces nothing, even when it changes the row. */
    @Test
    fun `a day evaluated again, and a recalculation, write no second MilestoneReached`() {
        (1..7).forEach { bothWriteOn(it) }
        closeThrough(7)
        closeThrough(7)
        closeThrough(9)

        recalculate.recalculate(UUID.fromString(bond)).changed shouldBe false
        jdbc.update("UPDATE streak_states SET total_complete_days = 3 WHERE bond_id = ?::uuid", bond) shouldBe 1
        recalculate.recalculate(UUID.fromString(bond)).let { (it.before.totalCompleteDays to it.after.totalCompleteDays) shouldBe (3 to 7) }

        milestones().map { it.dayId } shouldBe listOf(dayId(7))
        // Every row of the type, not only those that name a day of this bond: a replay has no day id to name.
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
    }

    /**
     * ADR-0041 decision 9: a day both wrote on before the bond ended is a complete day (ADR-0034
     * decision 7), and its milestone is history, written as on any other bond. Whether anybody is
     * told is the consumer's to ask (doc 02:144), not the producer's to decide. Bea leaves an hour
     * after both wrote the seventh day; the job evaluates it, and the days after, once it has ended.
     */
    @Test
    fun `a day both wrote on before the bond ended still writes its MilestoneReached`() {
        (1..7).forEach { bothWriteOn(it) }
        clock.set(at(7).plusSeconds(3_600))
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        closeThrough(9)

        jdbc.queryForObject("SELECT evaluated_as FROM bond_days WHERE id = ?", String::class.java, dayId(7)) shouldBe "COMPLETE"
        milestones().map { it.dayId } shouldBe listOf(dayId(7))
    }

    /**
     * ADR-0035 question 9: Bea leaves before the seventh day's reveal time, so the job closes it
     * withheld, `PENDING_REVEAL`. The owner ruled on 2026-10-22 that it counts for milestones too.
     */
    @Test
    fun `a day both wrote on that an ended bond withheld still reaches its milestone`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00:00'::time WHERE id = ?::uuid", bond) shouldBe 1
        bothWriteOn(7)
        clock.set(at(7).plusSeconds(3_600))
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        closeThrough(7)

        jdbc.queryForObject("SELECT status FROM bond_days WHERE id = ?", String::class.java, dayId(7)) shouldBe "PENDING_REVEAL"
        jdbc.queryForObject("SELECT evaluated_as FROM bond_days WHERE id = ?", String::class.java, dayId(7)) shouldBe "COMPLETE"
        milestones().map { it.dayId } shouldBe listOf(dayId(7))
        mockMvc
            .get("/api/v1/bonds/$bond/milestones") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }
            // Dated on the withheld day. Nothing is next for a bond that has ended.
            .contentAsString shouldBe """{"achieved":[{"threshold":7,"achievedOn":"${day(7)}"}],"next":null}"""
    }

    /**
     * Counted on the timeline, the days evaluated `COMPLETE`, which is what `GET /milestones`
     * dates by, and never on the stored `total_complete_days`. Drifted three too high, the stored
     * total would announce 7 on the fourth complete day, and again on the seventh once a
     * recalculation had put it right: two announcements, the first naming a day the read never
     * shows. The timeline announces once, on its seventh complete day.
     */
    @Test
    fun `a drifted stored total neither moves nor repeats the announcement`() {
        (1..3).forEach { bothWriteOn(it) }
        closeThrough(3)
        jdbc.update("UPDATE streak_states SET total_complete_days = 6 WHERE bond_id = ?::uuid", bond) shouldBe 1

        bothWriteOn(4)
        closeThrough(4)
        recalculate.recalculate(UUID.fromString(bond)).after.totalCompleteDays shouldBe 4
        (5..7).forEach { bothWriteOn(it) }
        closeThrough(7)

        milestones().map { it.dayId } shouldBe listOf(dayId(7))
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
        // The day the read will date 7 by: the seventh evaluated COMPLETE.
        jdbc.queryForObject(
            "SELECT id FROM bond_days WHERE bond_id = ?::uuid AND evaluated_as = 'COMPLETE' ORDER BY date OFFSET 6 LIMIT 1",
            UUID::class.java,
            bond,
        ) shouldBe dayId(7)
    }

    /**
     * The same transaction as the day's record, held by failing it. A trigger makes the bond's
     * last statement, the `streak_states` update after day 7 was recorded and announced, raise:
     * the bond rolls back, and the announcement with it. Published in a transaction of its own,
     * it would stay, and the retry would announce again. `TRUNCATE` drops no trigger, so the
     * `finally` is what keeps it out of every other test.
     */
    @Test
    fun `a bond whose evaluation fails announces nothing, and its retry announces once`() {
        (1..7).forEach { bothWriteOn(it) }
        jdbc.execute(
            """
            CREATE FUNCTION milestone_test_refuse() RETURNS trigger LANGUAGE plpgsql AS
            ${'$'}${'$'} BEGIN RAISE EXCEPTION 'refused by MilestoneReachedTest'; END ${'$'}${'$'}
            """.trimIndent(),
        )
        try {
            jdbc.execute(
                "CREATE TRIGGER milestone_test_refuse BEFORE UPDATE ON streak_states FOR EACH ROW EXECUTE FUNCTION milestone_test_refuse()",
            )
            closer.closeElapsedDays(Instant.parse("${day(7)}T23:01:00Z"), BUDGET).failed shouldBe 1

            jdbc.queryForObject(
                "SELECT count(*) FROM bond_days WHERE bond_id = ?::uuid AND evaluated_at IS NOT NULL",
                Int::class.java,
                bond,
            ) shouldBe 0
            jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 0
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS milestone_test_refuse ON streak_states")
            jdbc.execute("DROP FUNCTION IF EXISTS milestone_test_refuse()")
        }

        closeThrough(7)

        milestones().map { it.dayId } shouldBe listOf(dayId(7))
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
    }

    /**
     * ADR-0041 decision 8: the count a milestone is crossed from is read under the bond's lock.
     * ShedLock is a courtesy, not the guarantee (`SchedulingConfiguration`), so two runs can
     * overlap; one that read the count before the lock, and then waited on it while another run
     * committed days, would count on from a number already stale, and miss a threshold or
     * announce one twice. Here a connection holds the bond's lock while the run waits on it, and
     * commits days 1 to 6 evaluated `COMPLETE`, as an overlapping run would. Released, the run
     * finds day 7 alone and announces 7 on it. Read before the lock, the count is 0 and day 7 is
     * the first complete day: nothing is announced.
     */
    @Test
    fun `a run that waited on the bond's lock counts from the days committed while it waited`() {
        (1..7).forEach { bothWriteOn(it) }
        (1..7).forEach { closeDay.settle(BondDayId(dayId(it)), Instant.parse("${day(7)}T23:01:00Z")) shouldBe CloseDay.Outcome.CLOSED }

        val evaluated =
            Executors.newSingleThreadExecutor().use { pool ->
                dataSource.connection.use { holder ->
                    holder.autoCommit = false
                    holder.createStatement().use { it.execute("SELECT 1 FROM bonds WHERE id = '$bond' FOR UPDATE") }
                    val run = pool.submit(Callable { streaks.evaluate() })
                    try {
                        await().atMost(Duration.ofSeconds(10)).until {
                            jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                                Int::class.java,
                            )!! >= 1
                        }
                        // The other run's work, committed while this one waits.
                        evaluateAsComplete(holder, 1..6) shouldBe 6
                        holder.commit()
                    } finally {
                        holder.rollback()
                    }
                    run.get(15, TimeUnit.SECONDS)
                }
            }

        (evaluated.days to evaluated.failed) shouldBe (1 to 0)
        milestones().map { it.dayId } shouldBe listOf(dayId(7))
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
    }

    // ---- helpers ----------------------------------------------------------

    /** Days [days] evaluated `COMPLETE` on [connection], as `StreakStore.recordDay` writes a day. */
    private fun evaluateAsComplete(
        connection: Connection,
        days: IntRange,
    ): Int =
        connection
            .prepareStatement(
                """
                UPDATE bond_days SET evaluated_at = now(), evaluated_as = 'COMPLETE', evaluated_strict = false,
                    freeze_applied = false, version = version + 1
                WHERE bond_id = ?::uuid AND date BETWEEN ? AND ? AND evaluated_at IS NULL
                """.trimIndent(),
            ).use {
                it.setString(1, bond)
                it.setObject(2, day(days.first))
                it.setObject(3, day(days.last))
                it.executeUpdate()
            }

    private data class Announced(
        val dayId: UUID,
        val aggregateType: String,
        val aggregateId: UUID,
        val bondId: String,
        val keys: String,
    )

    /** Every MilestoneReached, in the order the days were evaluated (each day's events share its `occurred_at`). */
    private fun milestones(): List<Announced> =
        jdbc.query(
            """
            SELECT (payload->>'bondDayId')::uuid AS day_id, aggregate_type, aggregate_id, payload->>'bondId' AS bond_id,
                   (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(payload) AS k) AS keys
            FROM outbox_events o JOIN bond_days d ON d.id = (o.payload->>'bondDayId')::uuid
            WHERE event_type = 'MilestoneReached' ORDER BY d.date
            """.trimIndent(),
        ) { row, _ ->
            Announced(
                row.getObject("day_id", UUID::class.java),
                row.getString("aggregate_type"),
                row.getObject("aggregate_id", UUID::class.java),
                row.getString("bond_id"),
                row.getString("keys"),
            )
        }

    private fun dayId(n: Int): UUID =
        jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid AND date = ?", UUID::class.java, bond, day(n))!!

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    /** Runs the job once, a minute after day *n* ends. */
    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), BUDGET).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        clock.set(at(n))
        submit(ada)
        submit(bea)
    }

    private fun writeOn(
        n: Int,
        author: UUID,
    ) {
        clock.set(at(n))
        submit(author)
    }

    private fun submit(caller: UUID) {
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response.status shouldBe 201
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

    private fun accept(
        userId: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        const val BUDGET = 1_000
    }
}
