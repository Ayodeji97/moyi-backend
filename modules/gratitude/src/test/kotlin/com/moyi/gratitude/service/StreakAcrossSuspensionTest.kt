package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeAccountStanding
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.AccountStanding
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Admin A3, spec §4 (ruling 16) and §9: a suspension's days are not days anybody missed. No
 * freeze is spent on them and the run goes on, as after a called-off deletion
 * (`StreakAfterCancelledDeletionTest`, the model). Through the real routes and the real job;
 * spans through [FakeAccountStanding].
 *
 * `Africa/Lagos`; day *n* is 2026-09-01 plus *n*; the bond is paired on day 0. `ada` is suspended.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakAcrossSuspensionTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class StreakAcrossSuspensionTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val missingDays: CreateMissingDays,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired standing: AccountStanding,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val suspensions = standing as FakeAccountStanding
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
        suspensions.reset()
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    /**
     * Spec §9: a 5-day run with one freeze banked; pause three days, lift, both write: run 6, the
     * freeze kept. The freeze is banked by fourteen days, and the run that banked it ended by a
     * day missed in Strict mode (which spends none), so the run is five with a freeze in hand.
     */
    @Test
    fun `three days of a suspension are SUSPENDED, no freeze is spent, and the run goes on`() {
        // Drop the EvaluateStreaks branch and write the gap EMPTY in CreateMissingDays: day 21 spends the freeze.
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        clock.set(at(15))
        strictMode(true)
        closeThrough(15)
        clock.set(at(16))
        strictMode(false)
        (16..20).forEach { bothWriteOn(it) }
        closeThrough(20)
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(5, 1, 0) }

        suspensions.suspend(ada, at(21))
        suspensions.lift(ada, at(24))
        bothWriteOn(24)

        closeThrough(24)

        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(6, 1, 0) }
        decisions().takeLast(4) shouldBe
            listOf(
                Decision(day(21), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(22), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(23), "SUSPENDED", "SUSPENDED", freeze = false),
                // The lift day: both wrote after the lift, so it is complete.
                Decision(day(24), "REVEALED", "COMPLETE", freeze = false),
            )
        outbox("StreakBroken") shouldBe 1 // day 15's, in Strict mode; none since
    }

    /**
     * The late-entry grace is the day's (controller ruling amending ruling 16): day 15, which
     * nobody wrote on, ends at 23:00Z; a suspension begins at 01:00Z on day 16, inside its grace,
     * when a late entry for it would be refused. It is `SUSPENDED`, and the banked freeze is kept.
     */
    @Test
    fun `a day nobody wrote on, suspended in its late-entry hours, spends no freeze`() {
        // Overlap judged on [startsAt, endsAt) again: day 15 is EMPTY, MISSED, and spends the freeze.
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(14, 1, 0) }
        suspensions.suspend(ada, Instant.parse("${day(16)}T01:00:00Z"))
        suspensions.lift(ada, Instant.parse("${day(16)}T05:00:00Z"))

        closeThrough(15)

        decisions().last() shouldBe Decision(day(15), "SUSPENDED", "SUSPENDED", freeze = false)
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(14, 1, 0) }
        outbox("StreakBroken") shouldBe 0
    }

    /**
     * Belt and braces (spec §6, as `EvaluateStreaks` does for `wasPausedAt`): a day whose status
     * says missed, and which overlaps a span, is evaluated SUSPENDED. No real ordering produces
     * one (spec §4: a span never reaches back over a day already ended), so the gap is written
     * EMPTY by the close job's first step before the span is recorded, and then the job runs.
     */
    @Test
    fun `a day missed by its status that overlaps a span is evaluated SUSPENDED`() {
        // Drop the EvaluateStreaks branch: day 4 is MISSED and the run of three is broken.
        (1..3).forEach { bothWriteOn(it) }
        closeThrough(3)
        missingDays.create(closeAt(4), closeAt(4).minusSeconds(60 + 12 * 3_600), 1_000).failed shouldBe 0
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?", String::class.java, bond, day(4)) shouldBe
            "EMPTY"
        suspensions.suspend(ada, at(4))
        suspensions.lift(ada, at(4).plusSeconds(3_600))

        closeThrough(4)

        decisions().last() shouldBe Decision(day(4), "EMPTY", "SUSPENDED", freeze = false)
        streak().current shouldBe 3
        outbox("StreakBroken") shouldBe 0
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

    private fun closeAt(n: Int): Instant = Instant.parse("${day(n + 1)}T11:01:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(closeAt(n), 1_000).failed shouldBe 0
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

    /** As `StreakEvaluationTest`: the bond's settings, with its current tag. */
    private fun strictMode(on: Boolean) {
        val etag =
            mockMvc
                .get("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
                .andReturn()
                .response
                .getHeader(HttpHeaders.ETAG)!!
        mockMvc
            .patch("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                header(HttpHeaders.IF_MATCH, etag)
                contentType = MediaType.APPLICATION_JSON
                content = """{"strictMode":$on}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-01T10:00:00Z"))
    }
}
