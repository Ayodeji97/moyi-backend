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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-074: `recalculate` "MUST produce identical results". After the close job
 * has evaluated a bond's days, rebuilding the streak from those days must
 * give back the row the job left — for a history with a banked freeze, a
 * freeze spent, a break, and Strict mode on for part of it.
 *
 * `Africa/Lagos`; day *n* is 2026-09-01 plus *n*; the bond is paired on day 0.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(RecalculateStreakTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class RecalculateStreakTest(
    @Autowired private val recalculate: RecalculateStreak,
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
        bond = Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
    }

    /** Before as well as after ([setUp] calls it first): the job works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    /**
     * Thirty-four days with everything in them: fourteen complete days bank a
     * freeze; a missed day spends it; Strict mode goes on, fourteen more
     * complete days bank nothing, and a missed day ends the run; Strict mode
     * goes off and three days begin a new one.
     */
    private fun aHistoryWithEverythingInIt() {
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(15)
        strictMode(true)
        (16..29).forEach { bothWriteOn(it) }
        closeThrough(30)
        strictMode(false)
        (31..33).forEach { bothWriteOn(it) }
        closeThrough(33)
    }

    @Test
    fun `recalculating after the job has run changes nothing`() {
        aHistoryWithEverythingInIt()
        val stored = state()
        stored shouldBe
            mapOf(
                "current_streak" to 3,
                "longest_streak" to 29,
                "last_complete_date" to java.sql.Date.valueOf(day(33)),
                "freezes_available" to 0.toShort(),
                "freeze_progress" to 3.toShort(),
                "freezes_consumed" to 1,
                "total_complete_days" to 31,
            )

        val result = recalculate.recalculate(UUID.fromString(bond))

        result.changed shouldBe false
        state() shouldBe stored
        jdbc.queryForObject("SELECT recomputed_at IS NOT NULL FROM streak_states", Boolean::class.java) shouldBe true
    }

    @Test
    fun `a streak row that has drifted from its days is put back`() {
        aHistoryWithEverythingInIt()
        val stored = state()
        jdbc.update("UPDATE streak_states SET current_streak = 99, longest_streak = 99, freezes_available = 2, total_complete_days = 0")

        recalculate.recalculate(UUID.fromString(bond)).changed shouldBe true

        state() shouldBe stored
    }

    /** FR-073, at the one place it could be broken after the fact: a replay asks the day, not the bond. */
    @Test
    fun `recalculating with Strict mode since switched the other way decides no day again`() {
        aHistoryWithEverythingInIt()
        val stored = state()
        // On now: decided afresh, day 15 would spend no freeze and the first run would have ended at fourteen.
        strictMode(true)

        recalculate.recalculate(UUID.fromString(bond)).changed shouldBe false

        state() shouldBe stored
    }

    @Test
    fun `recalculating touches no day and announces nothing`() {
        aHistoryWithEverythingInIt()
        val days =
            jdbc.queryForList(
                "SELECT id, status, version, evaluated_at, evaluated_as, evaluated_strict, freeze_applied FROM bond_days ORDER BY date",
            )
        val events = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java)
        val log = jdbc.queryForObject("SELECT count(*) FROM streak_events", Int::class.java)

        recalculate.recalculate(UUID.fromString(bond))

        jdbc.queryForList(
            "SELECT id, status, version, evaluated_at, evaluated_as, evaluated_strict, freeze_applied FROM bond_days ORDER BY date",
        ) shouldBe
            days
        jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java) shouldBe events
        jdbc.queryForObject("SELECT count(*) FROM streak_events", Int::class.java) shouldBe log
    }

    @Test
    fun `a bond none of whose days has been evaluated recalculates to nothing`() {
        val result = recalculate.recalculate(UUID.fromString(bond))

        result.changed shouldBe false
        state()["current_streak"] shouldBe 0
    }

    private fun state(): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT current_streak, longest_streak, last_complete_date, freezes_available, freeze_progress, " +
                "freezes_consumed, total_complete_days FROM streak_states WHERE bond_id = ?::uuid",
            bond,
        )

    private fun strictMode(on: Boolean) {
        jdbc.update("UPDATE bonds SET strict_mode = ? WHERE id = ?::uuid", on, bond)
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), 1_000).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        clock.set(at(n))
        for (member in listOf(ada, bea)) submit(member).status shouldBe 201
    }

    private fun submit(caller: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-01T10:00:00Z"))
    }
}
