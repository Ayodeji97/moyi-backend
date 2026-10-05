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

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `each bond's day closes when its own calendar says, whatever the others are doing`() {
        val auckland = bondWithOneEntry("Pacific/Auckland")
        val kathmandu = bondWithOneEntry("Asia/Kathmandu")
        val lagos = bondWithOneEntry("Africa/Lagos")

        // Auckland's day ended at 12:00Z, and is settled a minute later (`DayCloser.SETTLE_MARGIN`).
        closer.closeElapsedDays(Instant.parse("2026-09-15T12:00:59Z"), BUDGET).closed shouldBe 0

        val afterAuckland = closer.closeElapsedDays(Instant.parse("2026-09-15T12:01:00Z"), BUDGET)
        afterAuckland.closed shouldBe 1
        afterAuckland.bondsChanged shouldBe setOf(UUID.fromString(auckland))
        statuses() shouldBe mapOf(auckland to "SOLO", kathmandu to "PARTIAL", lagos to "PARTIAL")

        // 18:15Z: Kathmandu's quarter-hour offset is why the job runs every fifteen minutes.
        closer.closeElapsedDays(Instant.parse("2026-09-15T18:15:59Z"), BUDGET).closed shouldBe 0
        closer.closeElapsedDays(Instant.parse("2026-09-15T18:16:00Z"), BUDGET).closed shouldBe 1
        statuses() shouldBe mapOf(auckland to "SOLO", kathmandu to "SOLO", lagos to "PARTIAL")

        closer.closeElapsedDays(Instant.parse("2026-09-15T23:01:00Z"), BUDGET).closed shouldBe 1
        statuses() shouldBe mapOf(auckland to "SOLO", kathmandu to "SOLO", lagos to "SOLO")
    }

    @Test
    fun `a second run finds nothing the first one finished`() {
        repeat(3) { bondWithOneEntry("Africa/Lagos") }

        val first = closer.closeElapsedDays(LAGOS_END, BUDGET)
        val second = closer.closeElapsedDays(LAGOS_END.plusSeconds(900), BUDGET)

        first.closed shouldBe 3
        first.backlog shouldBe false
        second shouldBe second.copy(created = 0, closed = 0, revealed = 0, failed = 0, bondsChanged = emptySet(), backlog = false)
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

    @Test
    fun `an ended bond's day that was waiting on its reveal time is still revealed when the time comes`() {
        val (bond, ada, bea) = pairedBond("Africa/Lagos")
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00' WHERE id = ?::uuid", bond)
        submit(ada, bond, "hers").status shouldBe 201
        submit(bea, bond, "his").status shouldBe 201
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        closer.closeElapsedDays(Instant.parse("2026-09-15T18:59:00Z"), BUDGET).revealed shouldBe 0
        closer.closeElapsedDays(Instant.parse("2026-09-15T19:00:00Z"), BUDGET).revealed shouldBe 1

        statuses()[bond] shouldBe "REVEALED"
        events("DayRevealed") shouldBe 1
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
        repeat(5) {
            val (bond, ada, bea) = pairedBond("Africa/Lagos")
            jdbc.update("UPDATE bonds SET reveal_time_local = '22:00' WHERE id = ?::uuid", bond)
            submit(ada, bond, "hers").status shouldBe 201
            submit(bea, bond, "his").status shouldBe 201
        }
        val ended = bondWithOneEntry("Pacific/Auckland")

        // Auckland's day is over at 12:00Z; the five Lagos days are pending until 21:00Z.
        val result =
            assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                ThrowingSupplier { closer.closeElapsedDays(Instant.parse("2026-09-15T12:01:00Z"), budget = 1) },
            )

        result.closed shouldBe 1
        result.backlog shouldBe false
        statuses()[ended] shouldBe "SOLO"
    }

    @Test
    fun `the scan for days that may have ended can use its index`() {
        // The statement the job runs, not a copy of it.
        val plan =
            dataSource.connection
                .use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
                    connection.prepareStatement("EXPLAIN " + CloseCandidates.SQL).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(LAGOS_END))
                        statement.setObject(2, UUID(0, 0))
                        statement.setObject(3, java.time.LocalDate.of(1, 1, 1))
                        statement.setInt(4, 200)
                        statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                    }
                }.joinToString("\n")

        plan shouldContain "bond_days_unclosed_idx"
    }

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

        /** A minute past Lagos's midnight: the first instant the job settles the 15th (`DayCloser.SETTLE_MARGIN`). */
        val LAGOS_END: Instant = Instant.parse("2026-09-15T23:01:00Z")
        const val BUDGET = 1_000
    }
}
