package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.MissingDays
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * Spec §6.4 step 1: the days nobody opened. A Bond-day's row is made by the
 * first entry written on it, so a day neither member wrote on has none, and
 * the job has to write it — closed, `EMPTY` — or it never counted for
 * anything.
 *
 * All in `Africa/Lagos`, where the day dated *d* runs from 23:00Z on *d − 1*
 * to 23:00Z on *d*. "Now" is 10:00Z on 2026-09-15: the 14th has ended and
 * the 15th has not.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(MissingDaysTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class MissingDaysTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val missing: MissingDays,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
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
    fun `a couple who paired and never wrote get every day that has ended, closed EMPTY, and not today`() {
        val bond = pairedOn("2026-09-10").id

        val result = closer.closeElapsedDays(NOW, BUDGET)

        result.created shouldBe 5
        result.bondsChanged shouldBe setOf(UUID.fromString(bond))
        days(bond) shouldBe (10..14).associate { LocalDate.of(2026, 9, it) to "EMPTY" }
        jdbc.queryForObject("SELECT count(*) FROM bond_days WHERE closed_at IS NULL", Int::class.java) shouldBe 0
        // The 12th, as the timeline cut it — not a midnight worked out here.
        jdbc.queryForMap("SELECT starts_at, ends_at, anchor_timezone FROM bond_days WHERE date = '2026-09-12'").let {
            (it["starts_at"] as java.sql.Timestamp).toInstant() shouldBe Instant.parse("2026-09-11T23:00:00Z")
            (it["ends_at"] as java.sql.Timestamp).toInstant() shouldBe Instant.parse("2026-09-12T23:00:00Z")
            it["anchor_timezone"] shouldBe "Africa/Lagos"
        }
        events("DayClosed") shouldBe 5

        closer.closeElapsedDays(NOW.plusSeconds(900), BUDGET).created shouldBe 0
        events("DayClosed") shouldBe 5
    }

    @Test
    fun `a row opened today does not hide the days missing behind it`() {
        val bond = pairedOn("2026-09-10")
        clock.set(Instant.parse("2026-09-11T10:00:00Z"))
        submit(bond.ada, bond.id).status shouldBe 201
        clock.set(NOW)
        submit(bond.ada, bond.id).status shouldBe 201

        val result = closer.closeElapsedDays(NOW, BUDGET)

        result.created shouldBe 4
        result.closed shouldBe 1
        days(bond.id) shouldBe
            mapOf(
                LocalDate.of(2026, 9, 10) to "EMPTY",
                // Had a row, with an entry on it: settled by the sweep, not rewritten.
                LocalDate.of(2026, 9, 11) to "SOLO",
                LocalDate.of(2026, 9, 12) to "EMPTY",
                LocalDate.of(2026, 9, 13) to "EMPTY",
                LocalDate.of(2026, 9, 14) to "EMPTY",
                // Today: still running, still as the entry left it.
                LocalDate.of(2026, 9, 15) to "PARTIAL",
            )
    }

    @Test
    fun `no day is written for the time a bond was one person`() {
        val bond = pairedOn("2026-09-13", createdOn = "2026-09-09").id

        closer.closeElapsedDays(NOW, BUDGET).created shouldBe 2

        days(bond).keys shouldBe setOf(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 14))
    }

    @Test
    fun `a bond still waiting for its partner gets no days at all`() {
        clock.set(Instant.parse("2026-09-05T10:00:00Z"))
        createBond(users.verified("Ada"))
        clock.set(NOW)

        closer.closeElapsedDays(NOW, BUDGET).created shouldBe 0

        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 0
    }

    @Test
    fun `a bond that ended gets the days up to the one it ended on, and none after`() {
        val bond = pairedOn("2026-09-10")
        clock.set(Instant.parse("2026-09-12T10:00:00Z"))
        mockMvc
            .post("/api/v1/bonds/${bond.id}/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bond.ada)) }
            .andReturn()
            .response.status shouldBe 204
        clock.set(NOW)

        closer.closeElapsedDays(NOW, BUDGET).created shouldBe 3

        days(bond.id).keys shouldBe (10..12).map { LocalDate.of(2026, 9, it) }.toSet()
    }

    /**
     * Spec §6.4 step 1: never an `EMPTY` day "in pending-member, suspension,
     * deletion or archived intervals". A bond counting down to deletion
     * refuses every write, so a day in that month is not one the couple
     * missed.
     */
    @Test
    fun `a bond counting down to deletion gets no days written for its cooling-off`() {
        val bond = pairedOn("2026-09-10")
        clock.set(Instant.parse("2026-09-12T10:00:00Z"))
        for (member in listOf(bond.ada, bond.bea)) {
            mockMvc
                .post("/api/v1/bonds/${bond.id}/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(member)) }
                .andReturn()
                .response.status shouldBe 202
        }
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bond.id) shouldBe "PENDING_DELETION"
        clock.set(NOW)

        closer.closeElapsedDays(NOW, BUDGET).created shouldBe 3

        days(bond.id).keys shouldBe (10..12).map { LocalDate.of(2026, 9, it) }.toSet()
        closer.closeElapsedDays(NOW.plusSeconds(900), BUDGET).created shouldBe 0
    }

    @Test
    fun `a long silence is written four hundred days at a time, and the result says there is more`() {
        // 500 days before the 15th of September 2026, to the day.
        val bond = pairedOn("2025-05-03").id

        val first = closer.closeElapsedDays(NOW, BUDGET)
        first.created shouldBe DayCloser.MAX_CREATED_PER_RUN
        first.backlog shouldBe true
        // Oldest first: the run resumes from what is still missing, not from a remembered place.
        days(bond).keys.max() shouldBe LocalDate.of(2025, 5, 3).plusDays(399)

        val second = closer.closeElapsedDays(NOW, BUDGET)
        second.created shouldBe 100
        second.backlog shouldBe false
        days(bond).size shouldBe 500
        days(bond).keys.max() shouldBe LocalDate.of(2026, 9, 14)

        closer.closeElapsedDays(NOW, BUDGET).created shouldBe 0
    }

    @Test
    fun `the day a bond became two starts when the bond did, not at a midnight`() {
        val bond = pairedOn("2026-09-10").id

        closer.closeElapsedDays(NOW, BUDGET)

        // Created and joined at 10:00Z on the 10th: the calendar begins there.
        jdbc
            .queryForObject(
                "SELECT starts_at FROM bond_days WHERE bond_id = ?::uuid AND date = '2026-09-10'",
                java.sql.Timestamp::class.java,
                bond,
            )?.toInstant() shouldBe Instant.parse("2026-09-10T10:00:00Z")
    }

    /**
     * BR-3a after the job: yesterday was written `EMPTY` and closed, and a
     * phone that was offline yesterday now sends what was typed then. A
     * settled day takes no entry; the words are kept, on today.
     */
    @Test
    fun `an offline entry aimed at a day the job wrote EMPTY is filed on today, and that day stays empty`() {
        val bond = pairedOn("2026-09-13")
        closer.closeElapsedDays(NOW, BUDGET).created shouldBe 2

        val filed =
            mockMvc
                .post("/api/v1/bonds/${bond.id}/entries") {
                    header(HttpHeaders.AUTHORIZATION, bearer(bond.ada))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"typed yesterday","intendedAt":"2026-09-14T12:00:00Z"}"""
                }.andReturn()
                .response

        filed.status shouldBe 201
        filed.contentAsString shouldContain "\"date\":\"2026-09-15\""
        jdbc.queryForMap("SELECT status, entry_count FROM bond_days WHERE bond_id = ?::uuid AND date = '2026-09-14'", bond.id) shouldBe
            mapOf("status" to "EMPTY", "entry_count" to 0.toShort())
    }

    /**
     * The race with a submission, at the one statement where it is decided:
     * the date already has a row — a member's entry opened it a moment ago —
     * and the job's insert must do nothing to it.
     */
    @Test
    fun `a date that already has a row is left exactly as it is`() {
        val bond = pairedOn("2026-09-15")
        submit(bond.ada, bond.id).status shouldBe 201
        val today = DayWindow(LocalDate.of(2026, 9, 15), Instant.parse("2026-09-14T23:00:00Z"), Instant.parse("2026-09-15T23:00:00Z"))

        val written = missing.insertClosed(UUID.randomUUID(), UUID.fromString(bond.id), today, "Africa/Lagos", BondDayStatus.EMPTY, NOW)

        written shouldBe false
        jdbc.queryForMap("SELECT status, entry_count, closed_at FROM bond_days") shouldBe
            mapOf("status" to "PARTIAL", "entry_count" to 1.toShort(), "closed_at" to null)
    }

    private data class Paired(
        val id: String,
        val ada: UUID,
        val bea: UUID,
    )

    /** Created at 10:00Z on [createdOn] and joined at 10:00Z on [date]; the clock is left at [NOW]. */
    private fun pairedOn(
        date: String,
        createdOn: String = date,
    ): Paired {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        clock.set(Instant.parse("${createdOn}T10:00:00Z"))
        val created = createBond(ada)
        clock.set(Instant.parse("${date}T10:00:00Z"))
        mockMvc
            .post("/api/v1/invites/${codeOf(created)}/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
        clock.set(NOW)
        return Paired(idOf(created), ada, bea)
    }

    private fun days(bond: String): Map<LocalDate, String> =
        jdbc
            .query("SELECT date, status FROM bond_days WHERE bond_id = ?::uuid", { row, _ ->
                row.getObject(1, LocalDate::class.java) to row.getString(2)
            }, bond)
            .toMap()

    private fun events(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun submit(
        caller: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"hers"}"""
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
        const val BUDGET = 1_000
    }
}
