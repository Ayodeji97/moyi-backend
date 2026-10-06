package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * A deletion that counted down and was called off (FR-028), and what its days
 * are to the streak: the owner's ruling of 2026-10-06 on ADR-0034's first
 * question. Through the real routes and the real job, as `StreakEvaluationTest`.
 *
 * `Africa/Lagos`; day *n* is 2026-09-01 plus *n*; the bond is paired on day 0.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakAfterCancelledDeletionTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class StreakAfterCancelledDeletionTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
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
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
                .also { it.status shouldBe 201 }
                .contentAsString
        bond = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        val code = Regex(""""code":"([^"]+)"""").find(created)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
    }

    /** Before as well as after: the job works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    /**
     * The owner's ruling (ADR-0034, question 1). While a deletion counts down
     * the bond refuses every entry. Called off, those days were written
     * `EMPTY` and judged missed: a streak ended by a month in which nobody
     * was allowed to write. They are `SUSPENDED`, and the run goes on.
     */
    @Test
    fun `the days of a deletion that was called off are suspended, and the run goes on`() {
        (1..3).forEach { bothWriteOn(it) }
        closeThrough(3)
        clock.set(at(4))
        requestDeletion(ada)
        requestDeletion(bea)
        clock.set(at(7))
        cancelDeletion(bea)
        bothWriteOn(7)
        bothWriteOn(8)

        closeThrough(8)

        streak() shouldBe Streak(current = 5, longest = 5, lastComplete = day(8), freezes = 0, progress = 5, consumed = 0, total = 5)
        decisions().takeLast(5) shouldBe
            listOf(
                // Days 4 to 6 each ended with the bond taking no writes. Day 7: it took them again, and both wrote.
                Decision(day(4), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(5), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(6), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(7), "REVEALED", "COMPLETE", freeze = false),
                Decision(day(8), "REVEALED", "COMPLETE", freeze = false),
            )
        outbox("StreakBroken") shouldBe 0
    }

    /** The same, with the job running through the countdown as it really would. */
    @Test
    fun `a job that ran during the countdown does not change what the called-off days are`() {
        (1..3).forEach { bothWriteOn(it) }
        clock.set(at(4))
        requestDeletion(ada)
        requestDeletion(bea)
        // Mid-countdown: the day the bond stopped on is written, and nothing after it.
        closeThrough(5)
        decisions().last() shouldBe Decision(day(4), "EMPTY", "AFTER_THE_END", freeze = false)
        clock.set(at(7))
        cancelDeletion(ada)
        bothWriteOn(7)

        closeThrough(7)

        streak().let { (it.current to it.longest) shouldBe (4 to 4) }
        decisions().takeLast(3).map { "${it.date} ${it.status} ${it.evaluatedAs}" } shouldBe
            listOf("${day(5)} SUSPENDED SUSPENDED", "${day(6)} SUSPENDED SUSPENDED", "${day(7)} REVEALED COMPLETE")
        outbox("StreakBroken") shouldBe 0
    }

    /**
     * The day a deletion is called off is an ordinary day from then on: the
     * bond takes entries for the rest of it. Were any overlap enough, asking
     * and cancelling at ten to midnight would be a free rest day, every day.
     */
    @Test
    fun `the day a deletion is called off on is judged like any other`() {
        (1..3).forEach { bothWriteOn(it) }
        clock.set(at(4))
        requestDeletion(ada)
        requestDeletion(bea)
        clock.set(at(6))
        cancelDeletion(ada)
        writeOn(6, ada)

        closeThrough(6)

        streak().let { (it.current to it.longest) shouldBe (0 to 3) }
        decisions().takeLast(3).map { "${it.date} ${it.status} ${it.evaluatedAs}" } shouldBe
            listOf("${day(4)} SUSPENDED SUSPENDED", "${day(5)} SUSPENDED SUSPENDED", "${day(6)} SOLO MISSED")
    }

    /** One had written before the two of them asked. The day ended with the bond closed to the other: not a miss. */
    @Test
    fun `a day one had written on before the countdown began is suspended too, and keeps its entry's status`() {
        (1..3).forEach { bothWriteOn(it) }
        writeOn(4, ada)
        clock.set(at(4).plusSeconds(3_600))
        requestDeletion(ada)
        requestDeletion(bea)
        clock.set(at(6))
        cancelDeletion(bea)
        bothWriteOn(6)

        closeThrough(6)

        streak().let { (it.current to it.longest) shouldBe (4 to 4) }
        decisions().takeLast(3).map { "${it.date} ${it.status} ${it.evaluatedAs}" } shouldBe
            listOf("${day(4)} SOLO SUSPENDED", "${day(5)} SUSPENDED SUSPENDED", "${day(6)} REVEALED COMPLETE")
    }

    @Test
    fun `asking and calling off within one day suspends nothing`() {
        (1..3).forEach { bothWriteOn(it) }
        clock.set(at(4))
        requestDeletion(ada)
        requestDeletion(bea)
        cancelDeletion(bea)

        closeThrough(4)

        streak().current shouldBe 0
        decisions().last() shouldBe Decision(day(4), "EMPTY", "MISSED", freeze = false)
    }

    @Test
    fun `no freeze is spent on a suspended day, and the day after the countdown can spend one`() {
        (1..14).forEach { bothWriteOn(it) }
        clock.set(at(15))
        requestDeletion(ada)
        requestDeletion(bea)
        clock.set(at(17))
        cancelDeletion(ada)

        closeThrough(17)

        decisions().takeLast(3) shouldBe
            listOf(
                Decision(day(15), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(16), "SUSPENDED", "SUSPENDED", freeze = false),
                // Day 17: called off at ten, nobody wrote for the rest of it. A missed day; the banked freeze covers it.
                Decision(day(17), "FROZEN", "MISSED", freeze = true),
            )
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(15, 0, 1) }
    }

    /** The other door out of a countdown: one member left during it, so calling it off ends the bond instead. */
    @Test
    fun `a countdown called off after a member left leaves a streak that is kept, not broken`() {
        (1..3).forEach { bothWriteOn(it) }
        clock.set(at(4))
        requestDeletion(ada)
        requestDeletion(bea)
        clock.set(at(5))
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204
        clock.set(at(7))
        cancelDeletion(ada)

        closeThrough(9)

        streak().let { (it.current to it.longest) shouldBe (3 to 3) }
        outbox("StreakBroken") shouldBe 0
        decisions().takeLast(4).map { "${it.date} ${it.status} ${it.evaluatedAs}" } shouldBe
            listOf(
                "${day(4)} SUSPENDED SUSPENDED",
                "${day(5)} SUSPENDED SUSPENDED",
                "${day(6)} SUSPENDED SUSPENDED",
                // The day the bond ended on: missed after the end, and moves nothing.
                "${day(7)} EMPTY AFTER_THE_END",
            )
    }

    @Test
    fun `two countdowns called off are two stretches, each suspended`() {
        (1..3).forEach { bothWriteOn(it) }
        clock.set(at(4))
        requestDeletion(ada)
        requestDeletion(bea)
        clock.set(at(5))
        cancelDeletion(ada)
        bothWriteOn(5)
        clock.set(at(6))
        requestDeletion(bea)
        requestDeletion(ada)
        clock.set(at(7))
        cancelDeletion(bea)
        bothWriteOn(7)

        closeThrough(7)

        jdbc.queryForObject("SELECT count(*) FROM bond_write_pauses", Int::class.java) shouldBe 2
        decisions().takeLast(4).map { "${it.date} ${it.evaluatedAs}" } shouldBe
            listOf("${day(4)} SUSPENDED", "${day(5)} COMPLETE", "${day(6)} SUSPENDED", "${day(7)} COMPLETE")
        streak().current shouldBe 5
    }

    // ---- helpers ----------------------------------------------------------

    private data class Streak(
        val current: Int,
        val longest: Int,
        val lastComplete: LocalDate?,
        val freezes: Int,
        val progress: Int,
        val consumed: Int,
        val total: Int,
    )

    private data class Decision(
        val date: LocalDate,
        val status: String,
        val evaluatedAs: String?,
        val freeze: Boolean?,
    )

    private fun streak(): Streak =
        jdbc
            .query("SELECT * FROM streak_states WHERE bond_id = ?::uuid", { row, _ ->
                Streak(
                    row.getInt("current_streak"),
                    row.getInt("longest_streak"),
                    row.getObject("last_complete_date", LocalDate::class.java),
                    row.getInt("freezes_available"),
                    row.getInt("freeze_progress"),
                    row.getInt("freezes_consumed"),
                    row.getInt("total_complete_days"),
                )
            }, bond)
            .single()

    private fun decisions(): List<Decision> =
        jdbc.query(
            "SELECT date, status, evaluated_as, freeze_applied FROM bond_days " +
                "WHERE bond_id = ?::uuid AND evaluated_at IS NOT NULL ORDER BY date",
            { row, _ ->
                Decision(
                    row.getObject("date", LocalDate::class.java),
                    row.getString("status"),
                    row.getString("evaluated_as"),
                    row.getObject("freeze_applied") as Boolean?,
                )
            },
            bond,
        )

    private fun outbox(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), 1_000).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        writeOn(n, ada)
        writeOn(n, bea)
    }

    private fun writeOn(
        n: Int,
        author: UUID,
    ) {
        clock.set(at(n))
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(author))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response.status shouldBe 201
    }

    private fun requestDeletion(member: UUID) {
        mockMvc
            .post("/api/v1/bonds/$bond/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(member)) }
            .andReturn()
            .response.status shouldBe 202
    }

    private fun cancelDeletion(member: UUID) {
        mockMvc
            .delete("/api/v1/bonds/$bond/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(member)) }
            .andReturn()
            .response.status shouldBe 204
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-01T10:00:00Z"))
    }
}
